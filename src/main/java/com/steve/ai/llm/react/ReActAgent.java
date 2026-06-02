package com.steve.ai.llm.react;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.PromptBuilder;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.async.AsyncLLMClient;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ReAct (Reason + Act) agent. Holds a scratchpad of past thoughts/actions/observations
 * and loops: call LLM -> parse step -> wait for game thread to execute -> feed observation ->
 * next LLM call. Finishes when the LLM emits is_final=true, when maxSteps is reached, or
 * when parse errors exceed tolerance (hard fail, no fallback to Plan-and-Execute).
 *
 * <p><b>Threading:</b> LLM calls run on the client thread pool. The game thread calls
 * <code>consumeNextStep</code>, <code>feedObservation</code>, and the query methods.
 * Internal state uses volatile + a lock to keep things consistent.</p>
 */
public class ReActAgent {

    private static final String ALLOWED_ACTIONS =
        "attack, build, mine, follow, pathfind, gather, craft, mcp";

    private final SteveEntity steve;
    private final String originalCommand;
    private final StringBuilder scratchpad = new StringBuilder();
    private final int maxSteps;
    private final int obsTruncate;
    private final int maxConsecutiveFailures;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile boolean started = false;
    private volatile boolean finished = false;
    private volatile boolean failed = false;
    private volatile String finalAnswer = null;
    private volatile String failureMessage = null;
    private volatile ResponseParser.ParsedResponse pendingStep = null;
    private volatile int stepCount = 0;
    private volatile int consecutiveFailures = 0;
    private volatile boolean observationPending = false;

    public ReActAgent(SteveEntity steve, String command, int maxSteps, int obsTruncate, int maxConsecutiveFailures) {
        this.steve = steve;
        this.originalCommand = command;
        this.maxSteps = maxSteps;
        this.obsTruncate = obsTruncate;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    public SteveEntity getSteve() {
        return steve;
    }

    public String getOriginalCommand() {
        return originalCommand;
    }

    /**
     * Kick off the first LLM call. Non-blocking. Must only be called once.
     *
     * <p>The <code>params</code> map should already contain <code>systemPrompt</code>,
     * <code>model</code>, <code>maxTokens</code>, <code>temperature</code>. The agent
     * will refresh <code>systemPrompt</code> every call to keep it current with
     * available templates / MCP tools.</p>
     */
    public void startAsync(AsyncLLMClient client, Map<String, Object> baseParams) {
        lock.lock();
        try {
            if (started) {
                SteveMod.LOGGER.warn("[ReAct] startAsync called twice for '{}'", originalCommand);
                return;
            }
            started = true;
        } finally {
            lock.unlock();
        }

        runStep(client, baseParams);
    }

    private void runStep(AsyncLLMClient client, Map<String, Object> baseParams) {
        if (finished || failed) {
            return;
        }

        int stepNum;
        lock.lock();
        try {
            stepCount++;
            stepNum = stepCount;
        } finally {
            lock.unlock();
        }

        if (stepNum > maxSteps) {
            markFinished("Reached max steps (" + maxSteps + ") without finishing");
            return;
        }

        // Refresh system prompt so available templates/MCP tools are current
        Map<String, Object> params = new java.util.HashMap<>(baseParams);
        params.put("systemPrompt", PromptBuilder.buildReActSystemPrompt(maxSteps));

        String prompt = PromptBuilder.buildReActUserPrompt(steve, originalCommand, scratchpad.toString());

        SteveMod.LOGGER.info("[ReAct step {}/{}] Steve '{}' thinking for command: {}",
            stepNum, maxSteps, steve.getSteveName(), originalCommand);

        client.sendAsync(prompt, params)
            .thenAccept(response -> {
                String content = response.getContent();
                if (content == null || content.isEmpty()) {
                    handleParseFailure("LLM returned empty response", stepNum);
                    scheduleNext(client, baseParams);
                    return;
                }

                SteveMod.LOGGER.debug("[ReAct step {}] raw LLM response: {}", stepNum, content);

                ResponseParser.ParsedResponse step = ResponseParser.parseReActStep(content);
                if (step == null) {
                    String snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    handleParseFailure("Response not valid JSON: " + snippet, stepNum);
                    scheduleNext(client, baseParams);
                    return;
                }

                consecutiveFailures = 0; // successful parse resets the counter

                if (step.isFinal()) {
                    String answer = step.getFinalAnswer() != null ? step.getFinalAnswer() : step.getReasoning();
                    SteveMod.LOGGER.info("[ReAct step {}/{}] FINAL: {}",
                        stepNum, maxSteps, answer);
                    markFinished(answer);
                    return;
                }

                if (step.getTasks().isEmpty()) {
                    handleParseFailure("Step has no action and is not final", stepNum);
                    scheduleNext(client, baseParams);
                    return;
                }

                var task = step.getTasks().get(0);
                if (!isAllowedAction(task.getAction())) {
                    feedObservationInternal("Invalid action: '" + task.getAction() + "'. Allowed: " + ALLOWED_ACTIONS);
                    scheduleNext(client, baseParams);
                    return;
                }

                appendScratchpad("Step " + stepNum + ":\nThought: "
                    + truncate(step.getReasoning(), 200)
                    + "\nAction: " + task.getAction()
                    + "\nParameters: " + task.getParameters());

                SteveMod.LOGGER.info("[ReAct step {}/{}] thought='{}' action={} params={}",
                    stepNum, maxSteps,
                    truncate(step.getReasoning(), 80),
                    task.getAction(),
                    task.getParameters());

                pendingStep = step;
                observationPending = true;
            })
            .exceptionally(throwable -> {
                SteveMod.LOGGER.error("[ReAct] LLM call failed at step {}: {}", stepNum, throwable.getMessage());
                markFailed("LLM call failed: " + throwable.getMessage());
                return null;
            });
    }

    private void scheduleNext(AsyncLLMClient client, Map<String, Object> baseParams) {
        // When called from the LLM completion callback, observationPending is true only
        // if we successfully set a pendingStep. Otherwise, we recursively continue the loop.
        lock.lock();
        try {
            if (finished || failed) {
                return;
            }
            if (observationPending) {
                // The game thread will call feedObservation; do not auto-schedule.
                return;
            }
        } finally {
            lock.unlock();
        }
        runStep(client, baseParams);
    }

    private void handleParseFailure(String message, int stepNum) {
        consecutiveFailures++;
        SteveMod.LOGGER.warn("[ReAct step {}] parse failure ({} of {}): {}",
            stepNum, consecutiveFailures, maxConsecutiveFailures, message);
        feedObservationInternal("[ERROR] " + message);
        if (consecutiveFailures >= maxConsecutiveFailures) {
            markFailed("Too many parse failures (" + maxConsecutiveFailures + " in a row)");
        }
    }

    private void markFinished(String answer) {
        lock.lock();
        try {
            finished = true;
            finalAnswer = answer;
            pendingStep = null;
            observationPending = false;
        } finally {
            lock.unlock();
        }
    }

    private void markFailed(String message) {
        lock.lock();
        try {
            failed = true;
            failureMessage = message;
            pendingStep = null;
            observationPending = false;
        } finally {
            lock.unlock();
        }
    }

    private void feedObservationInternal(String text) {
        String truncated = truncate(text, obsTruncate);
        appendScratchpad("Observation: " + truncated + "\n");
    }

    private void appendScratchpad(String text) {
        lock.lock();
        try {
            scratchpad.append(text).append("\n");
            // Soft cap: keep scratchpad under ~12k characters (rough prompt budget)
            int cap = 12_000;
            if (scratchpad.length() > cap) {
                // Drop the oldest complete step
                String s = scratchpad.toString();
                int cutoff = s.indexOf("\nStep ", cap / 2);
                if (cutoff > 0) {
                    scratchpad.setLength(0);
                    scratchpad.append("(earlier steps trimmed)\n");
                    scratchpad.append(s.substring(cutoff + 1));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isAllowedAction(String action) {
        if (action == null) return false;
        for (String a : ALLOWED_ACTIONS.split(", ")) {
            if (a.equals(action)) return true;
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---- public API for the game thread ----

    public boolean isFinished() {
        return finished;
    }

    public boolean failed() {
        return failed;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public int getStepCount() {
        return stepCount;
    }

    /**
     * True when there is a step ready to execute, or the agent has reached a terminal state.
     */
    public boolean isReadyNextStep() {
        return finished || failed || (observationPending && pendingStep != null);
    }

    /**
     * Take the pending step for execution. Returns null when nothing is ready or the
     * agent is finished/failed. After this call, observationPending is reset to false
     * — the caller MUST eventually call feedObservation to advance the loop.
     */
    public ResponseParser.ParsedResponse consumeNextStep() {
        lock.lock();
        try {
            if (finished || failed) {
                return null;
            }
            if (!observationPending || pendingStep == null) {
                return null;
            }
            ResponseParser.ParsedResponse step = pendingStep;
            pendingStep = null;
            observationPending = false;
            return step;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Feed the result of an executed action back into the scratchpad. Must be called
     * after <code>consumeNextStep</code> to advance the loop. Pass the same client and
     * baseParams that were used in <code>startAsync</code>.
     */
    public void feedObservation(ActionResult result, AsyncLLMClient client, Map<String, Object> baseParams) {
        if (result == null) {
            feedObservationInternal("(no result)");
        } else {
            String status = result.isSuccess() ? "OK" : "FAIL";
            String msg = result.getMessage();
            feedObservationInternal("[" + status + "] " + (msg == null || msg.isEmpty() ? "(empty)" : msg));
        }
        scheduleNext(client, baseParams);
    }

    /**
     * Feed a raw observation string. Used for invalid actions / parse failures where
     * we have no ActionResult.
     */
    public void feedObservation(String rawText, AsyncLLMClient client, Map<String, Object> baseParams) {
        feedObservationInternal(rawText);
        scheduleNext(client, baseParams);
    }

    public String getScratchpadSnapshot() {
        return scratchpad.toString();
    }
}

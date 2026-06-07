import {useEffect, useMemo, useRef, useState} from 'react';
import {usePlanStore} from './hooks/usePlanStore';
import {Structure3D} from './components/Structure3D';
import type {ChatMessage} from './lib/types';

function useCountdown(deadlineMs: number): string {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!deadlineMs) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [deadlineMs]);
  if (!deadlineMs) return '';
  const sec = Math.max(0, Math.round((deadlineMs - now) / 1000));
  return `${sec}s`;
}

function phaseBadge(phase: string): string {
  if (!phase) return 'bg-zinc-700 text-zinc-200 border-zinc-600';
  const p = phase.toLowerCase();
  if (p.startsWith('awaiting')) return 'bg-amber-500/20 text-amber-300 border-amber-500/40';
  if (p === 'design') return 'bg-sky-500/20 text-sky-300 border-sky-500/40';
  if (p === 'construction') return 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40';
  if (p === 'completed') return 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40';
  if (p === 'failed') return 'bg-rose-500/20 text-rose-300 border-rose-500/40';
  return 'bg-zinc-700 text-zinc-200 border-zinc-600';
}

function senderAccent(sender: ChatMessage['sender']): string {
  if (sender === 'USER') return 'bg-emerald-900/40 border-emerald-700/50 self-end';
  if (sender === 'STEVE') return 'bg-sky-900/40 border-sky-700/50 self-start';
  return 'bg-amber-900/30 border-amber-700/40 self-center italic';
}

function senderName(sender: ChatMessage['sender'], steveName: string): string {
  if (sender === 'USER') return 'You';
  if (sender === 'STEVE') return steveName || 'Steve';
  return 'System';
}

interface ChatPanelProps {
  messages: ChatMessage[];
  steves: string[];
  defaultSteve?: string;
  onSend: (steveName: string, message: string) => void;
  disabled?: boolean;
  placeholder?: string;
}

function ChatPanel({messages, steves, defaultSteve, onSend, disabled, placeholder}: ChatPanelProps) {
  const [draft, setDraft] = useState('');
  const [target, setTarget] = useState(defaultSteve ?? '');
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (defaultSteve && !target) setTarget(defaultSteve);
  }, [defaultSteve, target]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages.length]);

  const submit = () => {
    const msg = draft.trim();
    if (!msg) return;
    const steve = target || steves[0] || defaultSteve;
    if (!steve) return;
    onSend(steve, msg);
    setDraft('');
  };

  const list = Array.isArray(messages) ? messages : [];

  return (
    <div className="flex h-full min-h-0 flex-col gap-2">
      <div ref={scrollRef} className="chat-log flex-1 min-h-[220px] max-h-[50vh] overflow-y-auto pr-1 flex flex-col gap-2 py-1">
        {list.length === 0
          ? <p className="text-zinc-500 text-xs text-center my-auto">No messages yet.</p>
          : list.map((m) => (
              <div key={m.id} className={`max-w-[80%] rounded-xl px-3 py-2 border text-xs leading-relaxed break-words ${senderAccent(m.sender)}`}>
                <div className="flex justify-between gap-2 mb-1 text-[10px] text-zinc-400">
                  <span className="font-semibold">{senderName(m.sender, m.steveName)}</span>
                  <span>{new Date(m.ts).toLocaleTimeString()}</span>
                </div>
                <div>{m.message}</div>
              </div>
            ))}
      </div>
      <div className="grid grid-cols-[110px_1fr_auto] gap-1.5 pt-1.5 border-t border-zinc-700">
        <select
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          disabled={disabled || steves.length === 0}
          title="Target Steve"
          className="bg-zinc-900 border border-zinc-700 rounded-md px-2 py-1.5 text-xs outline-none focus:border-rose-500 disabled:opacity-50"
        >
          {steves.length === 0 && <option value="">(no Steve yet)</option>}
          {steves.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <input
          type="text"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
          placeholder={disabled ? 'Connect to a Steve first' : (placeholder ?? 'Tell Steve what to do…')}
          disabled={disabled}
          className="bg-zinc-900 border border-zinc-700 rounded-md px-2 py-1.5 text-xs outline-none focus:border-rose-500 disabled:opacity-50"
        />
        <button
          onClick={submit}
          disabled={disabled || !draft.trim() || !target}
          className="bg-rose-600 hover:bg-rose-700 disabled:bg-zinc-700 disabled:opacity-50 text-white text-xs uppercase tracking-wider px-3 py-1.5 rounded-md transition"
        >Send</button>
      </div>
    </div>
  );
}

function LandingPanel({
  messages, steves, defaultSteve, onChat, onStartPlan, connected,
}: {
  messages: ChatMessage[];
  steves: string[];
  defaultSteve: string;
  onChat: (s: string, m: string) => void;
  onStartPlan: (desc: string) => Promise<{ok: boolean; error?: string}>;
  connected: boolean;
}) {
  const [desc, setDesc] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submitPlan = async () => {
    const d = desc.trim();
    if (!d) return;
    setBusy(true);
    setError(null);
    const r = await onStartPlan(d);
    setBusy(false);
    if (r.ok) {
      setDesc('');
    } else {
      setError(r.error ?? 'failed to start plan');
    }
  };

  return (
    <div className="grid gap-2.5 min-h-0 grid-cols-1 lg:grid-cols-2">
      <section className="bg-black/50 border border-zinc-800 rounded-2xl p-4 flex flex-col gap-3">
        <h2 className="m-0 text-xs uppercase tracking-wider text-zinc-400">Plan a build</h2>
        <p className="text-zinc-400 text-sm m-0">
          Describe what you want built. Steve will pick the best NBT template
          and come back with a design doc for you to approve.
        </p>
        <div className="grid grid-cols-[1fr_auto] gap-2 items-stretch">
          <textarea
            value={desc}
            onChange={(e) => setDesc(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                submitPlan();
              }
            }}
            placeholder='e.g. "A small oak cabin by the river, 6x6 with a porch"'
            rows={3}
            disabled={busy || steves.length === 0 || !connected}
            className="resize-y min-h-[72px] bg-zinc-900 border border-zinc-700 rounded-md px-2.5 py-2 text-[13px] outline-none focus:border-rose-500 leading-relaxed disabled:opacity-50"
          />
          <button
            onClick={submitPlan}
            disabled={busy || !desc.trim() || steves.length === 0 || !connected}
            className="bg-rose-600 hover:bg-rose-700 disabled:bg-zinc-700 disabled:opacity-50 text-white text-xs uppercase tracking-wider px-4 py-2 rounded-md transition whitespace-nowrap"
          >
            {busy ? 'Planning…' : 'Start plan'}
          </button>
        </div>
        {steves.length === 0 && (
          <p className="text-zinc-500 text-xs mt-2">
            No Steve is in the world yet. Run <code className="text-rose-300">/steve spawn &lt;name&gt;</code> in Minecraft first.
          </p>
        )}
        {!connected && (
          <p className="text-zinc-500 text-xs mt-2">
            Not connected to the dashboard backend. Is <code className="text-rose-300">/steve dashboard</code> running in Minecraft?
          </p>
        )}
        {error && <p className="text-rose-400 text-xs mt-2">{error}</p>}
        <p className="text-zinc-500 text-[11px] mt-1.5">Ctrl/⌘ + Enter to send.</p>
      </section>

      <section className="bg-black/50 border border-zinc-800 rounded-2xl p-4 flex flex-col min-h-[320px]">
        <h2 className="m-0 mb-2 text-xs uppercase tracking-wider text-zinc-400">Chat</h2>
        <ChatPanel
          messages={messages}
          steves={steves}
          defaultSteve={defaultSteve}
          onSend={onChat}
          disabled={steves.length === 0}
          placeholder="Or just talk to a Steve…"
        />
      </section>
    </div>
  );
}

export function App() {
  const {state, sendCommand, sendChat, startPlan} = usePlanStore();
  const countdown = useCountdown(state.deadlineMs);

  const defaultSteve = useMemo(
    () => state.steveName || state.steves[0] || '',
    [state.steveName, state.steves],
  );

  const connDot = state.connected ? 'bg-emerald-500' : 'bg-rose-500';

  if (state.idle) {
    return (
      <div className="h-screen p-3 grid grid-rows-[auto_1fr] gap-2.5 box-border">
        <header className="flex items-center justify-between px-4 py-2 bg-black/50 border border-zinc-800 rounded-2xl">
          <h1 className="m-0 font-sans font-medium text-lg tracking-wider">Steve Plan Dashboard</h1>
          <span className={`w-2.5 h-2.5 rounded-full ${connDot}`} title={state.connected ? 'connected' : 'disconnected'} />
        </header>
        <main className="min-h-0">
          <LandingPanel
            messages={state.chat}
            steves={state.steves}
            defaultSteve={defaultSteve}
            onChat={sendChat}
            onStartPlan={startPlan}
            connected={state.connected}
          />
        </main>
      </div>
    );
  }

  const canApprove = state.phase === 'AWAITING_DESIGN_APPROVAL';
  const canHalt = state.phase !== 'COMPLETED' && state.phase !== 'FAILED';

  return (
    <div className="h-screen p-3 grid grid-rows-[auto_1fr] gap-2.5 box-border">
      <header className="flex items-center justify-between px-4 py-2 bg-black/50 border border-zinc-800 rounded-2xl">
        <h1 className="m-0 font-sans font-medium text-lg tracking-wider">Steve Plan Dashboard</h1>
        <span className={`w-2.5 h-2.5 rounded-full ${connDot}`} title={state.connected ? 'connected' : 'disconnected'} />
      </header>
      <div className="grid grid-cols-1 lg:grid-cols-[1fr_380px] gap-2.5 min-h-0">
        <div className="bg-black/50 border border-zinc-800 rounded-2xl overflow-hidden relative min-h-0">
          <Structure3D blocks={state.blocks} />
        </div>
        <div className="flex flex-col gap-2.5 min-h-0">
          <section className="bg-black/50 border border-zinc-800 rounded-2xl p-4">
            <h2 className="m-0 mb-2 text-xs uppercase tracking-wider text-zinc-400">Project {state.projectId}</h2>
            <p className="text-sm my-1"><span className="text-zinc-400">Steve:</span> <b>{state.steveName || '(unknown)'}</b></p>
            <p className="text-sm my-1"><span className="text-zinc-400">Command:</span> <code className="text-rose-300">{state.command}</code></p>
            <div className="flex items-center gap-2 flex-wrap">
              <span className={`inline-block px-2 py-0.5 rounded-full text-[10px] uppercase tracking-wider border ${phaseBadge(state.phase)}`}>
                {state.phase || '—'}
              </span>
              {countdown && <span className="text-zinc-400 text-xs">deadline: {countdown}</span>}
              <span className="text-zinc-400 text-xs">{state.totalBlocks} blocks</span>
            </div>
            <div className="flex items-center gap-2 mt-3">
              <button
                onClick={() => sendCommand('approve')}
                disabled={!canApprove}
                className="bg-rose-600 hover:bg-rose-700 disabled:bg-zinc-700 disabled:opacity-50 text-white text-xs uppercase tracking-wider px-4 py-2 rounded-md transition"
              >Approve</button>
              <button
                onClick={() => sendCommand('halt')}
                disabled={!canHalt}
                className="border border-white/40 hover:bg-white/10 disabled:opacity-50 text-white text-xs uppercase tracking-wider px-4 py-2 rounded-md transition"
              >Halt</button>
            </div>
          </section>

          <section className="bg-black/50 border border-zinc-800 rounded-2xl p-4">
            <h2 className="m-0 mb-2 text-xs uppercase tracking-wider text-zinc-400">Materials</h2>
            {state.materials.length === 0
              ? <p className="text-zinc-500 text-xs">(none)</p>
              : state.materials.map((m) => (
                  <div key={m.name} className="flex justify-between text-xs py-0.5">
                    <span className="flex-1">{m.name}</span>
                    <span className="w-16 text-right text-zinc-400">×{m.count}</span>
                    <span className="w-12 text-right text-zinc-400">
                      {m.percent ?? Math.round((m.count / Math.max(1, state.totalBlocks)) * 100)}%
                    </span>
                  </div>
                ))}
          </section>

          <section className="bg-black/50 border border-zinc-800 rounded-2xl p-4 flex-1 min-h-0 flex flex-col">
            <h2 className="m-0 mb-2 text-xs uppercase tracking-wider text-zinc-400">Chat</h2>
            <ChatPanel
              messages={state.chat}
              steves={state.steves}
              defaultSteve={defaultSteve}
              onSend={sendChat}
            />
          </section>

          <section className="bg-black/50 border border-zinc-800 rounded-2xl p-4 max-h-48 overflow-y-auto timeline">
            <h2 className="m-0 mb-2 text-xs uppercase tracking-wider text-zinc-400">Timeline</h2>
            {state.history.length === 0
              ? <p className="text-zinc-500 text-xs">Waiting for events…</p>
              : (Array.isArray(state.history) ? state.history : []).map((e, i) => (
                  <div
                    key={i}
                    className={`text-xs py-1 px-2 mb-1 border-l-2 ${
                      e.kind === 'phase' ? 'border-sky-500' :
                      e.kind === 'ok' ? 'border-emerald-500' :
                      e.kind === 'warn' ? 'border-amber-500' :
                      e.kind === 'err' ? 'border-rose-500' : 'border-zinc-500'
                    }`}
                  >
                    <span className="text-zinc-500 mr-1.5 text-[10px]">{e.ts}</span>
                    {e.message}
                  </div>
                ))}
          </section>
        </div>
      </div>
    </div>
  );
}

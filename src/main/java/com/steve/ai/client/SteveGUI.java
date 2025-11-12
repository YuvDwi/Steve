package com.steve.ai.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Side-mounted GUI panel for Steve agent interaction.
 * Inspired by Cursor's composer - slides in/out from the right side.
 * Now with scrollable message history!
 */
public class SteveGUI {
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_PADDING = 6;
    private static final int ANIMATION_SPEED = 20;
    private static final int MESSAGE_HEIGHT = 12;
    private static final int MAX_MESSAGES = 500;
    
    private static boolean isOpen = false;
    private static float slideOffset = PANEL_WIDTH; // Start fully hidden
    private static EditBox inputBox;
    private static List<String> commandHistory = new ArrayList<>();
    private static int historyIndex = -1;
    
    // Message history and scrolling
    private static List<ChatMessage> messages = new ArrayList<>();
    private static int scrollOffset = 0;
    private static int maxScroll = 0;
    private static final int BACKGROUND_COLOR = 0x15202020; // Ultra transparent (15 = ~8% opacity)
    private static final int BORDER_COLOR = 0x40404040; // More transparent border
    private static final int HEADER_COLOR = 0x25252525; // More transparent header (~15% opacity)
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    
    // Message bubble colors
    private static final int USER_BUBBLE_COLOR = 0xC04CAF50; // Green bubble for user
    private static final int STEVE_BUBBLE_COLOR = 0xC02196F3; // Blue bubble for Steve
    private static final int SYSTEM_BUBBLE_COLOR = 0xC0FF9800; // Orange bubble for system

    private static class ChatMessage {
        String sender; // "You", "Steve", "Alex", "System", etc.
        String text;
        int bubbleColor;
        boolean isUser; // true if message from user
        
        ChatMessage(String sender, String text, int bubbleColor, boolean isUser) {
            this.sender = sender;
            this.text = text;
            this.bubbleColor = bubbleColor;
            this.isUser = isUser;
        }
    }

    public static void toggle() {
        isOpen = !isOpen;
        
        Minecraft mc = Minecraft.getInstance();
        
        if (isOpen) {
            initializeInputBox();
            mc.setScreen(new SteveOverlayScreen());
            if (inputBox != null) {
                inputBox.setFocused(true);
            }
        } else {
            if (inputBox != null) {
                inputBox = null;
            }
            if (mc.screen instanceof SteveOverlayScreen) {
                mc.setScreen(null);
            }
        }
    }

    public static boolean isOpen() {
        return isOpen;
    }

    public static boolean isInputFocused() {
        return inputBox != null && inputBox.isFocused();
    }

    private static void initializeInputBox() {
        Minecraft mc = Minecraft.getInstance();
        if (inputBox == null) {
            inputBox = new EditBox(mc.font, 0, 0, PANEL_WIDTH - 20, 20, 
                Component.literal("Command"));
            inputBox.setMaxLength(256);
            inputBox.setHint(Component.literal("Tell Steve what to do..."));
            inputBox.setFocused(true);
        }
    }

    /**
     * Add a message to the chat history
     */
    public static void addMessage(String sender, String text, int bubbleColor, boolean isUser) {
        messages.add(new ChatMessage(sender, text, bubbleColor, isUser));
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
        // Auto-scroll to bottom on new message
        scrollOffset = 0;
    }

    /**
     * Add a user command to the history
     */
    public static void addUserMessage(String text) {
        addMessage("You", text, USER_BUBBLE_COLOR, true);
    }

    /**
     * Add a Steve response to the history
     */
    public static void addSteveMessage(String steveName, String text) {
        addMessage(steveName, text, STEVE_BUBBLE_COLOR, false);
    }

    /**
     * Add a system message to the history
     */
    public static void addSystemMessage(String text) {
        addMessage("System", text, SYSTEM_BUBBLE_COLOR, false);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay().id().toString().contains("hotbar")) {
            return; // Don't render over hotbar
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (isOpen && slideOffset > 0) {
            slideOffset = Math.max(0, slideOffset - ANIMATION_SPEED);
        } else if (!isOpen && slideOffset < PANEL_WIDTH) {
            slideOffset = Math.min(PANEL_WIDTH, slideOffset + ANIMATION_SPEED);
        }

        // Don't render if completely hidden
        if (slideOffset >= PANEL_WIDTH) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        
        int panelX = (int) (screenWidth - PANEL_WIDTH + slideOffset);
        int panelY = 0;
        int panelHeight = screenHeight;

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO
        );
        graphics.fillGradient(panelX, panelY, screenWidth, panelHeight, BACKGROUND_COLOR, BACKGROUND_COLOR);
        
        graphics.fillGradient(panelX - 2, panelY, panelX, panelHeight, BORDER_COLOR, BORDER_COLOR);

        int headerHeight = 35;
        graphics.fillGradient(panelX, panelY, screenWidth, headerHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§lSteve AI", panelX + PANEL_PADDING, panelY + 8, TEXT_COLOR);
        graphics.drawString(mc.font, "§7Press K to close", panelX + PANEL_PADDING, panelY + 20, 0xFF888888);

        // Message history area
        int inputAreaY = screenHeight - 80;
        int messageAreaTop = headerHeight + 5;
        int messageAreaHeight = inputAreaY - messageAreaTop - 5;
        int messageAreaBottom = messageAreaTop + messageAreaHeight;

        int totalMessageHeight = 0;
        for (ChatMessage msg : messages) {
            int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3);
            String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
            int bubbleHeight = MESSAGE_HEIGHT + 10; // bubble padding
            totalMessageHeight += bubbleHeight + 5 + 12; // message + spacing + name
        }
        maxScroll = Math.max(0, totalMessageHeight - messageAreaHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Render messages (scrollable)
        int yPos = messageAreaTop + 5;
        
        // Clip rendering to message area
        graphics.enableScissor(panelX, messageAreaTop, screenWidth, messageAreaBottom);
        
        if (messages.isEmpty()) {
            graphics.drawString(mc.font, "§7No messages yet...", 
                panelX + PANEL_PADDING, yPos, 0xFF666666);
            graphics.drawString(mc.font, "§7Type a command below!", 
                panelX + PANEL_PADDING, yPos + 12, 0xFF555555);
        } else {
            int currentY = messageAreaBottom - 5; // Start from bottom
            
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                
                int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3); // Leave space on sides
                String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
                int textWidth = mc.font.width(wrappedText);
                int textHeight = MESSAGE_HEIGHT;
                int bubbleWidth = Math.min(textWidth + 10, maxBubbleWidth);
                int bubbleHeight = textHeight + 10;
                
                int msgY = currentY - bubbleHeight + scrollOffset;
                
                if (msgY + bubbleHeight < messageAreaTop - 20 || msgY > messageAreaBottom + 20) {
                    currentY -= bubbleHeight + 5;
                    continue;
                }
                
                // Render message bubble based on sender
                if (msg.isUser) {
                    int bubbleX = screenWidth - bubbleWidth - PANEL_PADDING - 5;
                    
                    // Draw bubble background with gradient for alpha support
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    
                    // Draw sender name (small, above bubble)
                    graphics.drawString(mc.font, "§7" + msg.sender, bubbleX, msgY - 12, 0xFFCCCCCC);
                    
                    // Draw message text (white on colored bubble)
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                    
                } else {
                    int bubbleX = panelX + PANEL_PADDING;
                    
                    // Draw bubble background with gradient for alpha support
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    
                    // Draw sender name (small, above bubble)
                    graphics.drawString(mc.font, "§l" + msg.sender, bubbleX, msgY - 12, TEXT_COLOR);
                    
                    // Draw message text (white on colored bubble)
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                }
                
                currentY -= bubbleHeight + 5 + 12; // Extra space for sender name
            }
        }
        
        graphics.disableScissor();
        
        if (maxScroll > 0) {
            int scrollBarHeight = Math.max(20, (messageAreaHeight * messageAreaHeight) / (maxScroll + messageAreaHeight));
            int scrollBarY = messageAreaTop + (int)((messageAreaHeight - scrollBarHeight) * (1.0f - (float)scrollOffset / maxScroll));
            graphics.fill(screenWidth - 4, scrollBarY, screenWidth - 2, scrollBarY + scrollBarHeight, 0xFF888888);
        }

        // Command input area (bottom) with gradient for alpha support
        graphics.fillGradient(panelX, inputAreaY, screenWidth, screenHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§7Command:", panelX + PANEL_PADDING, inputAreaY + 10, 0xFF888888);

        if (inputBox != null && isOpen) {
            inputBox.setX(panelX + PANEL_PADDING);
            inputBox.setY(inputAreaY + 25);
            inputBox.setWidth(PANEL_WIDTH - (PANEL_PADDING * 2));
            inputBox.render(graphics, (int)mc.mouseHandler.xpos(), (int)mc.mouseHandler.ypos(), mc.getFrameTime());
        }

        graphics.drawString(mc.font, "§8Enter: Send | ↑↓: History | Scroll: Messages", 
            panelX + PANEL_PADDING, screenHeight - 15, 0xFF555555);
        
        RenderSystem.disableBlend();
    }

    /**
     * Simple word wrap for text
     */
    private static String wrapText(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        // Simple truncation for now
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            result.append(text.charAt(i));
            if (font.width(result.toString() + "...") >= maxWidth) {
                return result.substring(0, result.length() - 3) + "...";
            }
        }
        return result.toString();
    }

    public static boolean handleKeyPress(int keyCode, int scanCode, int modifiers) {
        if (!isOpen || inputBox == null) return false;

        Minecraft mc = Minecraft.getInstance();
        
        // Escape key - close panel
        if (keyCode == 256) { // ESC
            toggle();
            return true;
        }
        
        // Enter key - send command
        if (keyCode == 257) {
            String command = inputBox.getValue().trim();
            if (!command.isEmpty()) {
                sendCommand(command);
                inputBox.setValue("");
                historyIndex = -1;
            }
            return true;
        }

        // Arrow up - previous command
        if (keyCode == 265 && !commandHistory.isEmpty()) { // UP
            if (historyIndex < commandHistory.size() - 1) {
                historyIndex++;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            }
            return true;
        }

        // Arrow down - next command
        if (keyCode == 264) { // DOWN
            if (historyIndex > 0) {
                historyIndex--;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            } else if (historyIndex == 0) {
                historyIndex = -1;
                inputBox.setValue("");
            }
            return true;
        }

        // Backspace, Delete, Home, End, Left, Right - pass to input box
        if (keyCode == 259 || keyCode == 261 || keyCode == 268 || keyCode == 269 ||
            keyCode == 263 || keyCode == 262) {
            inputBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        // For normal character keys, return false so charTyped can handle them
        // This allows typing letters like 'k' without closing the GUI
        return false;
    }

    public static boolean handleCharTyped(char codePoint, int modifiers) {
        if (isOpen && inputBox != null) {
            inputBox.charTyped(codePoint, modifiers);
            return true; // Consumed
        }
        return false;
    }

    public static void handleMouseClick(double mouseX, double mouseY, int button) {
        if (!isOpen) return;

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        if (inputBox != null) {
            int inputAreaY = screenHeight - 80;
            if (mouseY >= inputAreaY + 25 && mouseY <= inputAreaY + 45) {
                inputBox.setFocused(true);
            } else {
                inputBox.setFocused(false);
            }
        }
    }

    public static void handleMouseScroll(double scrollDelta) {
        if (!isOpen) return;
        
        int scrollAmount = (int)(scrollDelta * 3 * MESSAGE_HEIGHT);
        scrollOffset -= scrollAmount;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private static void sendCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        
        commandHistory.add(command);
        if (commandHistory.size() > 50) {
            commandHistory.remove(0);
        }
        
        addUserMessage(command);

        if (command.toLowerCase().startsWith("spawn ")) {
            String name = command.substring(6).trim();
            if (name.isEmpty()) name = "Steve";
            if (mc.player != null) {
                mc.player.connection.sendCommand("steve spawn " + name);
                addSystemMessage("Spawning Steve agent: " + name);
            }
            return;
        }

        List<String> targetSteves = parseTargetSteves(command);
        
        if (targetSteves.isEmpty()) {
            var steves = SteveMod.getSteveManager().getAllSteves();
            if (!steves.isEmpty()) {
                targetSteves.add(steves.iterator().next().getSteveName());
            } else {
                // No Steves available
                addSystemMessage("No Steve agents found! Use 'spawn <name>' to create one.");
                return;
            }
        }

        // Send command to all targeted Steves
        if (mc.player != null) {
            for (String steveName : targetSteves) {
                mc.player.connection.sendCommand("steve tell " + steveName + " " + command);
            }
            
            if (targetSteves.size() > 1) {
                addSystemMessage("→ " + String.join(", ", targetSteves) + ": " + command);
            } else {
                addSystemMessage("→ " + targetSteves.get(0) + ": " + command);
            }
        }
    }
    
    private static List<String> parseTargetSteves(String command) {
        List<String> targets = new ArrayList<>();
        String commandLower = command.toLowerCase();
        
        if (commandLower.startsWith("all steves ") || commandLower.startsWith("all ") || 
            commandLower.startsWith("everyone ") || commandLower.startsWith("everybody ")) {
            var allSteves = SteveMod.getSteveManager().getAllSteves();
            for (SteveEntity steve : allSteves) {
                targets.add(steve.getSteveName());
            }
            return targets;
        }
        
        var allSteves = SteveMod.getSteveManager().getAllSteves();
        List<String> availableNames = new ArrayList<>();
        for (SteveEntity steve : allSteves) {
            availableNames.add(steve.getSteveName().toLowerCase());
        }
        
        String[] parts = command.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            String firstWord = trimmed.split(" ")[0].toLowerCase();
            
            if (availableNames.contains(firstWord)) {
                for (SteveEntity steve : allSteves) {
                    if (steve.getSteveName().equalsIgnoreCase(firstWord)) {
                        targets.add(steve.getSteveName());
                        break;
                    }
                }
            }
        }
        
        return targets;
    }

    public static void tick() {
        if (isOpen && inputBox != null) {
            inputBox.tick();
            // Auto-focus input box when panel is open
            if (!inputBox.isFocused()) {
                inputBox.setFocused(true);
            }
        }
    }
}

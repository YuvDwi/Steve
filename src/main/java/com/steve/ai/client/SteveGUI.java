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

public class SteveGUI {
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_PADDING = 6;
    private static final int ANIMATION_SPEED = 20;
    private static final int MESSAGE_HEIGHT = 12;
    private static final int MAX_MESSAGES = 500;

    private static boolean isOpen = false;
    private static float slideOffset = PANEL_WIDTH;
    private static EditBox inputBox;
    private static final List<String> commandHistory = new ArrayList<>();
    private static int historyIndex = -1;

    private static final List<ChatMessage> messages = new ArrayList<>();
    private static int scrollOffset = 0;
    private static int maxScroll = 0;

    private static final int BACKGROUND_COLOR = 0x15202020;
    private static final int BORDER_COLOR = 0x40404040;
    private static final int HEADER_COLOR = 0x25252525;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static final int USER_BUBBLE_COLOR = 0xC04CAF50;
    private static final int STEVE_BUBBLE_COLOR = 0xC02196F3;
    private static final int SYSTEM_BUBBLE_COLOR = 0xC0FF9800;

    private static class ChatMessage {
        String sender;
        String text;
        int bubbleColor;
        boolean isUser;

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
            inputBox.setFocused(true);
        } else {
            inputBox = null;
            if (mc.screen instanceof SteveOverlayScreen) {
                mc.setScreen(null);
            }
        }
    }

    private static void initializeInputBox() {
        Minecraft mc = Minecraft.getInstance();
        if (inputBox == null) {
            inputBox = new EditBox(mc.font, 0, 0, PANEL_WIDTH - 20, 20, Component.literal("Command"));
            inputBox.setMaxLength(256);
            inputBox.setHint(Component.literal("Tell Steve what to do..."));
        }
    }

    public static void addMessage(String sender, String text, int bubbleColor, boolean isUser) {
        messages.add(new ChatMessage(sender, text, bubbleColor, isUser));
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
        scrollOffset = 0;
        recalculateMaxScroll();
    }

    public static void addUserMessage(String text) {
        addMessage("You", text, USER_BUBBLE_COLOR, true);
    }

    public static void addSteveMessage(String steveName, String text) {
        addMessage(steveName, text, STEVE_BUBBLE_COLOR, false);
    }

    public static void addSystemMessage(String text) {
        addMessage("System", text, SYSTEM_BUBBLE_COLOR, false);
    }

    public static void handleMouseScroll(double scrollDelta) {
        if (!isOpen) return;

        int scrollAmount = (int) (scrollDelta * 3 * MESSAGE_HEIGHT);
        scrollOffset -= scrollAmount;

        // 🔧 Correção aplicada
        recalculateMaxScroll();
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private static void recalculateMaxScroll() {
        Minecraft mc = Minecraft.getInstance();
        int messageAreaHeight = mc.getWindow().getGuiScaledHeight() - 165;

        int totalMessageHeight = 0;
        for (ChatMessage msg : messages) {
            int bubbleHeight = MESSAGE_HEIGHT + 10;
            totalMessageHeight += bubbleHeight + 5 + 12;
        }
        maxScroll = Math.max(0, totalMessageHeight - messageAreaHeight);
    }

    private static String wrapText(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            result.append(text.charAt(i));
            if (font.width(result + "...") >= maxWidth) {
                return result.substring(0, Math.max(0, result.length() - 3)) + "...";
            }
        }
        return result.toString();
    }

    public static void tick() {
        if (isOpen && inputBox != null) {
            inputBox.tick();
            inputBox.setFocused(true);
        }
    }
}

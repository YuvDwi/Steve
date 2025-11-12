package com.steve.ai.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Invisible overlay screen that captures input for the Steve GUI
 * This prevents game controls from activating while typing
 */
public class SteveOverlayScreen extends Screen {
    
    public SteveOverlayScreen() {
        super(Component.literal("Steve AI"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Don't render anything - the SteveGUI renders via overlay
        // This screen is just to capture input
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // First, let SteveGUI handle the key (it will consume special keys like Enter, ESC, arrows)
        boolean handled = SteveGUI.handleKeyPress(keyCode, scanCode, modifiers);

        // If not handled and it's K key and input is NOT focused, toggle GUI
        // This prevents closing GUI while typing 'k' in the input box
        if (!handled && keyCode == 75 && !SteveGUI.isInputFocused() &&
            !hasShiftDown() && !hasControlDown() && !hasAltDown()) { // K
            SteveGUI.toggle();
            if (minecraft != null) {
                minecraft.setScreen(null);
            }
            return true;
        }

        return handled;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // Pass character input to SteveGUI
        return SteveGUI.handleCharTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        SteveGUI.handleMouseClick(mouseX, mouseY, button);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        SteveGUI.handleMouseScroll(scrollDelta);
        return true;
    }

    @Override
    public void removed() {
        // Clean up when screen is closed
        if (SteveGUI.isOpen()) {
            SteveGUI.toggle();
        }
    }
}


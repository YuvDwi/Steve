package com.steve.ai.client;

import com.steve.ai.SteveMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles client-side events, including disabling the narrator and checking key presses
 */
@Mod.EventBusSubscriber(modid = "steve", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEventHandler {
    
    private static boolean narratorDisabled = false;
    
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        
        if (!narratorDisabled && mc.options != null) {
            mc.options.narrator().set(NarratorStatus.OFF);
            mc.options.save();
            narratorDisabled = true;
        }
        
        // Only toggle GUI if input box is not focused (prevent closing while typing "k")
        if (KeyBindings.TOGGLE_GUI != null && KeyBindings.TOGGLE_GUI.consumeClick()) {
            if (!SteveGUI.isOpen() || mc.screen == null) {
                SteveGUI.toggle();
            }
        }
    }
}

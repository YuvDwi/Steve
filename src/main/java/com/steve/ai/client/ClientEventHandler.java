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
    
    private static boolean narratorDisabledThisSession = false;
    
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        
        // Desabilitar narrator apenas uma vez por sessão
        if (!narratorDisabledThisSession && mc.options != null) {
            try {
                if (mc.options.narrator().get() != NarratorStatus.OFF) {
                    mc.options.narrator().set(NarratorStatus.OFF);
                    mc.options.save();
                    SteveMod.LOGGER.info("Disabled narrator");
                }
                narratorDisabledThisSession = true;
            } catch (Exception e) {
                SteveMod.LOGGER.warn("Failed to disable narrator", e);
            }
        }
        
        // Verificar input para toggle da GUI
        if (KeyBindings.TOGGLE_GUI != null && KeyBindings.TOGGLE_GUI.consumeClick()) {
            SteveGUI.toggle();
        }
    }
}

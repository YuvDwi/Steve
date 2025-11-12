package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.memory.StructureRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEventHandler {
    private static boolean stevesSpawned = false;

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            SteveManager manager = SteveMod.getSteveManager();

            if (!stevesSpawned) {
                // Only clear existing Steves, don't auto-spawn new ones
                manager.clearAllSteves();

                // Clear structure registry for fresh spatial awareness
                StructureRegistry.clear();

                // Remove ALL SteveEntity instances from the world (including ones loaded from NBT)
                int removedCount = 0;
                for (var entity : level.getAllEntities()) {
                    if (entity instanceof SteveEntity) {
                        entity.discard();
                        removedCount++;
                    }
                }

                if (removedCount > 0) {
                    SteveMod.LOGGER.info("Cleaned up {} existing Steve entities", removedCount);
                }

                stevesSpawned = true;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        stevesSpawned = false;
    }
}


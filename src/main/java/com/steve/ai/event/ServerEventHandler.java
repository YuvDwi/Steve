package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.structure.StructureRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEventHandler {

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ServerLevel level = (ServerLevel) player.level();
        SteveManager manager = SteveMod.getSteveManager();

        // 如果 manager 里已有活跃的 Steve，跳过
        if (manager.getActiveCount() > 0) {
            SteveMod.LOGGER.info("Manager 已有 {} 个 Steve，跳过生成", manager.getActiveCount());
            return;
        }

        // 扫描世界中是否已有 Steve 实体（从存档恢复的）
        List<SteveEntity> existingSteves = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof SteveEntity steve) {
                existingSteves.add(steve);
            }
        }

        if (!existingSteves.isEmpty()) {
            SteveMod.LOGGER.info("从存档找到 {} 个 Steve 实体，注册到 manager", existingSteves.size());
            for (SteveEntity steve : existingSteves) {
                manager.registerExistingSteve(steve);
            }
            StructureRegistry.clear();
            return;
        }

        // 没有已有实体，生成 4 个新的
        SteveMod.LOGGER.info("没有找到 Steve 实体，生成 4 个新的");
        manager.clearAllSteves();
        StructureRegistry.clear();

        Vec3 playerPos = player.position();
        Vec3 lookVec = player.getLookAngle();
        String[] names = {"Steve", "Alex", "Bob", "Charlie"};

        for (int i = 0; i < 4; i++) {
            double offsetX = lookVec.x * 5 + (lookVec.z * (i - 1.5) * 2);
            double offsetZ = lookVec.z * 5 + (-lookVec.x * (i - 1.5) * 2);

            Vec3 spawnPos = new Vec3(
                playerPos.x + offsetX,
                playerPos.y,
                playerPos.z + offsetZ
            );

            manager.spawnSteve(level, spawnPos, names[i]);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 不重置 stevesSpawned，防止重复生成
    }
}

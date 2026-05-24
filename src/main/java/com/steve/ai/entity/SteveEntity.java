package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.SteveMemory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import com.steve.ai.memory.WarehouseManager;
import org.jetbrains.annotations.Nullable;

public class SteveEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> STEVE_NAME = 
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private SimpleContainer inventory;
    private int tickCounter = 0;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;
    private BlockPos warehousePos = null;
    private static final int RESTOCK_INTERVAL = 100; // 5 seconds

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.inventory = new SimpleContainer(36); // 36 slots like a player
        this.setCustomNameVisible(true);

        this.isInvulnerable = true;
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STEVE_NAME, "Steve");
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            actionExecutor.tick();

            tickCounter++;
            if (tickCounter % RESTOCK_INTERVAL == 0 && this.level() instanceof ServerLevel serverLevel) {
                WarehouseManager.autoRestockAll(serverLevel);
            }
        }
    }

    public void setSteveName(String name) {
        this.steveName = name;
        this.entityData.set(STEVE_NAME, name);
        this.setCustomName(Component.literal(name));
    }

    public String getSteveName() {
        return this.steveName;
    }

    public SteveMemory getMemory() {
        return this.memory;
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    public BlockPos getWarehousePos() {
        return this.warehousePos;
    }

    public void setWarehousePos(BlockPos pos) {
        this.warehousePos = pos;
    }

    public SimpleContainer getInventory() {
        return this.inventory;
    }

    /**
     * Add items to inventory. Returns the remainder that didn't fit.
     */
    public ItemStack addItemToInventory(ItemStack stack) {
        return this.inventory.addItem(stack);
    }

    /**
     * Check if inventory contains at least 'count' items of the given block type.
     */
    public boolean hasBlock(Block block, int count) {
        ItemStack target = new ItemStack(block.asItem());
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (ItemStack.isSameItemSameTags(slot, target)) {
                total += slot.getCount();
                if (total >= count) return true;
            }
        }
        return false;
    }

    /**
     * Get total count of a specific block item in inventory.
     */
    public int getBlockCount(Block block) {
        ItemStack target = new ItemStack(block.asItem());
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (ItemStack.isSameItemSameTags(slot, target)) {
                total += slot.getCount();
            }
        }
        return total;
    }

    /**
     * Remove 'count' items of the given block from inventory. Returns true if successful.
     */
    public boolean removeBlockFromInventory(Block block, int count) {
        if (!hasBlock(block, count)) return false;

        ItemStack target = new ItemStack(block.asItem());
        int remaining = count;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = inventory.getItem(i);
            if (ItemStack.isSameItemSameTags(slot, target)) {
                int take = Math.min(remaining, slot.getCount());
                slot.shrink(take);
                remaining -= take;
            }
        }
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("SteveName", this.steveName);

        CompoundTag memoryTag = new CompoundTag();
        this.memory.saveToNBT(memoryTag);
        tag.put("Memory", memoryTag);

        ListTag inventoryTag = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                stack.save(slotTag);
                inventoryTag.add(slotTag);
            }
        }
        tag.put("Inventory", inventoryTag);

        if (warehousePos != null) {
            tag.putLong("WarehousePos", warehousePos.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SteveName")) {
            this.setSteveName(tag.getString("SteveName"));
        }

        if (tag.contains("Memory")) {
            this.memory.loadFromNBT(tag.getCompound("Memory"));
        }

        if (tag.contains("Inventory")) {
            ListTag inventoryTag = tag.getList("Inventory", 10);
            this.inventory = new SimpleContainer(36);
            for (int i = 0; i < inventoryTag.size(); i++) {
                CompoundTag slotTag = inventoryTag.getCompound(i);
                int slot = slotTag.getByte("Slot") & 255;
                if (slot < inventory.getContainerSize()) {
                    inventory.setItem(slot, ItemStack.of(slotTag));
                }
            }
        }

        if (tag.contains("WarehousePos")) {
            this.warehousePos = BlockPos.of(tag.getLong("WarehousePos"));
        }
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                       MobSpawnType spawnType, @Nullable SpawnGroupData spawnData,
                                       @Nullable CompoundTag tag) {
        spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag);
        return spawnData;
    }

    public void sendChatMessage(String message) {
        if (this.level().isClientSide) return;
        
        Component chatComponent = Component.literal("<" + this.steveName + "> " + message);
        this.level().players().forEach(player -> player.sendSystemMessage(chatComponent));
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.world.damagesource.DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        if (inventory != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    this.spawnAtLocation(stack);
                }
            }
            inventory.clearContent();
        }
    }

    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.setNoGravity(flying);
        this.setInvulnerableBuilding(flying);
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    /**
     * Set invulnerability for building (immune to ALL damage: fire, lava, suffocation, fall, etc.)
     */
    public void setInvulnerableBuilding(boolean invulnerable) {
        this.isInvulnerable = invulnerable;
        this.setInvulnerable(invulnerable); // Minecraft's built-in invulnerability
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return true;
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (this.isFlying && !this.level().isClientSide) {
            double motionY = this.getDeltaMovement().y;
            
            if (this.getNavigation().isInProgress()) {
                super.travel(travelVector);
                
                // But add ability to move vertically freely
                if (Math.abs(motionY) < 0.1) {
                    // Small upward force to prevent falling
                    this.setDeltaMovement(this.getDeltaMovement().add(0, 0.05, 0));
                }
            } else {
                super.travel(travelVector);
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, net.minecraft.world.damagesource.DamageSource source) {
        // No fall damage when flying
        if (this.isFlying) {
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }
}


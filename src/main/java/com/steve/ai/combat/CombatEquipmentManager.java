package com.steve.ai.combat;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraftforge.items.ItemStackHandler;

import java.util.*;

/**
 * Manages combat equipment for Steve entities
 * Handles automatic armor equipping, weapon selection, and shield usage
 */
public class CombatEquipmentManager {
    private final SteveEntity steve;

    // Equipment tier rankings (higher = better)
    private static final Map<String, Integer> ARMOR_TIERS = new HashMap<>() {{
        put("leather", 1);
        put("chainmail", 2);
        put("golden", 2);
        put("iron", 3);
        put("diamond", 4);
        put("netherite", 5);
    }};

    private static final Map<String, Integer> WEAPON_TIERS = new HashMap<>() {{
        put("wooden", 1);
        put("stone", 2);
        put("golden", 2);
        put("iron", 3);
        put("diamond", 4);
        put("netherite", 5);
    }};

    public CombatEquipmentManager(SteveEntity steve) {
        this.steve = steve;
    }

    /**
     * Equip best available armor from inventory
     * @return true if armor was equipped
     */
    public boolean equipBestArmor() {
        boolean equipped = false;

        equipped |= equipBestArmorPiece(EquipmentSlot.HEAD);
        equipped |= equipBestArmorPiece(EquipmentSlot.CHEST);
        equipped |= equipBestArmorPiece(EquipmentSlot.LEGS);
        equipped |= equipBestArmorPiece(EquipmentSlot.FEET);

        if (equipped) {
            SteveMod.LOGGER.info("Steve '{}' equipped armor", steve.getSteveName());
        }

        return equipped;
    }

    /**
     * Equip best armor piece for a specific slot
     */
    private boolean equipBestArmorPiece(EquipmentSlot slot) {
        ItemStackHandler inventory = steve.getInventory();
        ItemStack currentArmor = steve.getItemBySlot(slot);
        ItemStack bestArmor = currentArmor.copy();
        int bestTier = getArmorTier(currentArmor);

        // Search inventory for better armor
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);

            if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armorItem)) {
                continue;
            }

            // Check if armor matches this slot
            if (armorItem.getEquipmentSlot() != slot) {
                continue;
            }

            int tier = getArmorTier(stack);
            if (tier > bestTier) {
                bestArmor = stack.copy();
                bestTier = tier;
            }
        }

        // Equip if found better armor
        if (bestTier > getArmorTier(currentArmor)) {
            steve.setItemSlot(slot, bestArmor);
            // Remove from inventory
            for (int i = 0; i < inventory.getSlots(); i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (ItemStack.matches(stack, bestArmor)) {
                    inventory.extractItem(i, 1, false);
                    break;
                }
            }
            return true;
        }

        return false;
    }

    /**
     * Get armor tier from item stack
     */
    private int getArmorTier(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) {
            return 0;
        }

        String itemName = stack.getItem().toString().toLowerCase();

        for (Map.Entry<String, Integer> entry : ARMOR_TIERS.entrySet()) {
            if (itemName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return 0;
    }

    /**
     * Equip best available weapon from inventory
     * @return true if weapon was equipped
     */
    public boolean equipBestWeapon() {
        ItemStackHandler inventory = steve.getInventory();
        ItemStack currentWeapon = steve.getMainHandItem();
        ItemStack bestWeapon = currentWeapon.copy();
        int bestTier = getWeaponTier(currentWeapon);

        // Search inventory for better weapon
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);

            if (stack.isEmpty()) {
                continue;
            }

            Item item = stack.getItem();
            if (!(item instanceof SwordItem) && !(item instanceof AxeItem)) {
                continue;
            }

            int tier = getWeaponTier(stack);
            if (tier > bestTier) {
                bestWeapon = stack.copy();
                bestTier = tier;
            }
        }

        // Equip if found better weapon
        if (bestTier > getWeaponTier(currentWeapon)) {
            steve.setItemSlot(EquipmentSlot.MAINHAND, bestWeapon);
            SteveMod.LOGGER.info("Steve '{}' equipped {} weapon",
                steve.getSteveName(), bestWeapon.getItem().toString());
            return true;
        }

        return false;
    }

    /**
     * Get weapon tier from item stack
     */
    private int getWeaponTier(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        Item item = stack.getItem();
        if (!(item instanceof SwordItem) && !(item instanceof AxeItem)) {
            return 0;
        }

        String itemName = item.toString().toLowerCase();

        for (Map.Entry<String, Integer> entry : WEAPON_TIERS.entrySet()) {
            if (itemName.contains(entry.getKey())) {
                // Swords are slightly better than axes
                int tierBonus = item instanceof SwordItem ? 1 : 0;
                return entry.getValue() + tierBonus;
            }
        }

        return 1; // Default tier
    }

    /**
     * Equip shield from inventory
     * @return true if shield was equipped
     */
    public boolean equipShield() {
        ItemStackHandler inventory = steve.getInventory();
        ItemStack offHand = steve.getOffhandItem();

        // Already have shield equipped
        if (offHand.getItem() instanceof ShieldItem) {
            return false;
        }

        // Search for shield in inventory
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);

            if (!stack.isEmpty() && stack.getItem() instanceof ShieldItem) {
                steve.setItemSlot(EquipmentSlot.OFFHAND, stack.copy());
                inventory.extractItem(i, 1, false);
                SteveMod.LOGGER.info("Steve '{}' equipped shield", steve.getSteveName());
                return true;
            }
        }

        return false;
    }

    /**
     * Equip bow and arrows from inventory
     * @return true if bow and arrows were equipped
     */
    public boolean equipBowAndArrows() {
        ItemStackHandler inventory = steve.getInventory();

        // Check if we have both bow and arrows
        boolean hasBow = false;
        boolean hasArrows = false;

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof BowItem) {
                hasBow = true;
            }
            if (stack.getItem() instanceof ArrowItem) {
                hasArrows = true;
            }
        }

        if (!hasBow || !hasArrows) {
            return false;
        }

        // Equip bow
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BowItem) {
                steve.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                SteveMod.LOGGER.info("Steve '{}' equipped bow", steve.getSteveName());
                return true;
            }
        }

        return false;
    }

    /**
     * Check if Steve has arrows in inventory
     */
    public boolean hasArrows() {
        ItemStackHandler inventory = steve.getInventory();

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof ArrowItem) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if Steve has a shield equipped
     */
    public boolean hasShieldEquipped() {
        return steve.getOffhandItem().getItem() instanceof ShieldItem;
    }

    /**
     * Check if Steve has a bow equipped
     */
    public boolean hasBowEquipped() {
        return steve.getMainHandItem().getItem() instanceof BowItem;
    }

    /**
     * Auto-equip best combat gear
     * @return summary of equipped items
     */
    public String autoEquipCombatGear() {
        StringBuilder summary = new StringBuilder();

        if (equipBestArmor()) {
            summary.append("Equipped armor. ");
        }

        if (equipBestWeapon()) {
            summary.append("Equipped weapon. ");
        }

        if (equipShield()) {
            summary.append("Equipped shield. ");
        }

        if (summary.length() == 0) {
            return "Already equipped with best available gear.";
        }

        return summary.toString().trim();
    }

    /**
     * Get combat readiness score (0-100)
     */
    public int getCombatReadiness() {
        int score = 0;

        // Weapon (30 points)
        int weaponTier = getWeaponTier(steve.getMainHandItem());
        score += Math.min(30, weaponTier * 6);

        // Armor (40 points total, 10 per piece)
        score += Math.min(10, getArmorTier(steve.getItemBySlot(EquipmentSlot.HEAD)) * 2);
        score += Math.min(10, getArmorTier(steve.getItemBySlot(EquipmentSlot.CHEST)) * 2);
        score += Math.min(10, getArmorTier(steve.getItemBySlot(EquipmentSlot.LEGS)) * 2);
        score += Math.min(10, getArmorTier(steve.getItemBySlot(EquipmentSlot.FEET)) * 2);

        // Shield (15 points)
        if (hasShieldEquipped()) {
            score += 15;
        }

        // Bow and arrows (15 points)
        if (hasBowEquipped() && hasArrows()) {
            score += 15;
        }

        return Math.min(100, score);
    }

    /**
     * Get equipment summary
     */
    public String getEquipmentSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Combat Readiness: ").append(getCombatReadiness()).append("%\n");

        ItemStack weapon = steve.getMainHandItem();
        if (!weapon.isEmpty()) {
            sb.append("Weapon: ").append(weapon.getItem().toString()).append("\n");
        }

        ItemStack shield = steve.getOffhandItem();
        if (!shield.isEmpty()) {
            sb.append("Shield: ").append(shield.getItem().toString()).append("\n");
        }

        sb.append("Armor: ");
        int armorPieces = 0;
        if (!steve.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) armorPieces++;
        if (!steve.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) armorPieces++;
        if (!steve.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) armorPieces++;
        if (!steve.getItemBySlot(EquipmentSlot.FEET).isEmpty()) armorPieces++;
        sb.append(armorPieces).append("/4 pieces");

        return sb.toString();
    }
}

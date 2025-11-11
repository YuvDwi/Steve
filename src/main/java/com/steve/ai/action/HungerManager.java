package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * Manages Steve's hunger automatically
 * Monitors food level and automatically eats when hungry
 */
public class HungerManager {
    private final SteveEntity steve;
    private int ticksSinceLastCheck = 0;
    private static final int CHECK_INTERVAL = 100; // Check every 5 seconds
    private static final int HUNGER_THRESHOLD = 14; // Eat when hunger < 14 (out of 20)

    // Food items ranked by nutrition value
    private static final Map<Item, Integer> FOOD_VALUES = new LinkedHashMap<>() {{
        // Best foods first
        put(Items.GOLDEN_APPLE, 4);
        put(Items.ENCHANTED_GOLDEN_APPLE, 4);
        put(Items.COOKED_BEEF, 8);
        put(Items.COOKED_PORKCHOP, 8);
        put(Items.COOKED_MUTTON, 6);
        put(Items.COOKED_CHICKEN, 6);
        put(Items.COOKED_RABBIT, 5);
        put(Items.COOKED_COD, 5);
        put(Items.COOKED_SALMON, 6);
        put(Items.BAKED_POTATO, 5);
        put(Items.BREAD, 5);
        put(Items.GOLDEN_CARROT, 6);
        put(Items.PUMPKIN_PIE, 8);
        put(Items.CAKE, 2); // Per slice
        put(Items.COOKIE, 2);
        // Raw foods (emergency)
        put(Items.BEEF, 3);
        put(Items.PORKCHOP, 3);
        put(Items.MUTTON, 2);
        put(Items.CHICKEN, 2);
        put(Items.RABBIT, 3);
        put(Items.COD, 2);
        put(Items.SALMON, 2);
        put(Items.CARROT, 3);
        put(Items.POTATO, 1);
        put(Items.APPLE, 4);
        put(Items.MELON_SLICE, 2);
        put(Items.SWEET_BERRIES, 2);
        put(Items.GLOW_BERRIES, 2);
    }};

    public HungerManager(SteveEntity steve) {
        this.steve = steve;
    }

    /**
     * Tick the hunger manager
     * Called from Steve's tick method
     */
    public void tick() {
        ticksSinceLastCheck++;

        if (ticksSinceLastCheck >= CHECK_INTERVAL) {
            ticksSinceLastCheck = 0;
            checkAndEat();
        }
    }

    /**
     * Check hunger level and eat if needed
     */
    private void checkAndEat() {
        FoodData foodData = steve.getFoodData();
        int foodLevel = foodData.getFoodLevel();

        if (foodLevel < HUNGER_THRESHOLD) {
            SteveMod.LOGGER.debug("Steve '{}' is hungry (food level: {})",
                steve.getSteveName(), foodLevel);

            boolean ate = eatFood();

            if (ate) {
                SteveMod.LOGGER.info("Steve '{}' ate food (food level was: {}, now: {})",
                    steve.getSteveName(), foodLevel, steve.getFoodData().getFoodLevel());
            } else {
                SteveMod.LOGGER.warn("Steve '{}' is hungry but has no food!",
                    steve.getSteveName());
            }
        }
    }

    /**
     * Eat the best available food from inventory
     * @return true if food was eaten
     */
    private boolean eatFood() {
        // Find best food in inventory
        Item bestFood = findBestFood();

        if (bestFood == null) {
            return false;
        }

        // Create food stack
        ItemStack foodStack = new ItemStack(bestFood);

        // Equip food
        steve.setItemInHand(InteractionHand.MAIN_HAND, foodStack);

        // Eat food (simulate consumption)
        steve.getFoodData().eat(bestFood, foodStack);

        // Remove from inventory
        InventoryHelper.removeItem(steve, bestFood, 1);

        // Swing arm for animation
        steve.swing(InteractionHand.MAIN_HAND, true);

        return true;
    }

    /**
     * Find the best food item in inventory
     * Prioritizes cooked/high-nutrition foods
     */
    private Item findBestFood() {
        for (Map.Entry<Item, Integer> entry : FOOD_VALUES.entrySet()) {
            Item food = entry.getKey();
            if (InventoryHelper.hasItem(steve, food, 1)) {
                return food;
            }
        }
        return null;
    }

    /**
     * Get current hunger level (0-20)
     */
    public int getHungerLevel() {
        return steve.getFoodData().getFoodLevel();
    }

    /**
     * Check if Steve is hungry
     */
    public boolean isHungry() {
        return steve.getFoodData().getFoodLevel() < HUNGER_THRESHOLD;
    }

    /**
     * Check if Steve is starving (very low hunger)
     */
    public boolean isStarving() {
        return steve.getFoodData().getFoodLevel() < 6;
    }

    /**
     * Get saturation level
     */
    public float getSaturation() {
        return steve.getFoodData().getSaturationLevel();
    }

    /**
     * Force Steve to eat if food is available
     * @return true if food was eaten
     */
    public boolean forceEat() {
        return eatFood();
    }

    /**
     * Check if Steve has any food in inventory
     */
    public boolean hasFood() {
        return findBestFood() != null;
    }

    /**
     * Get hunger status as string
     */
    public String getHungerStatus() {
        int hunger = getHungerLevel();
        float saturation = getSaturation();

        if (hunger >= 18) {
            return "Full (hunger: " + hunger + "/20, saturation: " + String.format("%.1f", saturation) + ")";
        } else if (hunger >= HUNGER_THRESHOLD) {
            return "Satisfied (hunger: " + hunger + "/20)";
        } else if (hunger >= 10) {
            return "Hungry (hunger: " + hunger + "/20)";
        } else if (hunger >= 6) {
            return "Very Hungry (hunger: " + hunger + "/20)";
        } else {
            return "STARVING (hunger: " + hunger + "/20)";
        }
    }

    /**
     * Count total food items in inventory
     */
    public int countFoodItems() {
        int count = 0;
        for (Item food : FOOD_VALUES.keySet()) {
            count += InventoryHelper.getItemCount(steve, food);
        }
        return count;
    }
}

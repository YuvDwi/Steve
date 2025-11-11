package com.steve.ai.util;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Recipe lookup and crafting support utility
 * Integrates with Minecraft's recipe system to provide:
 * - Recipe availability checking
 * - Ingredient requirement extraction
 * - Crafting table requirement detection
 * - Recipe type identification
 */
public class RecipeHelper {

    /**
     * Check if a crafting recipe exists for a given item
     * @param level The server level (needed for RecipeManager)
     * @param item The item to check
     * @return true if at least one recipe produces this item
     */
    public static boolean hasRecipe(ServerLevel level, Item item) {
        RecipeManager recipeManager = level.getRecipeManager();

        // Get all crafting recipes
        Collection<RecipeHolder<CraftingRecipe>> recipes =
                recipeManager.getAllRecipesFor(RecipeType.CRAFTING);

        // Check if any recipe produces the target item
        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(level.registryAccess());

            if (!result.isEmpty() && result.getItem() == item) {
                return true;
            }
        }

        return false;
    }

    /**
     * Find a crafting recipe for a given item
     * @param level The server level
     * @param item The target item
     * @return RecipeHolder containing the recipe, or null if not found
     */
    @Nullable
    public static RecipeHolder<CraftingRecipe> findRecipe(ServerLevel level, Item item) {
        RecipeManager recipeManager = level.getRecipeManager();
        Collection<RecipeHolder<CraftingRecipe>> recipes =
                recipeManager.getAllRecipesFor(RecipeType.CRAFTING);

        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(level.registryAccess());

            if (!result.isEmpty() && result.getItem() == item) {
                return holder;
            }
        }

        return null;
    }

    /**
     * Get required ingredients for crafting an item
     * Returns a map of Item -> required count
     * @param level The server level
     * @param item The item to craft
     * @return Map of ingredients, empty if no recipe found
     */
    public static Map<Item, Integer> getRequiredIngredients(ServerLevel level, Item item) {
        RecipeHolder<CraftingRecipe> holder = findRecipe(level, item);
        if (holder == null) {
            return Map.of();
        }

        CraftingRecipe recipe = holder.value();
        Map<Item, Integer> ingredients = new HashMap<>();

        // Extract ingredients from the recipe
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;

            // Get the first matching item stack (recipes can have alternatives)
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length > 0) {
                ItemStack stack = stacks[0];
                Item ingredientItem = stack.getItem();

                // Accumulate count
                ingredients.put(ingredientItem, ingredients.getOrDefault(ingredientItem, 0) + stack.getCount());
            }
        }

        return ingredients;
    }

    /**
     * Check if a recipe requires a crafting table (3x3 grid)
     * Returns false for 2x2 recipes that can be done in player inventory
     * @param level The server level
     * @param item The item to check
     * @return true if crafting table required
     */
    public static boolean requiresCraftingTable(ServerLevel level, Item item) {
        RecipeHolder<CraftingRecipe> holder = findRecipe(level, item);
        if (holder == null) {
            return false; // No recipe = no crafting table needed
        }

        CraftingRecipe recipe = holder.value();

        // Check if recipe is a ShapedRecipe (has dimensions)
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            int width = shapedRecipe.getWidth();
            int height = shapedRecipe.getHeight();

            // If width or height > 2, requires 3x3 crafting table
            return width > 2 || height > 2;
        }

        // Shapeless recipes and special recipes
        // Count total number of ingredients
        List<Ingredient> ingredients = recipe.getIngredients();
        int totalIngredients = 0;

        for (Ingredient ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                totalIngredients++;
            }
        }

        // If more than 4 ingredients, requires 3x3 grid
        return totalIngredients > 4;
    }

    /**
     * Get all items required for a crafting chain
     * For example, to craft a diamond pickaxe, you need diamonds AND sticks
     * This recursively finds all base materials
     * @param level The server level
     * @param item The final item to craft
     * @param depth Maximum recursion depth (prevents infinite loops)
     * @return Map of all required base materials
     */
    public static Map<Item, Integer> getFullCraftingChain(ServerLevel level, Item item, int depth) {
        if (depth <= 0) {
            return Map.of();
        }

        Map<Item, Integer> totalRequirements = new HashMap<>();
        Map<Item, Integer> directIngredients = getRequiredIngredients(level, item);

        if (directIngredients.isEmpty()) {
            // No recipe - this is a base material
            totalRequirements.put(item, 1);
            return totalRequirements;
        }

        // For each ingredient, check if it also has a recipe
        for (Map.Entry<Item, Integer> entry : directIngredients.entrySet()) {
            Item ingredient = entry.getKey();
            int count = entry.getValue();

            if (hasRecipe(level, ingredient) && depth > 1) {
                // Ingredient can be crafted - recurse
                Map<Item, Integer> subRequirements = getFullCraftingChain(level, ingredient, depth - 1);
                for (Map.Entry<Item, Integer> sub : subRequirements.entrySet()) {
                    totalRequirements.put(
                        sub.getKey(),
                        totalRequirements.getOrDefault(sub.getKey(), 0) + (sub.getValue() * count)
                    );
                }
            } else {
                // Base material
                totalRequirements.put(
                    ingredient,
                    totalRequirements.getOrDefault(ingredient, 0) + count
                );
            }
        }

        return totalRequirements;
    }

    /**
     * Get the result count for a recipe
     * Some recipes produce multiple items (e.g., 1 iron block -> 9 iron ingots)
     * @param level The server level
     * @param item The item to check
     * @return Number of items produced, or 0 if no recipe
     */
    public static int getRecipeResultCount(ServerLevel level, Item item) {
        RecipeHolder<CraftingRecipe> holder = findRecipe(level, item);
        if (holder == null) {
            return 0;
        }

        CraftingRecipe recipe = holder.value();
        ItemStack result = recipe.getResultItem(level.registryAccess());
        return result.getCount();
    }

    /**
     * Check if Steve has all required ingredients for a recipe
     * @param level The server level
     * @param steve The Steve entity (via InventoryHelper)
     * @param item The item to craft
     * @return true if all ingredients available
     */
    public static boolean hasAllIngredients(ServerLevel level, com.steve.ai.entity.SteveEntity steve, Item item) {
        Map<Item, Integer> required = getRequiredIngredients(level, item);

        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            if (!InventoryHelper.hasItem(steve, entry.getKey(), entry.getValue())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get missing ingredients for a recipe
     * Returns a map of Item -> count still needed
     * @param level The server level
     * @param steve The Steve entity
     * @param item The item to craft
     * @return Map of missing ingredients
     */
    public static Map<Item, Integer> getMissingIngredients(
        ServerLevel level,
        com.steve.ai.entity.SteveEntity steve,
        Item item
    ) {
        Map<Item, Integer> required = getRequiredIngredients(level, item);
        Map<Item, Integer> missing = new HashMap<>();

        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            Item ingredient = entry.getKey();
            int needed = entry.getValue();
            int have = InventoryHelper.getItemCount(steve, ingredient);

            if (have < needed) {
                missing.put(ingredient, needed - have);
            }
        }

        return missing;
    }

    /**
     * Check if a recipe is a shaped recipe (has specific pattern)
     * @param level The server level
     * @param item The item to check
     * @return true if recipe is shaped
     */
    public static boolean isShapedRecipe(ServerLevel level, Item item) {
        RecipeHolder<CraftingRecipe> holder = findRecipe(level, item);
        if (holder == null) {
            return false;
        }

        return holder.value() instanceof ShapedRecipe;
    }

    /**
     * Get recipe dimensions for display/debugging
     * @param level The server level
     * @param item The item to check
     * @return String like "3x3" or "shapeless"
     */
    public static String getRecipeDimensions(ServerLevel level, Item item) {
        RecipeHolder<CraftingRecipe> holder = findRecipe(level, item);
        if (holder == null) {
            return "none";
        }

        CraftingRecipe recipe = holder.value();
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            return shapedRecipe.getWidth() + "x" + shapedRecipe.getHeight();
        }

        return "shapeless";
    }
}


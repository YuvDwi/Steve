package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * Breeding action for animal husbandry
 * Supports cows, pigs, chickens, sheep, and other farm animals
 */
public class BreedingAction extends BaseAction {
    private String animalType;
    private int targetBreedings;
    private int breedingsDone;
    private int searchRadius = 16;
    private int ticksRunning;
    private static final int MAX_TICKS = 6000; // 5 minutes
    private static final int TICK_DELAY = 60; // 3 seconds between breeding attempts
    private int ticksSinceLastAction = 0;

    // Animal breeding food mappings
    private static final Map<String, List<Item>> BREEDING_FOODS = new HashMap<>() {{
        put("cow", Arrays.asList(Items.WHEAT));
        put("pig", Arrays.asList(Items.CARROT, Items.POTATO, Items.BEETROOT));
        put("chicken", Arrays.asList(Items.WHEAT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.BEETROOT_SEEDS));
        put("sheep", Arrays.asList(Items.WHEAT));
        put("horse", Arrays.asList(Items.GOLDEN_APPLE, Items.GOLDEN_CARROT));
        put("llama", Arrays.asList(Items.HAY_BLOCK));
        put("rabbit", Arrays.asList(Items.CARROT, Items.GOLDEN_CARROT, Items.DANDELION));
        put("turtle", Arrays.asList(Items.SEAGRASS));
        put("fox", Arrays.asList(Items.SWEET_BERRIES, Items.GLOW_BERRIES));
        put("goat", Arrays.asList(Items.WHEAT));
    }};

    public BreedingAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        animalType = task.getStringParameter("animal", "cow").toLowerCase();
        targetBreedings = task.getIntParameter("amount", 5);
        breedingsDone = 0;
        ticksRunning = 0;
        ticksSinceLastAction = 0;

        if (!BREEDING_FOODS.containsKey(animalType)) {
            result = ActionResult.failure("Unknown animal type: " + animalType);
            return;
        }

        // Check if we have breeding food
        List<Item> foods = BREEDING_FOODS.get(animalType);
        boolean hasFood = false;
        for (Item food : foods) {
            if (InventoryHelper.hasItem(steve, food, 2)) { // Need at least 2 items to breed
                hasFood = true;
                break;
            }
        }

        if (!hasFood) {
            result = ActionResult.failure("No breeding food for " + animalType + " in inventory");
            return;
        }

        SteveMod.LOGGER.info("Steve '{}' starting breeding action for {} (target: {})",
            steve.getSteveName(), animalType, targetBreedings);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastAction++;

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Breeding timeout - bred " + breedingsDone + " animals");
            return;
        }

        if (ticksSinceLastAction < TICK_DELAY) {
            return; // Wait between breeding attempts
        }

        boolean didBreeding = attemptBreeding();

        if (didBreeding) {
            breedingsDone++;
            ticksSinceLastAction = 0;

            if (breedingsDone >= targetBreedings) {
                result = ActionResult.success("Successfully bred " + breedingsDone + " " + animalType + "(s)");
                return;
            }
        } else {
            // Check if we've done enough or can't find more animals
            if (breedingsDone > 0 && ticksSinceLastAction > TICK_DELAY * 3) {
                result = ActionResult.success("Bred " + breedingsDone + " " + animalType + "(s) (no more pairs found)");
            } else if (breedingsDone == 0 && ticksSinceLastAction > TICK_DELAY * 5) {
                result = ActionResult.failure("No breedable " + animalType + " pairs found nearby");
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return "Breed " + animalType + " (" + breedingsDone + "/" + targetBreedings + ")";
    }

    /**
     * Attempt to breed a pair of animals
     */
    private boolean attemptBreeding() {
        // Find two animals that can be bred
        List<Animal> breedableAnimals = findBreedableAnimals();

        if (breedableAnimals.size() < 2) {
            SteveMod.LOGGER.debug("Steve '{}' found only {} breedable {}(s)",
                steve.getSteveName(), breedableAnimals.size(), animalType);
            return false;
        }

        // Get two animals
        Animal animal1 = breedableAnimals.get(0);
        Animal animal2 = breedableAnimals.get(1);

        // Get breeding food
        List<Item> foods = BREEDING_FOODS.get(animalType);
        Item breedingFood = null;

        for (Item food : foods) {
            if (InventoryHelper.hasItem(steve, food, 2)) {
                breedingFood = food;
                break;
            }
        }

        if (breedingFood == null) {
            SteveMod.LOGGER.warn("Steve '{}' ran out of breeding food for {}",
                steve.getSteveName(), animalType);
            return false;
        }

        // Move to first animal
        steve.teleportTo(animal1.getX(), animal1.getY(), animal1.getZ());

        // Feed first animal
        ItemStack foodStack = new ItemStack(breedingFood);
        steve.setItemInHand(InteractionHand.MAIN_HAND, foodStack);
        animal1.setInLove(steve);
        InventoryHelper.removeItem(steve, breedingFood, 1);

        steve.swing(InteractionHand.MAIN_HAND, true);

        // Move to second animal
        steve.teleportTo(animal2.getX(), animal2.getY(), animal2.getZ());

        // Feed second animal
        animal2.setInLove(steve);
        InventoryHelper.removeItem(steve, breedingFood, 1);

        steve.swing(InteractionHand.MAIN_HAND, true);

        SteveMod.LOGGER.info("Steve '{}' bred two {}(s) at ({}, {}, {})",
            steve.getSteveName(), animalType,
            (int)animal1.getX(), (int)animal1.getY(), (int)animal1.getZ());

        return true;
    }

    /**
     * Find animals that can be bred
     */
    private List<Animal> findBreedableAnimals() {
        List<Animal> breedableAnimals = new ArrayList<>();
        List<? extends Entity> nearbyEntities = steve.level().getEntities(
            steve,
            steve.getBoundingBox().inflate(searchRadius)
        );

        for (Entity entity : nearbyEntities) {
            if (!isMatchingAnimalType(entity)) {
                continue;
            }

            if (entity instanceof Animal animal) {
                // Check if animal can breed (not in love, not baby, not on cooldown)
                if (animal.canFallInLove() && animal.getAge() == 0) {
                    breedableAnimals.add(animal);
                }
            }
        }

        // Sort by distance
        breedableAnimals.sort(Comparator.comparingDouble(animal ->
            animal.distanceToSqr(steve.getX(), steve.getY(), steve.getZ())
        ));

        return breedableAnimals;
    }

    /**
     * Check if entity matches the target animal type
     */
    private boolean isMatchingAnimalType(Entity entity) {
        return switch (animalType) {
            case "cow" -> entity instanceof Cow && !(entity instanceof MushroomCow);
            case "mooshroom" -> entity instanceof MushroomCow;
            case "pig" -> entity instanceof Pig;
            case "chicken" -> entity instanceof Chicken;
            case "sheep" -> entity instanceof Sheep;
            case "horse" -> entity instanceof Horse;
            case "llama" -> entity instanceof Llama;
            case "rabbit" -> entity instanceof Rabbit;
            case "turtle" -> entity instanceof Turtle;
            case "fox" -> entity instanceof Fox;
            case "goat" -> entity instanceof Goat;
            default -> false;
        };
    }
}

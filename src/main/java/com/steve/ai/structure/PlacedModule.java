package com.steve.ai.structure;

import net.minecraft.core.BlockPos;

/**
 * A {@link LoadedTemplate} paired with its resolved world placement.
 *
 * <p>This is the runtime representation produced by the module-composition
 * pipeline in {@code PlanBuildAction.runDesign()}. All three downstream
 * consumers (CONSTRUCTION block placement, dashboard 3D snapshot, the
 * design-ready event payload) iterate the project's {@code placedModules}
 * list and compute world coordinates through
 * {@link ModuleTransform#apply(BlockPos, BlockPos, Facing)} — so the 3D
 * preview and the placed world cannot diverge.
 */
public final class PlacedModule {

    /**
     * 90° cardinal rotation around the Y axis applied to this module's
     * local coordinate frame. The default for newly-authored NBT files is
     * {@link #S} (matching vanilla {@code StructureTemplate}'s +Z default).
     */
    public enum Facing {
        /** −Z (north) */
        N,
        /** +X (east) */
        E,
        /** +Z (south) — default, matches vanilla */
        S,
        /** −X (west) */
        W
    }

    public final StructureTemplateLoader.LoadedTemplate template;
    public final BlockPos worldOrigin;
    public final Facing facing;

    public PlacedModule(StructureTemplateLoader.LoadedTemplate template, BlockPos worldOrigin, Facing facing) {
        this.template = template;
        this.worldOrigin = worldOrigin;
        this.facing = facing;
    }

    /** World position of this module's exit anchor (its downstream attachment point). */
    public BlockPos worldExit() {
        return ModuleTransform.apply(
            ModuleTransform.exitAnchor(template, facing), worldOrigin, facing);
    }
}

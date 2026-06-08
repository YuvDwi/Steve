package com.steve.ai.structure;

import net.minecraft.core.BlockPos;

/**
 * The single rotation/transform source for the module-composition system.
 *
 * <p>Every consumer of a {@link PlacedModule} — CONSTRUCTION block placement,
 * the dashboard 3D snapshot, the design-ready event payload — computes world
 * coordinates through {@link #apply(BlockPos, BlockPos, PlacedModule.Facing)}.
 * Centralising the rotation here means the 3D preview and the placed world
 * <em>cannot</em> diverge: a bug in coordinate math is a bug in one place,
 * and a fix in one place, only.
 *
 * <p>Rotation rules (Y axis is up, N=−Z, E=+X, S=+Z, W=−X). The rotation
 * maps a module's local forward direction (+Z) to the world direction
 * named by the facing — so an E-facing module's local +Z points at the
 * world's +X (east), an N-facing module's local +Z points at −Z, etc.
 * <pre>
 *   facing | (x, y, z) -> (x', y', z')
 *   -------+-----------------------------
 *     S    | ( x, y,  z)   identity
 *     W    | (-z, y,  x)   local +Z maps to world -X
 *     N    | (-x, y, -z)   180°
 *     E    | ( z, y, -x)   local +Z maps to world +X
 * </pre>
 *
 * <p>The {@code y} component is identity in all four facings — we only ever
 * rotate around the vertical axis. This is the only rotation behaviour in
 * the system; if you need to tilt modules, look elsewhere.
 */
public final class ModuleTransform {

    private ModuleTransform() {}

    /**
     * Apply the {@code facing} rotation to {@code rel} (a position in the
     * module's local frame) and translate by {@code origin}.
     *
     * @param rel    a position in the module's local coordinate frame
     * @param origin the module's world origin (its entry point)
     * @param facing which 90° Y-rotation to apply
     * @return the resulting world-space position
     */
    public static BlockPos apply(BlockPos rel, BlockPos origin, PlacedModule.Facing facing) {
        int x = rel.getX();
        int y = rel.getY();
        int z = rel.getZ();
        int rx = 0;
        int rz = 0;
        switch (facing) {
            case S -> { rx =  x; rz =  z; } // identity
            case W -> { rx = -z; rz =  x; } // local +Z -> world -X
            case N -> { rx = -x; rz = -z; } // 180°
            case E -> { rx =  z; rz = -x; } // local +Z -> world +X
            default -> { /* unreachable: enum exhaustiveness */ }
        }
        return origin.offset(rx, y, rz);
    }

    /**
     * Compute a module's exit anchor in its local coordinate frame, given its
     * rotation. The exit is the bottom-centre of the bbox face that the next
     * module in the chain will attach to.
     *
     * <p>Convention (per docs/hackathon/03-module-composition.md §2):
     * <ul>
     *   <li>S (+Z): (width/2, 0, depth)</li>
     *   <li>N (−Z): (width/2, 0, 0)</li>
     *   <li>E (+X): (width,   0, depth/2)</li>
     *   <li>W (−X): (0,       0, depth/2)</li>
     * </ul>
     *
     * <p>Integer division by 2 is intentional — anchor lives on a block cell,
     * not at a sub-block centre. Modules with odd dimensions have their
     * anchor slightly biased toward the negative side, which matches
     * Minecraft's structure-block save convention.
     */
    public static BlockPos exitAnchor(StructureTemplateLoader.LoadedTemplate t,
                                       PlacedModule.Facing facing) {
        int w = t.width;
        int h = t.height;
        int d = t.depth;
        return switch (facing) {
            case S -> new BlockPos(w / 2, 0, d);
            case N -> new BlockPos(w / 2, 0, 0);
            case E -> new BlockPos(w,     0, d / 2);
            case W -> new BlockPos(0,     0, d / 2);
        };
    }
}

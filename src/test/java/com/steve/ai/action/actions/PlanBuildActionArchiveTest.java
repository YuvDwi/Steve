package com.steve.ai.action.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.steve.ai.action.BuildProject;
import com.steve.ai.llm.react.BuildPhase;
import com.steve.ai.structure.PlacedModule;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the JSON shape written by {@link PlanBuildAction#serializeProject} to
 * the mempalace archive drawer. The contract: a top-level object with
 * {@code modules[]} (one entry per PlacedModule, with name/facing/worldOrigin/
 * worldExit and a flattened {@code blocks[]} of world-space block placements),
 * plus {@code totalBlocks}, {@code steveName}, and a {@code halted} object when
 * archiving a halt record.
 *
 * <p>We don't construct a real {@code BuildProject} against a Minecraft server:
 * the helper takes {@code steveName} as a parameter precisely so the test path
 * stays free of {@code SteveEntity} (which requires a live ServerLevel).</p>
 */
class PlanBuildActionArchiveTest {

    @Test
    void designPayloadHasExpectedShape() {
        BuildProject p = new BuildProject(null, "build a castle", List.of("house_1"));
        p.originPos = new BlockPos(100, 64, -200);
        p.totalBlocks = 2;
        p.placedModules.add(new PlacedModule(
            new StructureTemplateLoader.LoadedTemplate(
                "house_1",
                List.of(
                    new StructureTemplateLoader.TemplateBlock(
                        new BlockPos(0, 0, 0),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()),
                    new StructureTemplateLoader.TemplateBlock(
                        new BlockPos(1, 0, 0),
                        net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState())
                ),
                9, 6, 9),
            new BlockPos(100, 64, -200),
            PlacedModule.Facing.S));

        JsonObject json = PlanBuildAction.serializeProject(
            p, "Steve-1", BuildPhase.DESIGN, null);

        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals(p.id, json.get("projectId").getAsString(),
            "projectId should round-trip the BuildProject.id");
        assertEquals("build a castle", json.get("command").getAsString());
        assertEquals("DESIGN", json.get("phase").getAsString());
        assertEquals("Steve-1", json.get("steveName").getAsString());
        assertEquals(2, json.get("totalBlocks").getAsInt());
        assertFalse(json.has("halted"), "DESIGN payload must not carry a halted object");

        JsonObject origin = json.getAsJsonObject("origin");
        assertEquals(100, origin.get("x").getAsInt());
        assertEquals(64, origin.get("y").getAsInt());
        assertEquals(-200, origin.get("z").getAsInt());

        JsonArray modules = json.getAsJsonArray("modules");
        assertEquals(1, modules.size(), "expected exactly one placed module");
        JsonObject m = modules.get(0).getAsJsonObject();
        assertEquals("house_1", m.get("name").getAsString());
        assertEquals("S", m.get("facing").getAsString());
        assertEquals(9, m.get("width").getAsInt());
        assertEquals(6, m.get("height").getAsInt());
        assertEquals(9, m.get("depth").getAsInt());

        JsonObject worldOrigin = m.getAsJsonObject("worldOrigin");
        assertEquals(100, worldOrigin.get("x").getAsInt());
        assertNotNull(m.getAsJsonObject("worldExit"), "worldExit should be populated");

        JsonArray blocks = m.getAsJsonArray("blocks");
        assertEquals(2, blocks.size(), "both template blocks should be flattened");
        JsonObject first = blocks.get(0).getAsJsonObject();
        assertEquals(100, first.get("x").getAsInt());
        assertEquals(64, first.get("y").getAsInt());
        assertEquals(-200, first.get("z").getAsInt());
        assertTrue(first.get("blockId").getAsString().endsWith(":stone"),
            "expected blockId to end with :stone, got " + first.get("blockId").getAsString());
    }

    @Test
    void haltedPayloadOmitsBlocksButCarriesHaltMetadata() {
        BuildProject p = new BuildProject(null, "build a castle", List.of("house_1"));
        p.phase = BuildPhase.CONSTRUCTION;
        p.totalBlocks = 200;
        p.blocksPlaced = 42;
        p.placedModules.add(new PlacedModule(
            new StructureTemplateLoader.LoadedTemplate(
                "house_1", List.of(), 9, 6, 9),
            new BlockPos(0, 0, 0),
            PlacedModule.Facing.S));

        JsonObject json = PlanBuildAction.serializeProject(
            p, "Steve-1", p.phase, "player said halt");

        assertTrue(json.has("halted"), "HALT payload must carry a halted object");
        JsonObject halted = json.getAsJsonObject("halted");
        assertEquals("player said halt", halted.get("reason").getAsString());
        assertEquals(42, halted.get("blocksPlaced").getAsInt());
        assertEquals(200, halted.get("totalBlocks").getAsInt());
        assertEquals("CONSTRUCTION", halted.get("fromPhase").getAsString());

        JsonArray modules = json.getAsJsonArray("modules");
        assertEquals(1, modules.size());
        assertFalse(modules.get(0).getAsJsonObject().has("blocks"),
            "HALT payload should omit the per-block list (already in DESIGN drawer)");
    }

    @Test
    void facingRotationProducesCorrectWorldPosition() {
        // Sanity check: ModuleTransform.apply is the single source of truth for
        // world coordinates. An east-facing module's local (1, 0, 0) should land
        // at world (0, 0, -1) relative to its origin (per the rotation table in
        // ModuleTransform).
        BuildProject p = new BuildProject(null, "x", List.of("m"));
        p.originPos = new BlockPos(0, 0, 0);
        p.placedModules.add(new PlacedModule(
            new StructureTemplateLoader.LoadedTemplate(
                "m",
                List.of(new StructureTemplateLoader.TemplateBlock(
                    new BlockPos(1, 0, 0),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())),
                1, 1, 1),
            new BlockPos(0, 0, 0),
            PlacedModule.Facing.E));

        JsonObject json = PlanBuildAction.serializeProject(
            p, "Steve-1", BuildPhase.DESIGN, null);
        JsonArray blocks = json.getAsJsonArray("modules").get(0).getAsJsonObject()
            .getAsJsonArray("blocks");
        JsonObject b = blocks.get(0).getAsJsonObject();
        assertEquals(0, b.get("x").getAsInt());
        assertEquals(0, b.get("y").getAsInt());
        assertEquals(-1, b.get("z").getAsInt());
    }
}

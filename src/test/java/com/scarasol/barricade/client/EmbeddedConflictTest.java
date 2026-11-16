package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.sampling.TestMemoryLevel;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class EmbeddedConflictTest {
    private static final BlockPos POS = new BlockPos(100_000, 80, -250_000);
    private static final UUID REGION = new UUID(1, 2);
    private static final String SLAB = "minecraft:oak_slab[type=bottom,waterlogged=false]";
    private final PaletteSpec palette = PaletteSpec.create(List.of(
            new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "resources");

    private PileModel cube(float size) {
        return new PileModel("test-cube", "minecraft:oak_planks", size, size, size, 1, List.of(CollisionBox.CUBE));
    }

    private PileModel model(TestMemoryLevel memory, EmbeddedBlock embedded, String state) {
        memory.context(Map.of(embedded.pos(), ArchivedTerrainShapes.parse(state)), Map.of());
        try { return embedded.model(memory); }
        finally { memory.reset(); }
    }

    private ArchivedTerrainSnapshot snapshot(EmbeddedBlock embedded, String state) {
        return new ArchivedTerrainSnapshot(Map.of(embedded.pos(), new WorldBlock(state, new CompoundTag(), embedded)), Set.of());
    }

    @Test void embeddingPrunesAcrossOwnersAndRemovalRefillsLocallyWithoutMovingSurvivors() {
        var memory = MemoryFixture.create(false);
        ArchivedPileData data = new ArchivedPileData();
        for (int x = 0; x < 4; x++) data.add(POS.east(x), REGION, 42);
        long initial = data.commit(REGION, palette, ArchivedTerrainSnapshot.EMPTY);
        EmbeddedBlock embedded = data.addEmbedded(POS);
        PilePiece visual = embedded.transform(model(memory, embedded, "minecraft:stone"));
        Vector3f center = visual.transformPoint(0.5f, 0.5f, 0.5f);
        PileModel small = cube(0.2f);
        var local = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(initial, 0), POS,
                PilePiece.create(small, center.x, center.y, center.z, 1, new Quaternionf()));
        var overhang = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(initial, 1), POS.east(),
                PilePiece.create(small, center.x - 1, center.y, center.z, 1, new Quaternionf()));
        var survivor = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(initial, 2), POS.east(3),
                PilePiece.create(small, 0.5f, 0.5f, 0.5f, 1, new Quaternionf()));
        List<ArchivedPileReplay.ObjectPiece> before = List.of(local, overhang, survivor);
        ArchivedTerrainSnapshot context = snapshot(embedded, "minecraft:stone");
        data.damage(data.nearby(POS), new ArchivedPileRecipe.Damage(POS, false, "minecraft:stone", context));
        long placed = data.commit(REGION, palette, ArchivedTerrainSnapshot.EMPTY);
        assertTrue(data.recipe(placed).active().isEmpty());
        assertTrue(data.recipe(placed).repack().isEmpty());

        ArchivedPileData restored = ArchivedPileData.load(data.save(new CompoundTag()));
        assertEquals(data.recipe(placed), restored.recipe(placed));
        var prepared = new ArchivedTerrainShapes(memory).prepare(restored.recipe(placed), List.of());
        assertTrue(prepared.damage().get(0).embedded());
        var after = ArchivedPileReplay.replay(prepared, List.of(before), List.of(), input -> {
            fail("Embedding must not invoke the layout generator");
            return null;
        });
        assertEquals(List.of(survivor), after);
        assertSame(survivor, after.get(0), "Survivors retain their identity and exact transform");

        restored.removeEmbedded(POS);
        long removed = restored.commit(REGION, palette, ArchivedTerrainSnapshot.EMPTY);
        assertEquals(Set.of(POS, POS.east(), POS.east(2)), restored.recipe(removed).active());
        assertTrue(restored.recipe(removed).repack().isEmpty());
        var removal = new ArchivedTerrainShapes(memory).prepare(restored.recipe(removed), List.of(cube(0.4f)));
        var refilled = TestPileReplay.replay(removal, List.of(after));
        assertTrue(refilled.size() > after.size(), "Breaking an embedded block must fill its local gap");
        assertSame(survivor, refilled.stream().filter(object -> object.key().equals(survivor.key())).findFirst().orElseThrow());
        ArchivedPileData reloaded = ArchivedPileData.load(restored.save(new CompoundTag()));
        assertEquals(refilled, TestPileReplay.replay(new ArchivedTerrainShapes(memory).prepare(
                reloaded.recipe(removed), List.of(cube(0.4f))), List.of(after)));
        assertEquals(0, memory.chunkRequests());
    }

    @Test void aThinEmbeddedModelOnlyPrunesItsTransformedBodyNotTheWholeCell() {
        var memory = MemoryFixture.create(false);
        var embedded = new EmbeddedBlock(POS, 7, 1);
        PilePiece visual = embedded.transform(model(memory, embedded, SLAB));
        var context = snapshot(embedded, SLAB);
        var node = new ArchivedPileRecipe(2, 42, List.of(1L), Set.of(POS), Set.of(), Set.of(), palette.id(),
                ArchivedTerrainSnapshot.EMPTY, List.of(new ArchivedPileRecipe.Damage(POS, false, SLAB, context)));
        var damage = new ArchivedTerrainShapes(memory).prepare(node, List.of()).damage().get(0);
        var occupied = visual.transformPoint(0.5f, 0.25f, 0.5f);
        var empty = visual.transformPoint(0.5f, 0.85f, 0.5f);
        var hit = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(1, 0), POS,
                PilePiece.create(cube(0.1f), occupied.x, occupied.y, occupied.z, 1, visual.rotation()));
        var safe = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(1, 1), POS,
                PilePiece.create(cube(0.1f), empty.x, empty.y, empty.z, 1, visual.rotation()));
        assertTrue(ArchivedPileReplay.hit(hit, damage));
        assertFalse(ArchivedPileReplay.hit(safe, damage));
        assertEquals(0, memory.chunkRequests());
    }

    @Test void embeddedPruningRemovesSmallPenetrationsButNotSeparatedOrMerelyTouchingModels() {
        var box = OrientedBox.axisAligned(0, 0, 0, 1, 1, 1);
        var ordinary = new ArchivedPileReplay.DamageShape(POS, List.of(box));
        var embedded = new ArchivedPileReplay.DamageShape(POS, List.of(box), true);
        var clipping = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(1, 0), POS,
                PilePiece.create(cube(1), 1.49f, 0.5f, 0.5f, 1, new Quaternionf()));
        var touching = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(1, 1), POS,
                PilePiece.create(cube(1), 1.5f, 0.5f, 0.5f, 1, new Quaternionf()));
        assertFalse(ArchivedPileReplay.hit(clipping, ordinary), "Ordinary damage keeps its existing inset policy");
        assertTrue(ArchivedPileReplay.hit(clipping, embedded));
        assertFalse(ArchivedPileReplay.hit(touching, embedded));
    }

    @Test void laterGenerationAvoidsEmbeddedVisualsEvenInsideItsOwnLogicalDomain() {
        var memory = MemoryFixture.create(false);
        var embedded = new EmbeddedBlock(POS, 11, 1);
        Set<BlockPos> domain = Set.of(POS, POS.east());
        var node = new ArchivedPileRecipe(1, 42, List.of(), domain, domain, Set.of(), palette.id(),
                snapshot(embedded, "minecraft:stone"), List.of(), List.of(), Map.of(POS, embedded));
        var prepared = new ArchivedTerrainShapes(memory).prepare(node, List.of(cube(0.4f)));
        assertEquals(1, prepared.obstacles().size());
        var generated = TestPileReplay.replay(prepared, List.of());
        assertFalse(generated.isEmpty());
        for (var object : generated) {
            var boxes = object.piece().colliders(object.owner().getX() - POS.getX(),
                    object.owner().getY() - POS.getY(), object.owner().getZ() - POS.getZ());
            assertTrue(boxes.stream().noneMatch(box -> prepared.obstacles().stream()
                    .anyMatch(obstacle -> box.intersects(obstacle, 0.01f))));
        }
        assertEquals(0, memory.chunkRequests());
    }

    @Test void adjacentRegionsAlsoUseRecordedEmbeddedTransformsAsObstacles() {
        var memory = MemoryFixture.create(false);
        var embedded = new EmbeddedBlock(POS, 11, 1);
        BlockPos neighbor = POS.east().south();
        var node = new ArchivedPileRecipe(1, 42, List.of(), Set.of(neighbor), Set.of(neighbor), Set.of(), palette.id(),
                snapshot(embedded, SLAB), List.of());
        var prepared = new ArchivedTerrainShapes(memory).prepare(node, List.of());
        var expected = embedded.transform(model(memory, embedded, SLAB)).colliders(-1, 0, -1).get(0);
        assertEquals(1, prepared.obstacles().size());
        for (int corner = 0; corner < 8; corner++)
            assertEquals(expected.point((corner & 1) == 0 ? -1 : 1, (corner & 2) == 0 ? -1 : 1,
                    (corner & 4) == 0 ? -1 : 1),
                    prepared.obstacles().get(0).point((corner & 1) == 0 ? -1 : 1, (corner & 2) == 0 ? -1 : 1,
                            (corner & 4) == 0 ? -1 : 1));
        assertEquals(0, memory.chunkRequests());
    }

    @Test void stateRevisionsNeverChangeTheEmbeddedTransformOrItsSerializedIdentity() {
        var memory = MemoryFixture.create(false);
        var identity = new EmbeddedBlock(POS, 7, 1);
        var revised = new EmbeddedBlock(POS, 7, 9);
        var before = identity.transform(model(memory, identity, SLAB));
        var after = revised.transform(model(memory, revised, "minecraft:stone"));
        assertEquals(before.x(), after.x());
        assertEquals(before.y(), after.y());
        assertEquals(before.z(), after.z());
        assertEquals(before.scale(), after.scale());
        assertEquals(before.rotation(), after.rotation());
        assertEquals(revised, EmbeddedBlock.load(revised.save()));
        var context = snapshot(revised, SLAB);
        assertEquals(context, ArchivedTerrainSnapshot.load(context.save()));
    }

    @Test void positionsOnEveryAxisAndRepeatedEmbedsHaveIndependentFullThreeDimensionalRotations() {
        PileModel model = cube(1);
        Set<Quaternionf> rotations = new HashSet<>();
        int sideways = 0, inverted = 0;
        for (int i = 0; i < 128; i++) {
            for (BlockPos pos : List.of(POS.east(i), POS.above(i), POS.south(i))) {
                var identity = new EmbeddedBlock(pos, 1, 1);
                var rotation = identity.transform(model).rotation();
                assertEquals(1, rotation.lengthSquared(), 0.00001);
                assertEquals(rotation, EmbeddedBlock.load(identity.save()).transform(model).rotation());
                if (i > 0) assertTrue(rotations.add(rotation), "Adjacent cells must not share a pose");
                var up = rotation.transform(new Vector3f(0, 1, 0));
                if (Math.abs(up.y) < 0.5f) sideways++;
                if (up.y < -0.5f) inverted++;
            }
        }
        assertTrue(sideways > 100, "Rotations must include large tilts, not just upright yaw");
        assertTrue(inverted > 50, "Full 3D rotation must allow inverted poses");
        rotations.clear();
        for (int instance = 1; instance <= 128; instance++)
            assertTrue(rotations.add(new EmbeddedBlock(POS, instance, 1).transform(model).rotation()),
                    "Re-embedding at the same position must create a different pose");
    }
}

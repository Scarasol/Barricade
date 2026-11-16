package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PileEditingTest {
    @Test
    void fullDoorHasOneTransformAndItsHalvesShareTheSameSeamAfterRotation() {
        PileModel door = door();
        assertEquals(2, door.parts().size());
        assertEquals(1, door.parts().get(1).y());
        assertEquals(2, door.span().y);
        assertEquals(0.1875f, door.span().x);
        assertEquals(1, door.sizeY());
        PilePiece piece = PilePiece.create(door, 0.4f, 0.7f, 0.2f, 1,
                new Quaternionf().rotationYXZ(0.8f, 0.5f, -0.35f));
        ModelPart lower = door.parts().get(0), upper = door.parts().get(1);
        Vector3f lowerSeam = piece.transformPoint(lower.x(), lower.y() + 1, lower.z());
        Vector3f upperSeam = piece.transformPoint(upper.x(), upper.y(), upper.z());
        assertEquals(lowerSeam, upperSeam);
        assertEquals(1, piece.colliders(0, 0, 0).size());
        assertEquals(2, piece.renderBoxes().size());
        assertEquals(0.375f, piece.colliders(0, 0, 0).get(0).volume(), 0.00001f);
        for (int partIndex = 0; partIndex < door.parts().size(); partIndex++) {
            ModelPart part = door.parts().get(partIndex);
            OrientedBox bounds = piece.renderBoxes().get(partIndex);
            Vector3f center = piece.transformPoint(part.x() + 0.5f, part.y() + 0.5f, part.z() + 0.5f);
            assertTrue(bounds.contains(center.x, center.y, center.z));
        }
    }

    @Test
    void doorSelectionUsesItsActualTwoBlockFootprint() {
        List<RegionPiece> small = generate(Set.of(new GridPos(0, 0, 0)), List.of(), Set.of(), 0);
        assertTrue(small.stream().noneMatch(piece -> piece.piece().model().panel()));
        Set<GridPos> larger = rectangle(5, 2, 3);
        List<RegionPiece> pieces = generate(larger, List.of(), Set.of(), 0);
        assertTrue(pieces.stream().anyMatch(piece -> piece.piece().model().panel()),
                "A large region must actually exercise the door models");
        for (RegionPiece piece : pieces) {
            assertTrue(LegacyPileGenerator.fitsDomain(piece.colliders(), larger));
        }
    }

    @Test
    void repeatedManualPlacementDoesNotFreezeTheInitialGridOfWholeCubes() {
        Set<GridPos> domain = new HashSet<>();
        List<RegionPiece> pieces = List.of();
        int generation = 0;
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    GridPos edit = new GridPos(x, y, z);
                    domain.add(edit);
                    Set<GridPos> patch = new HashSet<>();
                    for (GridPos cell : domain) {
                        if (Math.abs(cell.x() - edit.x()) <= 2 && Math.abs(cell.y() - edit.y()) <= 2
                                && Math.abs(cell.z() - edit.z()) <= 2) {
                            patch.add(cell);
                        }
                    }
                    Generated updated = complete(domain, pieces, patch, generation);
                    pieces = updated.pieces();
                    generation = updated.nextGeneration();
                }
            }
        }
        List<RegionPiece> batch = generate(domain, List.of(), Set.of(), 0);
        float manualCoverage = coverage(domain, pieces);
        float batchCoverage = coverage(domain, batch);
        long tilted = pieces.stream().filter(piece -> LegacyPileGenerator.axisAlignment(piece.piece().rotation()) < 0.985f).count();
        System.out.printf("Manual vs batch: %d / %d pieces, coverage %.3f / %.3f, manual tilted %d/%d%n",
                pieces.size(), batch.size(), manualCoverage, batchCoverage, tilted, pieces.size());
        var counts = pieces.stream().collect(java.util.stream.Collectors.groupingBy(
                piece -> piece.piece().model().id(), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        System.out.println(counts);
        assertTrue(manualCoverage > 0.4f);
        assertTrue(Math.abs(manualCoverage - batchCoverage) < 0.2f);
        assertTrue(tilted >= pieces.size() * 0.6f, "Manual placement still looks like an aligned block grid");
        assertTrue(pieces.stream().anyMatch(piece -> piece.piece().model().panel()));
    }

    @Test
    void editingRebuildsTheNearbyPatchButLeavesDistantTransformsAlone() {
        Set<GridPos> domain = rectangle(12, 2, 2);
        List<RegionPiece> original = generate(domain, List.of(), Set.of(), 0);
        Set<GridPos> patch = new HashSet<>();
        domain.stream().filter(cell -> cell.x() >= 9).forEach(patch::add);
        List<RegionPiece> remote = original.stream().filter(piece ->
                piece.colliders().stream().allMatch(box -> box.maxX() < 8.5f)).toList();
        List<RegionPiece> nearby = original.stream().filter(piece -> piece.owner().x() >= 9).toList();
        List<RegionPiece> updated = generate(domain, original, patch, 1);
        assertFalse(remote.isEmpty());
        assertTrue(updated.containsAll(remote), "An edit moved distant saved objects");
        assertFalse(nearby.isEmpty());
        assertFalse(updated.containsAll(nearby), "The editable neighborhood was never repacked");
        assertTrue(updated.stream().allMatch(piece -> piece.piece().scale() >= 0.95f && piece.piece().scale() <= 1.05f));
    }

    @Test
    void repackingACrossingDoorRefillsEveryCellItUsedToOccupy() {
        GridPos edited = new GridPos(0, 0, 0);
        Set<GridPos> domain = rectangle(3, 2, 2);
        RegionPiece crossing = new RegionPiece(edited, PilePiece.create(door(), 0.8f, 1, 0.8f, 1,
                new Quaternionf()));
        LegacyPileGenerator.Result result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(321, 1, domain,
                TestPalette.models(), List.of(crossing), List.of(), List.of(), List.of(),
                Set.of(edited), Set.of(edited)));
        assertFalse(result.pieces().contains(crossing));
        assertTrue(result.work().contains(new GridPos(0, 1, 0)),
                "Removing the whole door must include its upper cell in any refill continuation");
    }

    @Test
    void compositeDefinitionsRejectMissingGeometryAndInvalidPartOffsets() {
        assertThrows(IllegalArgumentException.class, () -> PileModels.parse(JsonParser.parseString("""
                {"models":[{"id":"bad","size":[1,1,1],"parts":[{"block":"minecraft:oak_door"}]}]}
                """).getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> new ModelPart("minecraft:oak_planks", Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ModelPart("minecraft:oak_planks", 0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new CollisionBox(0, 0, 0, 1, 100, 1));
    }

    @Test
    void localDamageCoordinatesRemainLocalFarFromTheWorldOrigin() {
        var shape = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 0.5, 1);
        var damage = List.of(OrientedBox.axisAligned(0, 0, 0, 1, 0.5f, 1));
        assertEquals(0, damage.get(0).minX());
        assertEquals(0.5f, damage.get(0).maxY());
        net.minecraft.core.BlockPos origin = new net.minecraft.core.BlockPos(100_000, 90, -250_000);
        PilePiece object = PilePiece.create(door(), 0.5f, 0.5f, 0.5f, 1, new Quaternionf());
        var worldPiece = new com.scarasol.barricade.recipe.ArchivedPileReplay.ObjectPiece(
                new com.scarasol.barricade.recipe.ArchivedPileReplay.Key(1, 0), origin, object);
        assertTrue(com.scarasol.barricade.recipe.ArchivedPileReplay.hit(worldPiece,
                new com.scarasol.barricade.recipe.ArchivedPileReplay.DamageShape(origin, damage)));
    }

    @Test
    void largePanelsStayVisibleAfterAllFillerPassesAcrossSeeds() {
        Set<GridPos> domain = rectangle(5, 2, 3);
        for (long seed : new long[]{7, 42, 100, 321}) {
            LegacyPileGenerator.Result result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(seed, 0, domain,
                    TestPalette.models(), List.of(), List.of(), List.of(), List.of()));
            for (int pass = 1; result.continueFilling() && pass < 8; pass++) {
                result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(seed, pass, domain,
                        TestPalette.models(), result.pieces(), List.of(), List.of(), List.of(),
                        result.work(), Set.of()));
            }
            assertFalse(result.continueFilling());
            List<RegionPiece> pieces = result.pieces();
            var panels = pieces.stream().filter(piece -> piece.piece().model().panel()).toList();
            assertFalse(panels.isEmpty(), "No large panels for seed " + seed);
            for (RegionPiece panel : panels) {
                float visible = LegacyPileGenerator.panelVisibility(panel, domain, pieces, List.of());
                assertTrue(visible >= 3f / 9, "Panel buried after final fill: seed=" + seed + ", visible=" + visible);
            }
        }
    }

    @Test
    void contactQueriesFindCloseFacesAcrossSpatialBuckets() {
        SpatialIndex index = new SpatialIndex();
        index.add(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1));
        OrientedBox close = OrientedBox.axisAligned(1.02f, 0, 0, 2, 1, 1);
        assertFalse(index.intersects(close, 0));
        assertTrue(index.intersects(close, -0.04f));
    }

    private PileModel door() {
        return TestPalette.models().stream().filter(model -> model.id().equals("oak_door")).findFirst().orElseThrow();
    }

    private List<RegionPiece> generate(Set<GridPos> domain, List<RegionPiece> existing,
                                        Set<GridPos> patch, int generation) {
        return complete(domain, existing, patch, generation).pieces();
    }

    private record Generated(List<RegionPiece> pieces, int nextGeneration) {
    }

    private Generated complete(Set<GridPos> domain, List<RegionPiece> existing, Set<GridPos> patch, int generation) {
        LegacyPileGenerator.Result result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(321, generation++, domain,
                TestPalette.models(), existing, List.of(), List.of(), List.of(),
                patch.isEmpty() ? domain : patch, patch));
        int passes = 1;
        while (result.continueFilling() && passes++ < 8) {
            result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(321, generation++, domain, TestPalette.models(),
                    result.pieces(), List.of(), List.of(), List.of(), result.work(), Set.of()));
        }
        assertFalse(result.continueFilling(), "A local pile did not finish after eight worker passes");
        return new Generated(result.pieces(), generation);
    }

    private float coverage(Set<GridPos> domain, List<RegionPiece> pieces) {
        List<OrientedBox> boxes = new ArrayList<>();
        pieces.forEach(piece -> boxes.addAll(piece.colliders()));
        return LegacyPileGenerator.coverage(321, domain, boxes);
    }

    private Set<GridPos> rectangle(int width, int height, int depth) {
        Set<GridPos> cells = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    cells.add(new GridPos(x, y, z));
                }
            }
        }
        return cells;
    }
}

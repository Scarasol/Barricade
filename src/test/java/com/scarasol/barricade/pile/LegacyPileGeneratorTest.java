package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class LegacyPileGeneratorTest {
    private static final GridPos ZERO = new GridPos(0, 0, 0);
    private static final Set<GridPos> SINGLE = Set.of(ZERO);

    private LegacyPileGenerator.Snapshot snapshot(long seed, Set<GridPos> domain, List<RegionPiece> existing,
                                             List<OrientedBox> neighbors, List<OrientedBox> obstacles) {
        return new LegacyPileGenerator.Snapshot(seed, 0, domain, TestPalette.models(), existing,
                neighbors, obstacles, List.of());
    }

    private List<RegionPiece> generate(long seed) {
        return LegacyPileGenerator.generate(snapshot(seed, SINGLE, List.of(), List.of(), List.of())).pieces();
    }

    @Test
    void identicalInputsAreReproducibleButIndependentSeedsDiffer() {
        List<RegionPiece> first = generate(100);
        assertEquals(first, generate(100));
        assertNotEquals(first, generate(101));
        assertFalse(first.isEmpty());
    }

    @Test
    void fillsVolumeUsingNaturalSizedObjects() {
        long start = System.nanoTime();
        for (long seed : new long[]{1, 2, 7, 42, 100}) {
            List<RegionPiece> pieces = generate(seed);
            List<OrientedBox> boxes = boxes(pieces);
            float coverage = LegacyPileGenerator.coverage(seed, SINGLE, boxes);
            float volume = monteCarloVolume(SINGLE, boxes);
            System.out.printf("seed=%d pieces=%d sampleCoverage=%.3f independentVolume=%.3f%n",
                    seed, pieces.size(), coverage, volume);
            assertFalse(pieces.isEmpty());
            assertTrue(pieces.size() <= 12, "Too many objects in a single block");
            assertTrue(coverage >= 0.50f, "Not enough coverage for seed " + seed + ": " + coverage);
            assertTrue(volume >= 0.45f, "Not enough occupied volume for seed " + seed + ": " + volume);
            for (RegionPiece piece : pieces) {
                assertTrue(piece.piece().scale() >= 0.95f && piece.piece().scale() <= 1.05f);
                assertTrue(LegacyPileGenerator.fitsDomain(piece.colliders(), SINGLE));
            }
        }
        System.out.printf("Five isolated layouts: %.1f ms%n", (System.nanoTime() - start) / 1_000_000.0);
    }

    @Test
    void bundledModelsRetainOriginalDimensionsAndUseActualNonCubeGeometry() {
        for (PileModel model : TestPalette.models()) {
            assertEquals(1, model.sizeX());
            assertEquals(1, model.sizeY());
            assertEquals(1, model.sizeZ());
        }
        assertTrue(TestPalette.models().stream().anyMatch(model -> model.boxes().size() > 1));
        assertTrue(TestPalette.models().stream().anyMatch(model ->
                model.blockState().contains("trapdoor") && model.boxes().get(0).maxY() == 0.1875f));
    }

    @Test
    void regionPackingReachesDistantCellsAndCrossesInternalGridBoundaries() {
        Set<GridPos> domain = rectangle(7, 2, 2);
        LegacyPileGenerator.Result result = LegacyPileGenerator.generate(snapshot(42, domain, List.of(), List.of(), List.of()));
        int passes = 1;
        while (result.continueFilling() && passes < 8) {
            result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(42, passes++, domain,
                    TestPalette.models(), result.pieces(), List.of(), List.of(), List.of(), result.work(), Set.of()));
        }
        assertFalse(result.continueFilling(), "A small region must finish its bounded refinement passes");
        List<RegionPiece> pieces = result.pieces();
        List<OrientedBox> boxes = boxes(pieces);
        float coverage = LegacyPileGenerator.coverage(42, domain, boxes);
        System.out.printf("28-cell region: %d pieces, coverage=%.3f, %d passes%n", pieces.size(), coverage, passes);
        var counts = pieces.stream().collect(java.util.stream.Collectors.groupingBy(
                piece -> piece.piece().model().id(), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        System.out.println(counts);
        assertTrue(coverage > 0.60f);
        assertTrue(LegacyPileGenerator.coverage(42, Set.of(new GridPos(6, 1, 1)), boxes) > 0.25f);
        assertTrue(pieces.stream().anyMatch(piece -> piece.owner().x() >= 5));
        assertTrue(pieces.stream().anyMatch(piece -> piece.colliders().stream().anyMatch(box ->
                box.minX() < 3.9f && box.maxX() > 4.1f)));
        assertTrue(pieces.stream().allMatch(piece -> domain.contains(piece.owner())
                && piece.piece().scale() >= 0.95f && piece.piece().scale() <= 1.05f));
        assertTrue(pieces.stream().anyMatch(piece -> piece.piece().model().blockState().contains("slab")));
    }

    @Test
    void piecesDoNotSubstantiallyInterpenetrate() {
        List<RegionPiece> pieces = LegacyPileGenerator.generate(snapshot(42, rectangle(3, 2, 2),
                List.of(), List.of(), List.of())).pieces();
        for (int i = 0; i < pieces.size(); i++) {
            for (int j = 0; j < i; j++) {
                for (OrientedBox a : pieces.get(i).colliders()) {
                    for (OrientedBox b : pieces.get(j).colliders()) {
                        assertFalse(a.intersects(b, 0.009f), "Intersection between pieces " + i + " and " + j);
                    }
                }
            }
        }
    }

    @Test
    void respectsSolidGroundAndWall() {
        List<OrientedBox> obstacles = List.of(OrientedBox.axisAligned(-2, -1, -2, 3, 0, 3),
                OrientedBox.axisAligned(1, 0, -1, 2, 2, 2));
        List<RegionPiece> pieces = LegacyPileGenerator.generate(snapshot(7, SINGLE, List.of(), List.of(), obstacles)).pieces();
        assertFalse(pieces.isEmpty());
        for (OrientedBox box : boxes(pieces)) {
            for (OrientedBox obstacle : obstacles) {
                assertFalse(box.intersects(obstacle, 0.0001f));
            }
        }
    }

    @Test
    void doesNotFillTheBoundingBoxOfAConcaveRegion() {
        Set<GridPos> domain = Set.of(ZERO, new GridPos(1, 0, 0), new GridPos(0, 0, 1));
        List<OrientedBox> boxes = boxes(LegacyPileGenerator.generate(snapshot(10, domain,
                List.of(), List.of(), List.of())).pieces());
        assertTrue(LegacyPileGenerator.fitsDomain(boxes, domain));
        for (OrientedBox box : boxes) {
            assertFalse(box.contains(1.5f, 0.5f, 1.5f));
        }
    }

    @Test
    void wideningRegionPreservesAllExistingPieces() {
        List<RegionPiece> original = generate(7);
        List<RegionPiece> expanded = LegacyPileGenerator.generate(snapshot(7, rectangle(3, 1, 1),
                original, List.of(), List.of())).pieces();
        assertTrue(expanded.containsAll(original));
        assertTrue(expanded.stream().anyMatch(piece -> piece.owner().x() == 2));
    }

    @Test
    void removingCellOnlyDiscardsPiecesThatNoLongerFit() {
        PileModel slab = TestPalette.models().stream().filter(model -> model.id().equals("oak_slab")).findFirst().orElseThrow();
        PileModel cube = TestPalette.models().get(0);
        RegionPiece safe = new RegionPiece(ZERO, PilePiece.create(slab, 0.5f, 0.5f, 0.5f, 1, new Quaternionf()));
        RegionPiece crossing = new RegionPiece(ZERO, PilePiece.create(cube, 1.1f, 0.7f, 0.5f, 1, new Quaternionf()));
        List<RegionPiece> repaired = LegacyPileGenerator.generate(snapshot(4, SINGLE,
                List.of(safe, crossing), List.of(), List.of())).pieces();
        assertTrue(repaired.contains(safe));
        assertFalse(repaired.contains(crossing));
    }

    @Test
    void mergingRegionsPreservesCompatibleObjectsFromBothSides() {
        List<RegionPiece> left = generate(7);
        List<RegionPiece> right = generate(12).stream()
                .map(piece -> new RegionPiece(new GridPos(3, 0, 0), piece.piece())).toList();
        List<RegionPiece> existing = new ArrayList<>(left);
        existing.addAll(right);
        List<RegionPiece> merged = LegacyPileGenerator.generate(snapshot(7, rectangle(4, 1, 1),
                existing, List.of(), List.of())).pieces();
        assertTrue(merged.containsAll(existing));
    }

    @Test
    void unloadedTerrainPreservesSavedOverhangButBlocksNewIntersections() {
        RegionPiece saved = new RegionPiece(ZERO, PilePiece.create(TestPalette.models().get(0),
                0.6f, 0.5f, 0.5f, 1, new Quaternionf()));
        OrientedBox unknown = OrientedBox.axisAligned(1, 0, 0, 2, 1, 1);
        LegacyPileGenerator.Snapshot input = new LegacyPileGenerator.Snapshot(7, 0, SINGLE, TestPalette.models(),
                List.of(saved), List.of(), List.of(), List.of(unknown));
        List<RegionPiece> result = LegacyPileGenerator.generate(input).pieces();
        assertTrue(result.contains(saved));
        for (RegionPiece piece : result) {
            if (!piece.equals(saved)) {
                assertTrue(piece.colliders().stream().noneMatch(box -> box.intersects(unknown, 0)));
            }
        }
    }

    @Test
    void neighboringGeometryContributesToFillingAndAvoidance() {
        OrientedBox occupied = OrientedBox.axisAligned(-0.1f, 0, 0, 0.45f, 1, 1);
        List<RegionPiece> pieces = LegacyPileGenerator.generate(snapshot(12, SINGLE, List.of(),
                List.of(occupied), List.of())).pieces();
        for (OrientedBox box : boxes(pieces)) {
            assertFalse(box.intersects(occupied, 0.009f));
        }
    }

    @Test
    void compoundShapesKeepTheirEmptySpaces() {
        PileModel legs = new PileModel("legs", "minecraft:oak_planks", 1, 1, 1, 1,
                List.of(new CollisionBox(0, 0, 0, 0.2f, 1, 1),
                        new CollisionBox(0.8f, 0, 0, 1, 1, 1)));
        List<OrientedBox> boxes = PilePiece.create(legs, 0.5f, 0.5f, 0.5f, 1, new Quaternionf())
                .colliders(0, 0, 0);
        assertTrue(boxes.stream().anyMatch(box -> box.contains(0.1f, 0.5f, 0.5f)));
        assertTrue(boxes.stream().noneMatch(box -> box.contains(0.5f, 0.5f, 0.5f)));
    }

    @Test
    void rotatedBoxUsesActualOrientation() {
        OrientedBox box = new OrientedBox(new Vector3f(), new Vector3f(1, 0.1f, 0.1f),
                new Quaternionf().rotationY((float) (Math.PI / 4)));
        assertTrue(box.contains(0.5f, 0, -0.5f));
        assertFalse(box.contains(0.5f, 0, 0.5f));
    }

    @Test
    void boundedLargeRegionJobsKeepTheirPreviousResultsWhenContinuing() {
        Set<GridPos> domain = rectangle(12, 2, 5);
        long start = System.nanoTime();
        LegacyPileGenerator.Result first = LegacyPileGenerator.generate(snapshot(123, domain,
                List.of(), List.of(), List.of()));
        assertFalse(first.pieces().isEmpty());
        float before = LegacyPileGenerator.coverage(123, domain, boxes(first.pieces()));
        if (first.continueFilling()) {
            LegacyPileGenerator.Result next = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(123, 1,
                    domain, TestPalette.models(), first.pieces(), List.of(), List.of(), List.of()));
            assertTrue(next.pieces().containsAll(first.pieces()));
            assertTrue(LegacyPileGenerator.coverage(123, domain, boxes(next.pieces())) >= before);
        }
        System.out.printf("120-cell bounded job: %d pieces, coverage=%.3f, continue=%s, %.1f ms%n",
                first.pieces().size(), before, first.continueFilling(), (System.nanoTime() - start) / 1_000_000.0);
    }

    @Test
    void paletteAcceptsExplicitCollisionBoxesAndRejectsInvalidDimensions() {
        String json = """
                {"models":[{"id":"slab","block":"minecraft:oak_slab","size":[1,1,1],
                "collision_boxes":[{"from":[0,0,0],"to":[1,0.5,1]}]}]}
                """;
        PileModel model = PileModels.parse(JsonParser.parseString(json).getAsJsonObject()).get(0);
        assertEquals(0.5f, model.boxes().get(0).maxY());
        assertThrows(IllegalArgumentException.class, () -> new CollisionBox(0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PileModel("invalid", "minecraft:air", Float.NaN, 1, 1, 1, List.of(CollisionBox.CUBE)));
    }

    @Test
    void aSingleMaterialPoolCanFillWithoutAFillerOrDiversityQuota() {
        Set<GridPos> domain = rectangle(5, 2, 3);
        PileModel longFurniture = new PileModel("furniture", "minecraft:oak_planks", 1, 1, 1, 1,
                List.of(new CollisionBox(0, 0, 0, 1, 0.5f, 2)));
        for (PileModel material : List.of(longFurniture, TestPalette.models().get(0))) {
            var result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(42, 0, domain,
                    List.of(material), List.of(), List.of(), List.of(), List.of()));
            for (int pass = 1; result.continueFilling() && pass < 8; pass++) {
                result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(42, pass, domain, List.of(material),
                        result.pieces(), List.of(), List.of(), List.of(), result.work(), Set.of()));
            }
            assertTrue(result.pieces().size() >= 6, "A single-material tag must not stop at a diversity quota");
            assertTrue(LegacyPileGenerator.coverage(42, domain, boxes(result.pieces())) > 0.35f);
        }
    }

    private Set<GridPos> rectangle(int width, int height, int depth) {
        Set<GridPos> domain = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    domain.add(new GridPos(x, y, z));
                }
            }
        }
        return domain;
    }

    private List<OrientedBox> boxes(List<RegionPiece> pieces) {
        List<OrientedBox> boxes = new ArrayList<>();
        pieces.forEach(piece -> boxes.addAll(piece.colliders()));
        return boxes;
    }

    private float monteCarloVolume(Set<GridPos> domain, List<OrientedBox> boxes) {
        Random random = new Random(999);
        List<GridPos> cells = domain.stream().sorted().toList();
        int occupied = 0;
        for (int i = 0; i < 5000; i++) {
            GridPos cell = cells.get(random.nextInt(cells.size()));
            float x = cell.x() + random.nextFloat(), y = cell.y() + random.nextFloat(),
                    z = cell.z() + random.nextFloat();
            if (boxes.stream().anyMatch(box -> box.contains(x, y, z))) {
                occupied++;
            }
        }
        return occupied / 5000.0f;
    }
}

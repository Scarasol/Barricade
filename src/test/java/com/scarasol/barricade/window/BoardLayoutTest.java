package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoardLayoutTest {
    static Set<BoardCell> rectangle(int u, int v, int width, int height) {
        Set<BoardCell> shape = new HashSet<>();
        for (int x = u; x < u + width; x++) for (int y = v; y < v + height; y++) shape.add(new BoardCell(x, y));
        return shape;
    }
    static BoardGroup group(Set<BoardCell> shape, Set<BoardCell> damage, Set<BoardCell> supports) {
        return new BoardGroup(new UUID(1, 2), 1, 42, new BoardPlane(Direction.NORTH, 0), shape, damage, supports);
    }
    static Set<BoardCell> supports(Set<BoardCell> shape) {
        Set<BoardCell> result = new HashSet<>();
        for (BoardCell cell : shape) for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++)
            if (!shape.contains(cell.offset(u, v))) result.add(cell.offset(u, v));
        return result;
    }
    @Test void generationAndColdReloadAreExactAndDamageNeverEntersGeometry() {
        Set<BoardCell> shape = rectangle(0, 0, 3, 3);
        BoardGroup intact = group(shape, Set.of(), supports(shape));
        BoardGroup damaged = group(shape, Set.of(new BoardCell(1, 1)), supports(shape));
        assertEquals(BoardLayout.generate(intact), BoardLayout.generate(damaged));
        assertEquals(BoardLayout.generate(intact), BoardLayout.generate(BoardGroup.load(intact.save())));
        var visible = BoardLayout.visible(BoardLayout.generate(damaged), damaged.damage());
        assertFalse(visible.isEmpty());
        assertTrue(visible.stream().noneMatch(fragment -> fragment.owner().equals(new BoardCell(1, 1))));
        assertFits(visible, union(difference(shape, damaged.damage()), supports(shape)));
    }
    @Test void planksCrossCellSeamsAndExtendGeometricallyBeyondTheOpening() {
        Set<BoardCell> shape = rectangle(0, 0, 2, 2);
        var layout = BoardLayout.generate(group(shape, Set.of(), supports(shape)));
        assertTrue(layout.planks().stream().anyMatch(plank ->
                plank.cells().size() >= 2));
        double min = vertices(layout).stream().mapToDouble(BoardLayout.Vertex::u).min().orElseThrow();
        double max = vertices(layout).stream().mapToDouble(BoardLayout.Vertex::u).max().orElseThrow();
        assertTrue(min < -3 && max > 35, min + " / " + max);
        assertEquals(layout.planks(), BoardLayout.generate(group(shape, Set.of(), Set.of())).planks());
    }
    @Test void irregularShapesAndOriginalHolesFitWholeBoardsRatherThanClippedPolygons() {
        Set<BoardCell> shape = rectangle(0, 0, 3, 3);
        shape.remove(new BoardCell(1, 1));
        shape.remove(new BoardCell(2, 2));
        var layout = BoardLayout.generate(group(shape, Set.of(), Set.of()));
        assertFalse(layout.planks().isEmpty());
        for (var fragment : BoardLayout.visible(layout, Set.of())) {
            assertTrue(shape.contains(fragment.owner()));
            assertCuboid(plank(layout, fragment), fragment);
        }
        assertFits(BoardLayout.visible(layout, Set.of()), union(shape, supports(shape)));
        assertNotEquals(layout.key(), group(rectangle(0, 0, 3, 3), Set.of(new BoardCell(1, 1)), Set.of()).layoutKey());
    }
    @Test void dimensionsUvsNormalsAndFiniteBoundsHoldAcrossSeedsAndNegativeCoordinates() {
        for (long seed = 0; seed < 24; seed++) {
            Set<BoardCell> shape = rectangle(-3, -5, 3, 3);
            BoardGroup group = new BoardGroup(new UUID(1, 2), 1, seed, new BoardPlane(Direction.UP, 10),
                    shape, Set.of(), supports(shape));
            var layout = BoardLayout.generate(group);
            assertTrue(layout.planks().size() >= 4);
            assertTrue(layout.planks().stream().anyMatch(BoardLayout.Plank::brace));
            assertEquals(layout.planks().size(), layout.planks().stream().map(BoardLayout.Plank::id).distinct().count());
            for (var plank : layout.planks()) {
                assertTrue(plank.length() >= 5 && plank.length() <= BoardLayout.MAX_LENGTH);
                assertTrue(plank.width() >= 3 && plank.width() <= 10);
                assertTrue(plank.thickness() >= 1 && plank.thickness() <= 2);
                assertEquals(1, Math.hypot(plank.direction().u(), plank.direction().v()), 0.000001);
            }
            for (var fragment : BoardLayout.visible(layout, Set.of())) {
                assertCuboid(plank(layout, fragment), fragment);
                for (var face : fragment.faces()) {
                    assertEquals(4, face.vertices().size());
                    assertEquals(1, face.normal().length(), 0.00001);
                    for (var vertex : face.vertices()) {
                        assertTrue(Double.isFinite(vertex.u()) && Double.isFinite(vertex.v()));
                        assertTrue(vertex.depth() >= 0 && vertex.depth() <= 6);
                        assertTrue(vertex.textureU() >= 0 && vertex.textureU() <= 1);
                        assertTrue(vertex.textureV() >= 0 && vertex.textureV() <= 1);
                    }
                }
            }
        }
    }
    @Test void allSixFramesRoundTripAndKeepTheWoodOnTheClickedSideOfItsSupport() {
        BlockPos position = new BlockPos(-17, 63, 31);
        for (Direction facing : Direction.values()) {
            BoardPlane plane = BoardPlane.at(position, facing);
            BoardCell cell = plane.cell(position);
            assertEquals(position, plane.block(cell));
            var back = plane.point(cell.u() * 16.0 + 8, cell.v() * 16.0 + 8, 0);
            var front = plane.point(cell.u() * 16.0 + 8, cell.v() * 16.0 + 8, 6);
            assertEquals(6.0 / 16, back.distanceTo(front), 0.00001);
            assertEquals(plane.normal(0, 0, 1), front.subtract(back).normalize());
        }
    }
    @Test void fullCapacityLayoutIsBounded() {
        Set<BoardCell> shape = rectangle(0, 0, 16, 16);
        long start = System.nanoTime();
        var layout = BoardLayout.generate(group(shape, Set.of(), supports(shape)));
        assertTrue(layout.planks().size() < 1000);
        assertTrue(vertices(layout).size() < 250_000);
        System.out.println("Window-board 16x16 layout: " + layout.planks().size() + " planks, "
                + vertices(layout).size() + " vertices, " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
    @Test void intactPlanksAreSingleClosedCuboidsWithExactlyTwoFullWidthEnds() {
        Set<BoardCell> shape = rectangle(0, 0, 3, 3);
        for (long seed = 0; seed < 40; seed++) {
            var layout = BoardLayout.generate(seeded(shape, supports(shape), seed));
            var visible = BoardLayout.visible(layout, Set.of());
            assertEquals(layout.planks().size(), visible.size(), "No body/end-piece assembly");
            for (var fragment : visible) {
                var plank = plank(layout, fragment);
                assertCuboid(plank, fragment);
                assertEquals(plank.length() * plank.width(), area(footprint(fragment)), 0.00001);
                Set<Vec3> corners = new HashSet<>();
                for (var face : fragment.faces()) if (!face.nail())
                    for (var vertex : face.vertices()) corners.add(new Vec3(vertex.u(), vertex.v(), vertex.depth()));
                assertTrue(corners.size() >= 8);
            }
        }
    }
    @Test void mainsTouchWallAndBracesTouchMainsWithIndependentNailsAtRealJoints() {
        Set<BoardCell> shape = rectangle(-1, -1, 4, 4);
        for (long seed = 0; seed < 24; seed++) {
            var layout = BoardLayout.generate(seeded(shape, supports(shape), seed));
            List<BoardLayout.Plank> mains = layout.planks().stream().filter(plank -> !plank.brace()).toList();
            for (var plank : layout.planks()) {
                if (!plank.brace()) {
                    assertEquals(0, plank.depth());
                    assertEquals(2, plank.thickness());
                } else {
                    assertEquals(2, plank.depth());
                    assertTrue(plank.nails().size() >= 2, "A complete brace must not pivot on one nail");
                    for (var nail : plank.nails()) {
                        var point = BoardStructureTest.point(plank, nail);
                        assertTrue(!shape.contains(new BoardCell((int) Math.floor(point.u() / 16), (int) Math.floor(point.v() / 16)))
                                || mains.stream().anyMatch(main -> contains(main, point)
                                && main.depth() + main.thickness() == plank.depth()));
                    }
                }
            }
            for (var fragment : BoardLayout.visible(layout, Set.of())) {
                var plank = plank(layout, fragment);
                long nailFaces = fragment.faces().stream().filter(BoardLayout.Face::nail).count();
                if (plank.brace()) assertEquals(plank.nails().size() * 6, nailFaces);
                else assertTrue(nailFaces <= plank.nails().size() * 6);
                assertEquals(0, nailFaces % 6);
                for (var face : fragment.faces()) if (face.nail()) for (var vertex : face.vertices())
                    assertTrue(vertex.depth() >= plank.depth() + plank.thickness()
                            && vertex.depth() <= 4);
            }
        }
    }
    @Test void everyCompleteBraceHasDistinctFastenersNearBothRealEnds() {
        for (var shape : List.of(rectangle(0, 0, 1, 1), rectangle(0, 0, 2, 2),
                rectangle(-3, -5, 4, 4), rectangle(0, 0, 8, 2))) {
            for (var supports : List.of(Set.<BoardCell>of(), supports(shape))) {
                for (long seed = 0; seed < 64; seed++) {
                    var layout = BoardLayout.generate(seeded(shape, supports, seed));
                    for (var fragment : BoardLayout.visible(layout, Set.of())) {
                        var plank = plank(layout, fragment);
                        if (!plank.brace()) continue;
                        List<BoardLayout.Point> heads = new ArrayList<>(nailHeads(fragment));
                        assertTrue(heads.size() >= 2, "Single-nail complete brace: seed=" + seed + ", plank=" + plank);
                        for (int end : new int[]{2, plank.length() - 2}) {
                            var point = plank.start().add(plank.direction().scale(end));
                            var nearest = heads.stream().min(Comparator.comparingDouble(head -> distance(head, point))).orElseThrow();
                            assertTrue(distance(nearest, point) <= BoardLayout.NAIL_MERGE_DISTANCE + 0.00001,
                                    "Brace end has no fastener: seed=" + seed + ", plank=" + plank);
                            heads.remove(nearest);
                        }
                    }
                }
            }
        }
    }
    @Test void sameLayerBoardsDoNotIntersectOrCreateCoplanarFlashing() {
        Set<BoardCell> shape = rectangle(0, 0, 8, 8);
        for (long seed = 0; seed < 8; seed++) {
            var layout = BoardLayout.generate(seeded(shape, supports(shape), seed));
            var visible = BoardLayout.visible(layout, Set.of());
            for (int i = 0; i < visible.size(); i++) for (int j = i + 1; j < visible.size(); j++) {
                var first = visible.get(i);
                var second = visible.get(j);
                if (plank(layout, first).depth() != plank(layout, second).depth()) continue;
                Area intersection = footprint(first);
                intersection.intersect(footprint(second));
                assertEquals(0, area(intersection), 0.00001);
            }
        }
    }
    @Test void damageCutsWholeWidthsWithoutMovingSurvivorsAndRepairRestoresTheExactMesh() {
        Set<BoardCell> shape = rectangle(0, 0, 3, 3);
        for (long seed = 0; seed < 24; seed++) {
            var layout = BoardLayout.generate(seeded(shape, supports(shape), seed));
            var intact = BoardLayout.visible(layout, Set.of());
            Set<BoardCell> damage = Set.of(new BoardCell(1, 1));
            var damaged = BoardLayout.visible(layout, damage);
            assertFits(damaged, union(difference(shape, damage), supports(shape)));
            for (var fragment : damaged) {
                var plank = plank(layout, fragment);
                assertCuboid(plank, fragment);
                if (Collections.disjoint(plank.cells(), damage)) {
                    var original = intact.stream().filter(before -> before.plankId() == plank.id()).findFirst().orElseThrow();
                    assertEquals(original.faces().stream().filter(face -> !face.nail()).toList(),
                            fragment.faces().stream().filter(face -> !face.nail()).toList());
                }
                for (var face : fragment.faces()) if (!face.nail() && face.normal().z() > 0.9) {
                    for (var vertex : face.vertices()) {
                        double along = (vertex.u() - plank.start().u()) * plank.direction().u()
                                + (vertex.v() - plank.start().v()) * plank.direction().v();
                        double expected = along / 16 - Math.floor(along / 16);
                        if (expected < 0.00001 || expected > 0.99999)
                            assertTrue(vertex.textureU() < 0.00001 || vertex.textureU() > 0.99999);
                        else assertEquals(expected, vertex.textureU(), 0.00001);
                    }
                }
            }
            assertEquals(intact, BoardLayout.visible(layout, Set.of()));
            assertTrue(BoardLayout.visible(layout, shape).isEmpty());
        }
    }
    @Test void destroyedEdgeCellsLeaveNoDetachedWallEndPiecesOrNails() {
        Set<BoardCell> shape = rectangle(0, 0, 2, 2);
        var layout = BoardLayout.generate(group(shape, Set.of(), supports(shape)));
        var visible = BoardLayout.visible(layout, rectangle(0, 0, 1, 2));
        assertFalse(visible.isEmpty());
        for (var fragment : visible) {
            assertEquals(1, fragment.owner().u());
            assertCuboid(plank(layout, fragment), fragment);
        }
        assertFits(visible, union(rectangle(1, 0, 1, 2), supports(shape)));
    }
    @Test void smallNarrowAndConcaveShapesKeepSquareEndsAcrossSeeds() {
        var concave = rectangle(-1, -1, 3, 3);
        concave.remove(new BoardCell(0, 0));
        concave.remove(new BoardCell(1, 1));
        for (var shape : List.of(rectangle(0, 0, 1, 1), rectangle(-3, -2, 1, 4),
                rectangle(0, 0, 4, 1), concave)) {
            for (long seed = 0; seed < 24; seed++) {
                for (var supports : List.of(Set.<BoardCell>of(), supports(shape))) {
                    var layout = BoardLayout.generate(seeded(shape, supports, seed));
                    var visible = BoardLayout.visible(layout, Set.of());
                    assertFalse(visible.isEmpty());
                    assertFits(visible, union(shape, supports(shape)));
                    assertEquals(layout.planks().size(), visible.size());
                    for (var fragment : visible) assertCuboid(plank(layout, fragment), fragment);
                }
            }
        }
    }
    @Test void supportedConcaveCornersNeverCreateOverlappingEndExtensions() {
        var shape = rectangle(0, 0, 16, 3);
        for (int u = 0; u < 16; u += 2) shape.remove(new BoardCell(u, 1));
        for (long seed = 0; seed < 24; seed++) {
            var layout = BoardLayout.generate(seeded(shape, supports(shape), seed));
            var visible = BoardLayout.visible(layout, Set.of());
            for (int i = 0; i < visible.size(); i++) for (int j = i + 1; j < visible.size(); j++) {
                var a = visible.get(i);
                var b = visible.get(j);
                if (plank(layout, a).depth() != plank(layout, b).depth()) continue;
                Area intersection = footprint(a);
                intersection.intersect(footprint(b));
                assertEquals(0, area(intersection), 0.00001);
            }
        }
    }
    @Test void geometricallyAnchoredEndsReceiveNailsWithoutConsultingWallMaterials() {
        var shape = rectangle(0, 0, 3, 4);
        var leftOnly = rectangle(-1, 0, 1, 4);
        for (var supports : List.of(Set.<BoardCell>of(), leftOnly, supports(shape))) {
            for (long seed = 0; seed < 24; seed++) {
                var generated = BoardLayout.generate(seeded(shape, supports, seed));
                var mains = generated.planks().stream().filter(plank -> !plank.brace()).toList();
                var layout = new BoardLayout.Layout(generated.key(), mains);
                assertFalse(mains.isEmpty());
                for (var fragment : BoardLayout.visible(layout, Set.of())) {
                    var plank = plank(layout, fragment);
                    assertEquals(plank.nails().size(), nailHeads(fragment).size());
                    for (var nail : plank.nails())
                        assertTrue(nailHeads(fragment).stream().anyMatch(head -> distance(head,
                                BoardStructureTest.point(plank, nail)) < 0.00001));
                }
            }
        }
    }
    @Test void nearBraceJointsShareOnlyTheirNearbyEndNailWhileDistantJointsKeepBothEnds() {
        for (double jointX : new double[]{4, 30, 32}) {
            var layout = nailFixture(jointX, 14);
            var visible = BoardLayout.visible(layout, Set.of());
            var main = visible.stream().filter(fragment -> fragment.plankId() == 1).findFirst().orElseThrow();
            var brace = visible.stream().filter(fragment -> fragment.plankId() == 2).findFirst().orElseThrow();
            assertEquals(1, nailHeads(main).size());
            assertEquals(1, nailHeads(brace).size());
            double remainingEnd = jointX < 18 ? 34 : 2;
            assertEquals(remainingEnd, nailHeads(main).get(0).u(), 0.00001);
            assertEquals(jointX, nailHeads(brace).get(0).u(), 0.00001);
        }
        for (double jointX : new double[]{7, 18, 29}) {
            var layout = nailFixture(jointX, 14);
            var visible = BoardLayout.visible(layout, Set.of());
            var main = visible.stream().filter(fragment -> fragment.plankId() == 1).findFirst().orElseThrow();
            assertEquals(2, nailHeads(main).size(), "A distant diagonal nail must not replace an end nail");
        }
    }
    @Test void losingASharedBraceNailRevealsTheOriginalEndNailWithoutChangingAnyWood() {
        var layout = nailFixture(32, 15.3);
        var intact = BoardLayout.visible(layout, Set.of());
        var original = intact.stream().filter(fragment -> fragment.plankId() == 1).findFirst().orElseThrow();
        assertEquals(1, nailHeads(original).size());
        Set<BoardCell> damage = Set.of(new BoardCell(1, 1));
        var damaged = BoardLayout.visible(layout, damage);
        var remaining = damaged.stream().filter(fragment -> fragment.plankId() == 1).findFirst().orElseThrow();
        assertEquals(2, nailHeads(remaining).size());
        assertEquals(original.faces().stream().filter(face -> !face.nail()).toList(),
                remaining.faces().stream().filter(face -> !face.nail()).toList());
        assertTrue(damaged.stream().filter(fragment -> fragment.plankId() == 2)
                .flatMap(fragment -> nailHeads(fragment).stream()).findAny().isEmpty());
        assertEquals(intact, BoardLayout.visible(layout, Set.of()));
    }
    @Test void aNearbyNailOnAnUnrelatedBoardCannotSuppressAnEndNail() {
        var fixture = nailFixture(32, 17);
        var visible = BoardLayout.visible(fixture, Set.of());
        var main = visible.stream().filter(fragment -> fragment.plankId() == 1).findFirst().orElseThrow();
        assertEquals(2, nailHeads(main).size(), "Nearby alone is not enough; the joint must land on this plank");
    }
    @Test void everyIntactEndHasAnOwnOrNearbySharedNailAcrossSeedsShapesAndNegativeCoordinates() {
        var irregular = rectangle(-2, -3, 4, 4);
        irregular.remove(new BoardCell(0, -2));
        for (var shape : List.of(rectangle(0, 0, 2, 2), rectangle(-3, -5, 3, 4),
                rectangle(0, 0, 8, 4), irregular)) {
            for (long seed = 0; seed < 24; seed++) {
                var layout = BoardLayout.generate(seeded(shape, Set.of(), seed));
                var visible = BoardLayout.visible(layout, Set.of());
                List<BoardLayout.Point> braceHeads = visible.stream().filter(fragment -> plank(layout, fragment).brace())
                        .flatMap(fragment -> nailHeads(fragment).stream()).toList();
                for (var fragment : visible) {
                    var plank = plank(layout, fragment);
                    if (plank.brace()) continue;
                    for (var nail : plank.nails()) {
                        var point = BoardStructureTest.point(plank, nail);
                        assertTrue(nailHeads(fragment).stream().anyMatch(head -> distance(head, point) < 0.00001)
                                || braceHeads.stream().anyMatch(head -> contains(plank, head)
                                        && distance(head, point) <= BoardLayout.NAIL_MERGE_DISTANCE + 0.00001));
                    }
                }
            }
        }
    }
    @Test void nailSharingDoesNotSplitMoveOrRetextureTheSolidPlank() {
        var layout = nailFixture(32, 14);
        var mainOnly = new BoardLayout.Layout(layout.key(), List.of(layout.planks().get(0)));
        var original = BoardLayout.visible(mainOnly, Set.of()).get(0);
        var shared = BoardLayout.visible(layout, Set.of()).stream()
                .filter(fragment -> fragment.plankId() == 1).findFirst().orElseThrow();
        assertEquals(original.faces().stream().filter(face -> !face.nail()).toList(),
                shared.faces().stream().filter(face -> !face.nail()).toList());
        assertCuboid(layout.planks().get(0), shared);
        assertEquals(2, layout.planks().get(0).nails().size(), "Keep the original end nails available as fallbacks");
    }
    @Test void renderedEndNailHeadsAreNotCompletelyBuriedUnderBraceWood() {
        for (var shape : List.of(rectangle(0, 0, 3, 4), rectangle(-3, -3, 8, 8))) {
            for (long seed = 0; seed < 24; seed++) {
                var layout = BoardLayout.generate(seeded(shape, Set.of(), seed));
                var visible = BoardLayout.visible(layout, Set.of());
                Area braces = new Area();
                for (var fragment : visible) if (plank(layout, fragment).brace()) braces.add(footprint(fragment));
                for (var fragment : visible) {
                    if (plank(layout, fragment).brace()) continue;
                    for (var face : fragment.faces()) if (face.nail() && face.normal().z > 0.9) {
                        Area exposed = faceFootprint(face);
                        double depth = face.vertices().get(0).depth();
                        if (depth <= 3) exposed.subtract(braces);
                        else {
                            assertEquals(4, depth);
                            Area contact = faceFootprint(face);
                            contact.intersect(braces);
                            assertTrue(area(contact) > 0.00001, "A raised end nail must rest on brace wood");
                        }
                        assertTrue(area(exposed) > 0.00001, "Buried end nail: seed=" + seed
                                + ", main=" + plank(layout, fragment) + ", nail=" + face.vertices()
                                + ", braces=" + layout.planks().stream().filter(BoardLayout.Plank::brace).toList());
                    }
                }
            }
        }
    }
    @Test void anEndNailCoveredByABraceTipRestsOnItsOuterSurfaceInsteadOfDisappearing() {
        var layout = nailFixture(36, 17);
        var visible = BoardLayout.visible(layout, Set.of());
        var main = visible.stream().filter(fragment -> fragment.plankId() == 1).findFirst().orElseThrow();
        assertEquals(2, nailHeads(main).size());
        var heads = main.faces().stream().filter(face -> face.nail() && face.normal().z > 0.9).toList();
        assertEquals(3, heads.get(0).vertices().get(0).depth());
        assertEquals(4, heads.get(1).vertices().get(0).depth());
        assertEquals(34, nailHeads(main).get(1).u(), 0.00001);
    }
    @Test void renderGeometryPreview() throws Exception {
        var two = rectangle(0, 0, 2, 2);
        var three = rectangle(0, 0, 3, 3);
        var irregular = rectangle(0, 0, 3, 3);
        irregular.remove(new BoardCell(2, 2)); irregular.remove(new BoardCell(2, 1));
        var ring = rectangle(0, 0, 3, 3);
        ring.remove(new BoardCell(1, 1));
        List<BoardGroup> groups = List.of(group(two, Set.of(), supports(two)),
                group(three, Set.of(), supports(three)),
                group(three, Set.of(new BoardCell(1, 1)), supports(three)),
                group(irregular, Set.of(), Set.of()), group(ring, Set.of(), Set.of()),
                group(rectangle(0, 0, 4, 3), Set.of(new BoardCell(1, 1)), Set.of()));
        String[] names = {"2x2 + wall overhang", "3x3", "3x3 damaged", "L shape", "Original hole", "Expanded 4x3 + damage"};
        BufferedImage image = new BufferedImage(1200, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(0x24282a)); graphics.fillRect(0, 0, 1200, 720);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 17));
        for (int i = 0; i < groups.size(); i++) {
            BoardGroup group = groups.get(i);
            int x = i % 3 * 400 + 60, y = i / 3 * 360 + 300;
            graphics.setColor(new Color(0xd5d9d6));
            graphics.drawString(names[i], i % 3 * 400 + 28, i / 3 * 360 + 30);
            drawGroup(graphics, group, x, y, 4);
        }
        graphics.dispose();
        Path file = Path.of("build/verification/window-boards/layouts.png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
        var full = rectangle(-4, -7, 16, 16);
        var large = seeded(full, Set.of(), 11);
        image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
        graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(0x24282a)); graphics.fillRect(0, 0, 1400, 900);
        drawGroup(graphics, large, 236, 480, 3);
        Graphics2D zoom = (Graphics2D) graphics.create();
        zoom.setClip(900, 100, 384, 384);
        drawGroup(zoom, large, 1284, 772, 6);
        zoom.dispose();
        graphics.setColor(new Color(0xdd5555));
        graphics.drawRect(236 - 3 * 48, 480 - 6 * 48, 48, 48);
        graphics.drawRect(1284 - 3 * 96, 772 - 6 * 96, 96, 96);
        graphics.dispose();
        ImageIO.write(image, "png", Path.of("build/verification/window-boards/large-layout.png").toFile());
    }
    private static void drawGroup(Graphics2D graphics, BoardGroup group, int x, int y, int scale) {
        for (BoardCell cell : group.shape()) {
            graphics.setColor(new Color(group.damage().contains(cell) ? 0x23282b : 0x536c73));
            graphics.fillRect(x + cell.u() * 16 * scale, y - (cell.v() + 1) * 16 * scale, 16 * scale - 1, 16 * scale - 1);
        }
        var faces = BoardLayout.visible(BoardLayout.generate(group), group.damage()).stream()
                .flatMap(fragment -> fragment.faces().stream()).filter(face -> face.normal().z > 0.9)
                .sorted(Comparator.comparingDouble(face -> face.vertices().get(0).depth())).toList();
        for (var face : faces) {
            Path2D path = new Path2D.Double();
            for (int j = 0; j < face.vertices().size(); j++) {
                var vertex = face.vertices().get(j);
                if (j == 0) path.moveTo(x + vertex.u() * scale, y - vertex.v() * scale);
                else path.lineTo(x + vertex.u() * scale, y - vertex.v() * scale);
            }
            path.closePath();
            graphics.setColor(new Color(face.nail() ? 0x52565a : face.tint()));
            graphics.fill(path);
        }
    }
    private static List<BoardLayout.Vertex> vertices(BoardLayout.Layout layout) {
        return BoardLayout.visible(layout, Set.of()).stream().flatMap(fragment -> fragment.faces().stream())
                .flatMap(face -> face.vertices().stream()).toList();
    }
    private static BoardGroup seeded(Set<BoardCell> shape, Set<BoardCell> supports, long seed) {
        return new BoardGroup(new UUID(1, 2), 1, seed, new BoardPlane(Direction.NORTH, 0), shape, Set.of(), supports);
    }
    static BoardLayout.Plank plank(BoardLayout.Layout layout, BoardLayout.Fragment fragment) {
        return layout.planks().stream().filter(plank -> plank.id() == fragment.plankId()).findFirst().orElseThrow();
    }
    private static BoardLayout.Layout nailFixture(double jointU, double jointV) {
        var shape = rectangle(-1, -1, 6, 4);
        var main = new BoardLayout.Plank(1, new BoardLayout.Point(0, 14), new BoardLayout.Point(1, 0),
                36, 4, 2, 0, false, 0xc0b6a4, 0,
                List.of(new BoardLayout.Nail(2, new BoardCell(0, 0)), new BoardLayout.Nail(34, new BoardCell(2, 0))),
                rectangle(0, 0, 3, 1).stream().sorted().toList());
        int minU = (int) Math.floor((jointU - 7.2) / 16), maxU = (int) Math.floor((jointU + 9.6) / 16);
        int minV = (int) Math.floor((jointV - 8.9) / 16), maxV = (int) Math.floor((jointV + 12.1) / 16);
        var brace = new BoardLayout.Plank(2, new BoardLayout.Point(jointU - 6, jointV - 8),
                new BoardLayout.Point(0.6, 0.8), 24, 3, 1, 2, true, 0xc8beac, 0,
                List.of(new BoardLayout.Nail(10, new BoardCell((int) Math.floor(jointU / 16), (int) Math.floor(jointV / 16)))),
                rectangle(minU, minV, maxU - minU + 1, maxV - minV + 1).stream().sorted().toList());
        return new BoardLayout.Layout(group(shape, Set.of(), Set.of()).layoutKey(), List.of(main, brace));
    }
    private static List<BoardLayout.Point> nailHeads(BoardLayout.Fragment fragment) {
        return fragment.faces().stream().filter(face -> face.nail() && face.normal().z > 0.9).map(face ->
                new BoardLayout.Point(face.vertices().stream().mapToDouble(BoardLayout.Vertex::u).average().orElseThrow(),
                        face.vertices().stream().mapToDouble(BoardLayout.Vertex::v).average().orElseThrow())).toList();
    }
    private static double distance(BoardLayout.Point a, BoardLayout.Point b) {
        return Math.hypot(a.u() - b.u(), a.v() - b.v());
    }
    private static boolean contains(BoardLayout.Plank plank, BoardLayout.Point point) {
        double u = point.u() - plank.start().u(), v = point.v() - plank.start().v();
        double along = u * plank.direction().u() + v * plank.direction().v();
        double across = -u * plank.direction().v() + v * plank.direction().u();
        return along >= -0.00001 && along <= plank.length() + 0.00001
                && Math.abs(across) <= plank.width() / 2.0 + 0.00001;
    }
    static void assertCuboid(BoardLayout.Plank plank, BoardLayout.Fragment fragment) {
        List<BoardLayout.Face> wood = fragment.faces().stream().filter(face -> !face.nail()).toList();
        assertFalse(wood.isEmpty());
        var caps = wood.stream().filter(face -> Math.abs(face.normal().x * plank.direction().u()
                + face.normal().y * plank.direction().v()) > 0.99).toList();
        assertEquals(2, caps.size(), "Only the actual two ends may have caps");
        double[] span = new double[2];
        for (int i = 0; i < caps.size(); i++) {
            var cap = caps.get(i);
            span[i] = (cap.vertices().get(0).u() - plank.start().u()) * plank.direction().u()
                    + (cap.vertices().get(0).v() - plank.start().v()) * plank.direction().v();
            assertEquals(Math.rint(span[i]), span[i], 0.00001, "Integer plank/cut length");
            assertEquals(plank.width() * plank.thickness(), faceArea(cap), 0.00001, "Full-width square end");
        }
        double length = Math.abs(span[1] - span[0]);
        assertEquals(length * plank.width(), area(footprint(fragment)), 0.00001, "No missing wedges or seams");
        double surface = wood.stream().mapToDouble(BoardLayoutTest::faceArea).sum();
        assertEquals(2 * (length * plank.width() + length * plank.thickness()
                + plank.width() * plank.thickness()), surface, 0.00001, "No artificial internal faces");
        for (var face : fragment.faces()) {
            assertEquals(4, new HashSet<>(face.vertices()).size(), "No degenerate triangle quads");
            assertTrue(faceArea(face) > 0.000001);
            for (var vertex : face.vertices()) if (!face.nail()) {
                assertTrue(vertex.depth() == plank.depth() || vertex.depth() == plank.depth() + plank.thickness());
                double across = -(vertex.u() - plank.start().u()) * plank.direction().v()
                        + (vertex.v() - plank.start().v()) * plank.direction().u();
                assertEquals(plank.width() / 2.0, Math.abs(across), 0.00001);
            }
        }
    }
    private static double faceArea(BoardLayout.Face face) {
        var a = face.vertices().get(0);
        var b = face.vertices().get(1);
        var d = face.vertices().get(3);
        return new Vec3(b.u() - a.u(), b.v() - a.v(), b.depth() - a.depth())
                .cross(new Vec3(d.u() - a.u(), d.v() - a.v(), d.depth() - a.depth())).length();
    }
    static Area footprint(BoardLayout.Fragment fragment) {
        Area result = new Area();
        for (var face : fragment.faces()) if (!face.nail() && face.normal().z > 0.9)
            result.add(faceFootprint(face));
        return result;
    }
    private static Area faceFootprint(BoardLayout.Face face) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < face.vertices().size(); i++) {
            var vertex = face.vertices().get(i);
            if (i == 0) path.moveTo(vertex.u(), vertex.v());
            else path.lineTo(vertex.u(), vertex.v());
        }
        path.closePath();
        return new Area(path);
    }
    private static void assertFits(List<BoardLayout.Fragment> fragments, Set<BoardCell> allowed) {
        Area boundary = new Area();
        for (var cell : allowed) boundary.add(new Area(new Rectangle2D.Double(cell.u() * 16, cell.v() * 16, 16, 16)));
        for (var fragment : fragments) {
            Area outside = footprint(fragment);
            outside.subtract(boundary);
            assertEquals(0, area(outside), 0.00001, "Wood must not cross holes or destroyed cells");
        }
    }
    static double area(Area area) {
        double result = 0, firstX = 0, firstY = 0, lastX = 0, lastY = 0;
        double[] point = new double[6];
        for (PathIterator path = area.getPathIterator(null); !path.isDone(); path.next()) {
            switch (path.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> { firstX = lastX = point[0]; firstY = lastY = point[1]; }
                case PathIterator.SEG_LINETO -> {
                    result += lastX * point[1] - point[0] * lastY;
                    lastX = point[0]; lastY = point[1];
                }
                case PathIterator.SEG_CLOSE -> result += lastX * firstY - firstX * lastY;
                default -> fail("Non-polygonal board");
            }
        }
        return Math.abs(result) / 2;
    }
    private static Set<BoardCell> difference(Set<BoardCell> shape, Set<BoardCell> damage) {
        Set<BoardCell> result = new HashSet<>(shape);
        result.removeAll(damage);
        return result;
    }
    private static Set<BoardCell> union(Set<BoardCell> shape, Set<BoardCell> supports) {
        Set<BoardCell> result = new HashSet<>(shape);
        result.addAll(supports);
        return result;
    }
}

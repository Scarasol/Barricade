package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.pile.PilePiece;
import java.util.List;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class VisualOverlapTest {
    private static final PileModel MODEL = new PileModel("cube", "minecraft:stone",
            1, 1, 1, 1, List.of(CollisionBox.CUBE));

    @Test void touchingAndShallowPenetrationStayButObviousCrossingDoesNot() {
        try (StaticMesh cube = VisualMeshFixture.boxes(CollisionBox.CUBE)) {
            var first = VisualOverlap.capture(cube);
            for (float shift : new float[]{1, .99f, .98f}) {
                assertFalse(VisualOverlap.obvious(first, shifted(cube, shift, 0, 0)), "shift=" + shift);
                assertFalse(VisualOverlap.obvious(shifted(cube, shift, 0, 0), first), "Symmetric tolerance");
            }
            for (float shift : new float[]{.9f, .5f, 0}) {
                assertTrue(VisualOverlap.obvious(first, shifted(cube, shift, 0, 0)), "shift=" + shift);
                assertTrue(VisualOverlap.obvious(shifted(cube, shift, 0, 0), first), "Symmetric intersection");
            }
        }
    }

    @Test void aLongThinBarCrossingTheEmbeddedFaceIsCaughtEvenWithItsBodyCenterOutside() {
        try (StaticMesh furnace = VisualMeshFixture.boxes(CollisionBox.CUBE);
             StaticMesh bar = VisualMeshFixture.boxes(new CollisionBox(.5f, .45f, .45f, 1.8f, .55f, .55f))) {
            assertTrue(VisualOverlap.obvious(VisualOverlap.capture(bar), VisualOverlap.capture(furnace)));
            assertTrue(VisualOverlap.obvious(VisualOverlap.capture(furnace), VisualOverlap.capture(bar)));
        }
    }

    @Test void hollowFurnitureAcceptsObjectsInItsEmptySpaceEvenWhenBothCollisionBoxesAreFullCubes() {
        try (StaticMesh furniture = VisualMeshFixture.boxes(
                new CollisionBox(0, .85f, 0, 1, 1, 1),
                new CollisionBox(0, 0, 0, .1f, .85f, .1f),
                new CollisionBox(.9f, 0, 0, 1, .85f, .1f),
                new CollisionBox(0, 0, .9f, .1f, .85f, 1),
                new CollisionBox(.9f, 0, .9f, 1, .85f, 1));
             StaticMesh small = VisualMeshFixture.boxes(new CollisionBox(.3f, .3f, .3f, .7f, .7f, .7f))) {
            assertTrue(furniture.bounds.intersects(small.bounds));
            assertFalse(VisualOverlap.obvious(VisualOverlap.capture(furniture), VisualOverlap.capture(small)));
            assertFalse(VisualOverlap.obvious(VisualOverlap.capture(small), VisualOverlap.capture(furniture)));
            assertTrue(VisualOverlap.obvious(VisualOverlap.capture(furniture), shifted(small, .3f, .4f, .3f)),
                    "A real table-top intersection is still rejected");
        }
    }

    @Test void containedGeometryCannotCoverTheEmbeddedObjectAndOverlappingComponentsDoNotCancel() {
        try (StaticMesh cube = VisualMeshFixture.boxes(CollisionBox.CUBE);
             StaticMesh small = VisualMeshFixture.boxes(new CollisionBox(.3f, .3f, .3f, .7f, .7f, .7f));
             StaticMesh doubled = VisualMeshFixture.boxes(CollisionBox.CUBE, CollisionBox.CUBE)) {
            assertTrue(VisualOverlap.obvious(VisualOverlap.capture(cube), VisualOverlap.capture(small)));
            assertTrue(VisualOverlap.obvious(VisualOverlap.capture(small), VisualOverlap.capture(cube)));
            assertTrue(VisualOverlap.obvious(VisualOverlap.capture(small), VisualOverlap.capture(doubled)),
                    "Oriented winding handles overlapping closed model components");
        }
    }

    @Test void rotatedAndScaledGeometryUsesRenderedCoordinatesRatherThanAxisAlignedBounds() {
        try (StaticMesh cube = VisualMeshFixture.boxes(CollisionBox.CUBE);
             StaticMesh bar = VisualMeshFixture.boxes(new CollisionBox(.5f, .45f, .45f, 1.8f, .55f, .55f));
             StaticMesh clear = VisualMeshFixture.boxes(new CollisionBox(1.4f, .3f, .3f, 1.8f, .7f, .7f))) {
            for (int i = 0; i < 8; i++) {
                var pose = PilePiece.create(MODEL, -.4f, .6f, .2f, .98f,
                        new Quaternionf().rotationXYZ(.31f * i, -.73f * i, .17f * i));
                var solid = VisualOverlap.capture(cube).transformed(pose, 0, 0, 0);
                assertTrue(VisualOverlap.obvious(solid, VisualOverlap.capture(bar).transformed(pose, 0, 0, 0)));
                assertFalse(VisualOverlap.obvious(solid, VisualOverlap.capture(clear).transformed(pose, 0, 0, 0)));
            }
        }
    }

    private static VisualOverlap.Geometry shifted(StaticMesh mesh, float x, float y, float z) {
        return VisualOverlap.capture(mesh).transformed(PilePiece.create(MODEL, .5f + x, .5f + y, .5f + z,
                1, new Quaternionf()), 0, 0, 0);
    }
}

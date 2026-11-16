package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PilePieceBoundsTest {
    @Test void cachedMaterialGeometryPreservesPosesWithoutMutatingSharedVectors() {
        PileModel model = new PileModel("test", List.of(new ModelPart("minecraft:stone", 0, 0, 0)),
                .7f, .4f, 1.2f, 1, List.of(new CollisionBox(-.2f, .1f, 0, .8f, .5f, 1.3f)),
                -.4f, .9f, .3f);
        Vector3f span = model.span(), collisionCenter = model.collisionCenter();
        for (int i = 0; i < 100; i++) {
            Quaternionf expected = PileGeometry.orientation(model, new Random(i), null);
            Quaternionf actual = PileGeometry.orientation(span, new Random(i), null);
            assertEquals(expected, actual);
            Vector3f center = new Vector3f(.13f, .91f, .37f);
            assertEquals(PileGeometry.atCenter(model, center, .97f, expected, new GridPos(-1, 0, 1)),
                    PileGeometry.atCenter(model, collisionCenter, center, .97f, actual, new GridPos(-1, 0, 1)));
            assertEquals(model.span(), span);
            assertEquals(model.collisionCenter(), collisionCenter);
        }
    }
    @Test void directBoundsExactlyMatchAllocatedCollidersForCompoundOffCenterGeometry() {
        Random random = new Random(90521);
        for (int sample = 0; sample < 500; sample++) {
            List<CollisionBox> boxes = new ArrayList<>();
            for (int i = 0; i < 1 + sample % 16; i++) {
                float x = random.nextFloat() * 3 - 1, y = random.nextFloat() * 3 - 1, z = random.nextFloat() * 3 - 1;
                boxes.add(new CollisionBox(x, y, z, x + .01f + random.nextFloat(),
                        y + .01f + random.nextFloat(), z + .01f + random.nextFloat()));
            }
            PileModel model = new PileModel("test", List.of(new ModelPart("minecraft:stone", 0, 0, 0)),
                    .1f + random.nextFloat(), .1f + random.nextFloat(), .1f + random.nextFloat(), 1, boxes,
                    random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2);
            PilePiece piece = PilePiece.create(model, random.nextFloat() - .5f, random.nextFloat() - .5f,
                    random.nextFloat() - .5f, .1f + random.nextFloat(),
                    new Quaternionf().rotationXYZ(random.nextFloat() * 6, random.nextFloat() * 6, random.nextFloat() * 6));
            float[] expected = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (OrientedBox box : piece.colliders(0, 0, 0)) {
                expected[0] = Math.min(expected[0], box.minX()); expected[1] = Math.min(expected[1], box.minY());
                expected[2] = Math.min(expected[2], box.minZ()); expected[3] = Math.max(expected[3], box.maxX());
                expected[4] = Math.max(expected[4], box.maxY()); expected[5] = Math.max(expected[5], box.maxZ());
            }
            assertArrayEquals(expected, piece.collisionBounds(), "Bounds must preserve even floating-point boundary decisions");
        }
    }
}

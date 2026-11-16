package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class FixedGeometryTest {
    @Test void quantizedAxesDoNotMissSubstantialObbIntersections() {
        Random random = new Random(1762);
        for (int i = 0; i < 2000; i++) {
            OrientedBox first = randomBox(random), second = randomBox(random);
            int[] data = new int[FixedGeometry.STRIDE * 2];
            System.arraycopy(FixedGeometry.encode(first), 0, data, 0, FixedGeometry.STRIDE);
            System.arraycopy(FixedGeometry.encode(second), 0, data, FixedGeometry.STRIDE, FixedGeometry.STRIDE);
            if (first.intersects(second, .01f))
                assertTrue(FixedGeometry.intersects(data, 0, 0, FixedGeometry.STRIDE, 0));
            Vector3f center = first.point(0, 0, 0);
            assertTrue(FixedGeometry.contains(data, 0, 0, Math.round(center.x * 1024),
                    Math.round(center.y * 1024), Math.round(center.z * 1024)));
            assertFalse(FixedGeometry.contains(data, 0, 0, Math.round((first.maxX() + .1f) * 1024),
                    Math.round(center.y * 1024), Math.round(center.z * 1024)));
        }
    }
    @Test void tinyShapesKeepANonzeroCollisionBody() {
        OrientedBox shape = new OrientedBox(new Vector3f(), new Vector3f(.0001f),
                new Quaternionf().rotationXYZ(.3f, .8f, .6f));
        int[] data = new int[FixedGeometry.STRIDE * 2];
        System.arraycopy(FixedGeometry.encode(shape), 0, data, 0, FixedGeometry.STRIDE);
        System.arraycopy(data, 0, data, FixedGeometry.STRIDE, FixedGeometry.STRIDE);
        assertTrue(FixedGeometry.intersects(data, 0, 0, FixedGeometry.STRIDE, 0, -5));
    }
    @Test void sweptIntervalsExactlyMatchEveryDiscreteCollisionStep() {
        Random random = new Random(87561);
        for (int i = 0; i < 8000; i++) {
            int[] data = new int[FixedGeometry.STRIDE * 2];
            System.arraycopy(FixedGeometry.encode(randomBox(random)), 0, data, 0, FixedGeometry.STRIDE);
            System.arraycopy(FixedGeometry.encode(randomBox(random)), 0, data, FixedGeometry.STRIDE, FixedGeometry.STRIDE);
            int otherY = random.nextInt(3073) - 1536, maximum = 1 + random.nextInt(48), margin = i % 2 == 0 ? -5 : 3;
            int expected = maximum + 1;
            for (int step = 1; step <= maximum; step++)
                if (FixedGeometry.intersects(data, 0, -36 * step, FixedGeometry.STRIDE, otherY, margin)) {
                    expected = step; break;
                }
            assertEquals(expected, FixedGeometry.firstIntersectionStep(data, 0, FixedGeometry.STRIDE,
                    otherY, 36, maximum, margin), "Sweep must preserve discrete settling");
        }
    }
    @Test void packedGeometryEncodingMatchesStandaloneRecords() {
        Random random = new Random(523);
        int[] packed = new int[FixedGeometry.STRIDE * 3 + 2];
        packed[0] = 71; packed[packed.length - 1] = 93;
        for (int i = 0; i < 3; i++) {
            OrientedBox box = randomBox(random);
            FixedGeometry.encode(box, packed, 1 + i * FixedGeometry.STRIDE);
            assertArrayEquals(FixedGeometry.encode(box), Arrays.copyOfRange(packed,
                    1 + i * FixedGeometry.STRIDE, 1 + (i + 1) * FixedGeometry.STRIDE));
        }
        assertEquals(71, packed[0]); assertEquals(93, packed[packed.length - 1]);
    }
    @Test void separateShapeTranslationExactlyMatchesCopiedFixedPointGeometry() {
        Random random = new Random(81349);
        for (int i = 0; i < 8000; i++) {
            int[] left = FixedGeometry.encode(randomBox(random)), right = FixedGeometry.encode(randomBox(random));
            int[] beforeLeft = left.clone(), beforeRight = right.clone();
            int dx = random.nextInt(8193) - 4096, dy = random.nextInt(8193) - 4096, dz = random.nextInt(8193) - 4096;
            int[] copied = new int[FixedGeometry.STRIDE * 2];
            System.arraycopy(left, 0, copied, 0, left.length);
            System.arraycopy(right, 0, copied, left.length, right.length);
            copied[left.length] += dx; copied[left.length + 1] += dy; copied[left.length + 2] += dz;
            int margin = i % 2 == 0 ? -3 : 3;
            assertEquals(FixedGeometry.intersects(copied, 0, 0, left.length, 0, margin),
                    FixedGeometry.intersects(left, 0, right, 0, dx, dy, dz, margin));
            assertArrayEquals(beforeLeft, left); assertArrayEquals(beforeRight, right);
        }
    }
    @Test void arbitrarySeparateArraySweepsMatchEveryStaticTestIncludingZeroAndSingleStepIntervals() {
        Random random = new Random(14756);
        for (int i = 0; i < 8000; i++) {
            int[] left = FixedGeometry.encode(randomBox(random)), right = FixedGeometry.encode(randomBox(random));
            int[] beforeLeft = left.clone(), beforeRight = right.clone();
            int dx = random.nextInt(3073) - 1536, dy = random.nextInt(3073) - 1536;
            int dz = random.nextInt(3073) - 1536, maximum = random.nextInt(49);
            int sx = random.nextInt(73) - 36, sy = random.nextInt(73) - 36, sz = random.nextInt(73) - 36;
            int margin = i % 2 == 0 ? -3 : 3;
            long interval = FixedGeometry.intersectionSteps(left, 0, right, 0, dx, dy, dz,
                    sx, sy, sz, maximum, margin);
            for (int step = 0; step <= maximum; step++)
                assertEquals(FixedGeometry.intersects(left, 0, right, 0,
                                dx - step * sx, dy - step * sy, dz - step * sz, margin),
                        interval != -1 && step >= (int) (interval >>> 32) && step <= (int) interval,
                        "Swept interval must equal the static SAT predicate at every step");
            assertArrayEquals(beforeLeft, left); assertArrayEquals(beforeRight, right);
        }
        int[] cube = FixedGeometry.encode(new OrientedBox(new Vector3f(), new Vector3f(.5f), new Quaternionf()));
        assertEquals(0, FixedGeometry.intersectionSteps(cube, 0, cube, 0, 0, 0, 0, 0, 0, 0, 0, -3));
        assertEquals(-1, FixedGeometry.intersectionSteps(cube, 0, cube, 0, 4096, 0, 0, 0, 0, 0, 0, -3));
    }
    @Test void sweptPointContainmentExactlyMatchesEveryStaticStep() {
        Random random = new Random(82651);
        for (int i = 0; i < 4000; i++) {
            int[] shape = FixedGeometry.encode(randomBox(random)), shifted = shape.clone();
            int[] before = shape.clone();
            int x = random.nextInt(8193) - 4096, y = random.nextInt(8193) - 4096, z = random.nextInt(8193) - 4096;
            int sx = random.nextInt(73) - 36, sy = random.nextInt(73) - 36, sz = random.nextInt(73) - 36;
            int maximum = random.nextInt(49);
            long interval = FixedGeometry.containmentSteps(shape, 0, x, y, z, sx, sy, sz, maximum);
            for (int step = 0; step <= maximum; step++) {
                shifted[0] = shape[0] + step * sx;
                shifted[1] = shape[1] + step * sy;
                shifted[2] = shape[2] + step * sz;
                assertEquals(FixedGeometry.contains(shifted, 0, 0, x, y, z),
                        interval != -1 && step >= (int) (interval >>> 32) && step <= (int) interval);
            }
            assertArrayEquals(before, shape);
        }
    }
    @Test void directCompoundPieceEncodingExactlyMatchesBoxesIncludingAsymmetricPivotsAndReusedBuffers() {
        Random random = new Random(12891);
        for (int i = 0; i < 2000; i++) {
            List<CollisionBox> boxes = new ArrayList<>();
            for (int count = 1 + random.nextInt(8); count > 0; count--) {
                float x = random.nextFloat() * 2 - 1, y = random.nextFloat() * 2 - 1, z = random.nextFloat() * 2 - 1;
                boxes.add(new CollisionBox(x, y, z, x + .002f + random.nextFloat(),
                        y + .002f + random.nextFloat(), z + .002f + random.nextFloat()));
            }
            PileModel model = new PileModel("compound", List.of(new ModelPart("minecraft:stone", 0, 0, 0)),
                    .1f + random.nextFloat() * 1.5f, .1f + random.nextFloat() * 1.5f,
                    .1f + random.nextFloat() * 1.5f, 1, boxes,
                    random.nextFloat() * 2 - .5f, random.nextFloat() * 2 - .5f, random.nextFloat() * 2 - .5f);
            PilePiece piece = PilePiece.create(model, random.nextFloat() * 5 - 2, random.nextFloat() * 5 - 2,
                    random.nextFloat() * 5 - 2, .95f + random.nextFloat() * .1f,
                    new Quaternionf().rotationXYZ(random.nextFloat() * 6, random.nextFloat() * 6, random.nextFloat() * 6));
            int[] packed = new int[boxes.size() * FixedGeometry.STRIDE + 2];
            Arrays.fill(packed, 17);
            piece.encodeColliders(packed, 1);
            var expected = piece.colliders(0, 0, 0);
            for (int box = 0; box < expected.size(); box++)
                assertArrayEquals(FixedGeometry.encode(expected.get(box)),
                        Arrays.copyOfRange(packed, 1 + box * FixedGeometry.STRIDE, 1 + (box + 1) * FixedGeometry.STRIDE));
            int[] before = packed.clone();
            piece.encodeColliders(packed, 1);
            assertArrayEquals(before, packed);
            int dx = random.nextInt(17) - 8, dy = random.nextInt(17) - 8, dz = random.nextInt(17) - 8;
            piece.encodeColliders(packed, 1, dx, dy, dz);
            expected = piece.colliders(dx, dy, dz);
            for (int box = 0; box < expected.size(); box++)
                assertArrayEquals(FixedGeometry.encode(expected.get(box)),
                        Arrays.copyOfRange(packed, 1 + box * FixedGeometry.STRIDE, 1 + (box + 1) * FixedGeometry.STRIDE));
            assertEquals(17, packed[0]); assertEquals(17, packed[packed.length - 1]);
        }
    }
    private OrientedBox randomBox(Random random) {
        return new OrientedBox(new Vector3f(random.nextFloat() * 6 - 3, random.nextFloat() * 6 - 3,
                random.nextFloat() * 6 - 3), new Vector3f(.02f + random.nextFloat() * 2,
                .02f + random.nextFloat() * 2, .02f + random.nextFloat() * 2),
                new Quaternionf().rotationXYZ(random.nextFloat() * 6, random.nextFloat() * 6, random.nextFloat() * 6));
    }
}

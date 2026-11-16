package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class OrientedBoxTest {
    @Test
    void penetrationMeasuresWorldDistanceRatherThanLocalAxisInset() {
        var cube = OrientedBox.axisAligned(0, 0, 0, 1, 1, 1);
        var touching = OrientedBox.axisAligned(1, .25f, .25f, 1.25f, .75f, .75f);
        var shallow = touching.translated(-1f / 32, 0, 0);
        assertEquals(0, touching.penetration(cube, 0, 0, 0));
        assertEquals(1f / 32, shallow.penetration(cube, 0, 0, 0), .000001f);
        assertEquals(.6f, OrientedBox.axisAligned(.4f, .4f, .4f, .6f, .6f, .6f)
                .penetration(cube, 0, 0, 0), .000001f);
        var rotated = new OrientedBox(new Vector3f(1.1f, .5f, .5f), new Vector3f(.2f),
                new Quaternionf().rotationY((float) Math.PI / 4));
        assertEquals((float) Math.sqrt(.08) - .1f, rotated.penetration(cube, 0, 0, 0), .000001f);
        assertEquals(rotated.penetration(cube, 0, 0, 0),
                rotated.translated(7, -3, 2).penetration(cube, -7, 3, -2), .000001f);
    }

    @Test
    void penetrationIsSymmetricAndInvariantUnderSharedRotation() {
        var random = new Random(32);
        for (int i = 0; i < 1000; i++) {
            var relative = new Quaternionf().rotationXYZ(random.nextFloat(), random.nextFloat(), random.nextFloat());
            var position = new Vector3f(random.nextFloat() * 1.8f, .1f, .2f);
            var a = new OrientedBox(new Vector3f(), new Vector3f(.7f, .4f, .3f), new Quaternionf());
            var b = new OrientedBox(position, new Vector3f(.5f, .3f, .4f), relative);
            float depth = a.penetration(b, 0, 0, 0);
            assertEquals(depth, b.penetration(a, 0, 0, 0), .000001f);
            var frame = new Quaternionf().rotationXYZ(random.nextFloat() * 6, random.nextFloat() * 6, random.nextFloat() * 6);
            var turnedA = new OrientedBox(new Vector3f(), new Vector3f(.7f, .4f, .3f), frame);
            var turnedB = new OrientedBox(frame.transform(new Vector3f(position)),
                    new Vector3f(.5f, .3f, .4f), new Quaternionf(frame).mul(relative));
            assertEquals(depth, turnedA.penetration(turnedB, 0, 0, 0), .000002f);
        }
    }

    @Test
    void translationPreservesRotatedGeometryWithoutMutatingTheSource() {
        Quaternionf rotation = new Quaternionf().rotationXYZ(.3f, -.7f, 1.1f);
        OrientedBox box = new OrientedBox(new Vector3f(1, 2, 3), new Vector3f(.2f, .7f, .4f), rotation);
        Vector3f offset = new Vector3f(8, -4, 2);
        OrientedBox translated = box.translated(offset.x, offset.y, offset.z);
        assertEquals(new Vector3f(1, 2, 3), box.point(0, 0, 0));
        assertEquals(box.volume(), translated.volume());
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            assertTrue(box.point(x, y, z).add(offset).distance(translated.point(x, y, z)) < .000002f);
        OrientedBox roundTrip = translated.translated(-offset.x, -offset.y, -offset.z);
        assertTrue(box.point(.5f, -.5f, .5f).distance(roundTrip.point(.5f, -.5f, .5f)) < .000002f);
    }
    @Test
    void overlapAndSeparationAreInvariantUnderASharedWorldRotation() {
        Random random = new Random(379);
        for (int i = 0; i < 500; i++) {
            Quaternionf frame = new Quaternionf().rotationXYZ(random.nextFloat() * 6,
                    random.nextFloat() * 6, random.nextFloat() * 6);
            Vector3f origin = new Vector3f(random.nextFloat() * 40 - 20,
                    random.nextFloat() * 40 - 20, random.nextFloat() * 40 - 20);
            OrientedBox a = new OrientedBox(origin, new Vector3f(1, 0.25f, 0.5f), frame);
            Quaternionf turned = new Quaternionf(frame).rotateY((float) (Math.PI / 2));
            for (float distance : new float[]{1.39f, 1.41f}) {
                Vector3f center = frame.transform(new Vector3f(distance, 0, 0)).add(origin);
                OrientedBox b = new OrientedBox(center, new Vector3f(0.3f, 0.1f, 0.4f), turned);
                assertEquals(distance < 1.4f, a.intersects(b, 0), "Incorrect rotated face separation");
                assertEquals(a.intersects(b, 0), b.intersects(a, 0), "Intersection must be symmetric");
            }
        }
    }

    @Test
    void separatedThinBoxesRemainSeparateEvenWhenTheirWorldBoundsOverlap() {
        Quaternionf rotation = new Quaternionf().rotationYXZ(0.7f, 0.23f, -0.31f);
        OrientedBox a = new OrientedBox(new Vector3f(), new Vector3f(1, 0.1f, 0.1f), rotation);
        OrientedBox b = new OrientedBox(rotation.transform(new Vector3f(0, 0, 0.22f)),
                new Vector3f(1, 0.1f, 0.1f), rotation);
        assertTrue(a.minX() < b.maxX() && a.maxX() > b.minX());
        assertTrue(a.minZ() < b.maxZ() && a.maxZ() > b.minZ());
        assertFalse(a.intersects(b, 0));
        assertTrue(a.intersects(b, -0.04f));
    }
}

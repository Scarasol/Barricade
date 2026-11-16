package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SpatialIndexTest {
    @Test
    void indexedIntersectionAndContainmentMatchBruteForce() {
        Random random = new Random(1318);
        List<OrientedBox> boxes = new ArrayList<>();
        SpatialIndex index = new SpatialIndex();
        for (int count = 0; count < 80; count++) {
            OrientedBox box = randomBox(random);
            boxes.add(box);
            index.add(box);
            if (count % 7 == 0) index.add(box);
        }
        for (int query = 0; query < 300; query++) {
            OrientedBox box = randomBox(random);
            for (float inset : new float[]{0, 0.008f, -0.045f, -0.3f})
                assertEquals(boxes.stream().anyMatch(other -> box.intersects(other, inset)),
                        index.intersects(box, inset), "Indexed broad phase missed an intersection");
            Vector3f point = box.point(0, 0, 0);
            assertEquals(boxes.stream().anyMatch(other -> other.contains(point.x, point.y, point.z)),
                    index.contains(point.x, point.y, point.z));
        }
    }

    @Test
    void nearbyQueriesDeduplicateByIdentityAndRetainBucketOrder() {
        SpatialIndex index = new SpatialIndex();
        OrientedBox left = OrientedBox.axisAligned(-0.7f, 0, 0, 0.1f, 1, 1);
        OrientedBox right = OrientedBox.axisAligned(0.3f, 0, 0, 1.1f, 1, 1);
        OrientedBox distinct = OrientedBox.axisAligned(-0.7f, 0, 0, 0.1f, 1, 1);
        index.add(right);
        index.add(left);
        index.add(left);
        index.add(distinct);
        for (int repetition = 0; repetition < 10; repetition++)
            assertEquals(List.of(left, distinct, right), index.nearby(0.5f, 0.5f, 0.5f, 1.1f));
        assertEquals(Set.of(left, distinct, right),
                new HashSet<>(index.nearby(0.5f, 0.5f, 0.5f, 1.1f)));
    }

    @Test
    void cacheInvalidationMatchesNearbyQueriesAtBucketBoundaries() {
        for (float start : new float[]{-2.01f, -2, -1.99f, -0.5f, 0, 0.49f, 0.5f, 1.99f, 2}) {
            OrientedBox box = OrientedBox.axisAligned(start, -0.5f, 0, start + 0.2f, 0.5f, 0.2f);
            SpatialIndex index = new SpatialIndex();
            index.add(box);
            for (int x = -5; x <= 5; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++) {
                GridPos cell = new GridPos(x, y, z);
                assertEquals(!index.nearby(x + 0.5f, y + 0.5f, z + 0.5f, 1.1f).isEmpty(),
                        SpatialIndex.affectsNearby(box, cell, 1.1f));
            }
        }
    }

    private static OrientedBox randomBox(Random random) {
        return new OrientedBox(new Vector3f(random.nextFloat() * 12 - 6,
                random.nextFloat() * 8 - 4, random.nextFloat() * 12 - 6),
                new Vector3f(0.05f + random.nextFloat(), 0.05f + random.nextFloat(), 0.05f + random.nextFloat()),
                new Quaternionf().rotationXYZ(random.nextFloat() * 6, random.nextFloat() * 6,
                        random.nextFloat() * 6));
    }
}

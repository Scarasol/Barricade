package com.scarasol.barricade.pile;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class SpatialIndex {
    private final CellMap<List<Entry>> buckets = new CellMap<>();
    private final Map<OrientedBox, Entry> entries = new IdentityHashMap<>();
    private long query;
    private static final class Entry {
        final OrientedBox box;
        long visited;
        Entry(OrientedBox box) { this.box = box; }
    }

    public void add(OrientedBox box) {
        Entry entry = entries.computeIfAbsent(box, Entry::new);
        for (int x = bucket(box.minX()); x <= bucket(box.maxX()); x++) {
            for (int y = bucket(box.minY()); y <= bucket(box.maxY()); y++) {
                for (int z = bucket(box.minZ()); z <= bucket(box.maxZ()); z++) {
                    List<Entry> group = buckets.get(x, y, z);
                    if (group == null) {
                        group = new ArrayList<>();
                        buckets.put(x, y, z, group);
                    }
                    group.add(entry);
                }
            }
        }
    }

    public boolean intersects(OrientedBox box, float inset) {
        long stamp = nextQuery();
        float margin = Math.max(0, -inset) * 1.733f;
        for (int x = bucket(box.minX() - margin); x <= bucket(box.maxX() + margin); x++) {
            for (int y = bucket(box.minY() - margin); y <= bucket(box.maxY() + margin); y++) {
                for (int z = bucket(box.minZ() - margin); z <= bucket(box.maxZ() + margin); z++) {
                    List<Entry> group = buckets.get(x, y, z);
                    if (group == null) continue;
                    for (int index = 0; index < group.size(); index++) {
                        Entry other = group.get(index);
                        if (other.visited == stamp) continue;
                        other.visited = stamp;
                        if (box.intersects(other.box, inset)) return true;
                    }
                }
            }
        }
        return false;
    }

    public List<OrientedBox> nearby(float x, float y, float z, float radius) {
        List<OrientedBox> result = new ArrayList<>();
        long stamp = nextQuery();
        for (int bx = bucket(x - radius); bx <= bucket(x + radius); bx++) {
            for (int by = bucket(y - radius); by <= bucket(y + radius); by++) {
                for (int bz = bucket(z - radius); bz <= bucket(z + radius); bz++) {
                    List<Entry> group = buckets.get(bx, by, bz);
                    if (group == null) continue;
                    for (int index = 0; index < group.size(); index++) {
                        Entry entry = group.get(index);
                        if (entry.visited == stamp) continue;
                        entry.visited = stamp;
                        result.add(entry.box);
                    }
                }
            }
        }
        return result;
    }

    public boolean contains(float x, float y, float z) {
        List<Entry> group = buckets.get(bucket(x), bucket(y), bucket(z));
        if (group != null) for (int index = 0; index < group.size(); index++)
            if (group.get(index).box.contains(x, y, z)) return true;
        return false;
    }

    private long nextQuery() {
        if (++query == 0) {
            entries.values().forEach(entry -> entry.visited = 0);
            query = 1;
        }
        return query;
    }

    static boolean affectsNearby(OrientedBox box, GridPos cell, float radius) {
        return bucket(box.minX()) <= bucket(cell.x() + 0.5f + radius)
                && bucket(box.maxX()) >= bucket(cell.x() + 0.5f - radius)
                && bucket(box.minY()) <= bucket(cell.y() + 0.5f + radius)
                && bucket(box.maxY()) >= bucket(cell.y() + 0.5f - radius)
                && bucket(box.minZ()) <= bucket(cell.z() + 0.5f + radius)
                && bucket(box.maxZ()) >= bucket(cell.z() + 0.5f - radius);
    }

    private static int bucket(float coordinate) {
        return (int) Math.floor(coordinate * 2);
    }
}

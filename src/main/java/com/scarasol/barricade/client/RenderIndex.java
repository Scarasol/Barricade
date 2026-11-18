package com.scarasol.barricade.client;

import java.util.*;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Indexes actual transformed extents, including objects whose owner is outside view. */
final class RenderIndex<T> {
    private final Map<Long, Set<T>> sections = new HashMap<>();
    private final Map<T, AABB> bounds = new HashMap<>();
    void put(T item, AABB box) {
        remove(item);
        bounds.put(item, box);
        sections(box).forEach(section -> sections.computeIfAbsent(section, key -> new HashSet<>()).add(item));
    }
    void remove(T item) {
        AABB box = bounds.remove(item);
        if (box == null) return;
        for (long key : sections(box)) {
            Set<T> bucket = sections.get(key);
            bucket.remove(item);
            if (bucket.isEmpty()) sections.remove(key);
        }
    }
    Set<T> near(Vec3 camera, double distance) {
        return inSections(new AABB(camera, camera).inflate(distance));
    }
    Set<T> intersecting(AABB box) {
        Set<T> result = inSections(box);
        if (!result.isEmpty()) result.removeIf(item -> !bounds.get(item).intersects(box));
        return result;
    }
    private Set<T> inSections(AABB box) {
        if (sections.isEmpty()) return Set.of();
        Set<T> result = new HashSet<>();
        for (long key : sections(box)) {
            var bucket = sections.get(key);
            if (bucket != null) result.addAll(bucket);
        }
        return result;
    }
    void clear() { sections.clear(); bounds.clear(); }
    static List<Long> sections(AABB box) {
        List<Long> result = new ArrayList<>();
        for (int x = SectionPos.blockToSectionCoord(box.minX); x <= SectionPos.blockToSectionCoord(box.maxX); x++)
            for (int y = SectionPos.blockToSectionCoord(box.minY); y <= SectionPos.blockToSectionCoord(box.maxY); y++)
                for (int z = SectionPos.blockToSectionCoord(box.minZ); z <= SectionPos.blockToSectionCoord(box.maxZ); z++)
                    result.add(SectionPos.asLong(x, y, z));
        return result;
    }
}

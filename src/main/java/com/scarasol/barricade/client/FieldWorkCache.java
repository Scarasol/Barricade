package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.SpatialField;
import java.util.*;
import java.util.function.Supplier;

/** Worker-owned solver LRU. Display objects live separately and survive solver eviction. */
final class FieldWorkCache<K> {
    private final int maximumFields, maximumSlots;
    private final Map<K, SpatialField> fields = new LinkedHashMap<>(16, .75f, true);
    private SpatialField active;
    FieldWorkCache(int maximumFields, int maximumSlots) {
        this.maximumFields = maximumFields; this.maximumSlots = maximumSlots;
    }
    SpatialField acquire(K key, Supplier<SpatialField> factory) {
        SpatialField field = fields.computeIfAbsent(key, ignored -> factory.get());
        if (active != field) { idle(); active = field; }
        return field;
    }
    void idle() {
        if (active != null) active.releasePreparationCache();
        active = null;
    }
    void retain(Set<K> live) {
        fields.keySet().retainAll(live);
        if (active != null && !fields.containsValue(active)) idle();
    }
    void trim(K keep) {
        int slots = cachedSlots();
        var iterator = fields.entrySet().iterator();
        while (iterator.hasNext() && (fields.size() > maximumFields || slots > maximumSlots)) {
            var entry = iterator.next();
            if (entry.getKey().equals(keep)) continue;
            slots -= entry.getValue().cachedSlots(); iterator.remove();
        }
        // The current definition is kept even if its own complete answer set exceeds the soft budget.
    }
    int size() { return fields.size(); }
    int cachedSlots() { return fields.values().stream().mapToInt(SpatialField::cachedSlots).sum(); }
    int cachedPoses() { return fields.values().stream().mapToInt(field -> field.metrics().cachedPoses()).sum(); }
}

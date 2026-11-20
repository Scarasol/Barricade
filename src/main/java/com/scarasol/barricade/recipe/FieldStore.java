package com.scarasol.barricade.recipe;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Atomic current-state revision stream. A missed baseline is an error, not a replay request. */
public final class FieldStore {
    private Map<UUID, FieldRegion> regions = Map.of();
    private Map<String, PaletteSpec> palettes = Map.of();
    private Map<BlockPos, EmbeddedBlock> embedded = Map.of();
    private long revision = -1, samples;
    public Map<UUID, FieldRegion> regions() { return regions; }
    public PaletteSpec palette(String id) { return palettes.get(id); }
    public EmbeddedBlock embeddedAt(BlockPos pos) { return embedded.get(pos); }
    public long samples() { return samples; }
    public long revision() { return revision; }
    public void accept(CompoundTag payload) {
        if (payload.getInt("Format") != 2) throw new IllegalArgumentException("Unsupported field wire format");
        long base = payload.getLong("Base"), next = payload.getLong("Revision");
        if (next <= revision) return;
        if (base != -1 && base != revision) throw new IllegalArgumentException("Missing field baseline");
        Map<UUID, FieldRegion> groups = base == -1 ? new HashMap<>() : new HashMap<>(regions);
        Map<String, PaletteSpec> rules = base == -1 ? new HashMap<>() : new HashMap<>(palettes);
        for (Tag value : payload.getList("Palettes", Tag.TAG_COMPOUND)) {
            PaletteSpec palette = PaletteSpec.load((CompoundTag) value);
            PaletteSpec old = rules.putIfAbsent(palette.id(), palette);
            if (old != null && !old.equals(palette)) throw new IllegalArgumentException("Conflicting palette");
        }
        for (Tag value : payload.getList("Removed", Tag.TAG_COMPOUND))
            groups.remove(((CompoundTag) value).getUUID("Id"));
        for (Tag value : payload.getList("Regions", Tag.TAG_COMPOUND)) {
            FieldRegion region = FieldRegion.load((CompoundTag) value);
            FieldRegion old = groups.get(region.id());
            if (old != null && region.revision() < old.revision())
                throw new IllegalArgumentException("Stale region revision");
            groups.put(region.id(), region);
        }
        Set<String> live = new HashSet<>();
        Set<BlockPos> cells = new HashSet<>();
        Map<BlockPos, EmbeddedBlock> embeddedBlocks = new HashMap<>();
        for (FieldRegion region : groups.values()) {
            if (!rules.containsKey(region.definition().palette())) throw new IllegalArgumentException("Missing field palette");
            for (var pos : region.domain()) if (!cells.add(pos)) throw new IllegalArgumentException("Overlapping field regions");
            embeddedBlocks.putAll(region.embedded());
            live.add(region.definition().palette());
        }
        rules.keySet().retainAll(live);
        regions = Map.copyOf(groups); palettes = Map.copyOf(rules); embedded = Map.copyOf(embeddedBlocks);
        revision = next; samples = payload.getLong("Samples");
    }
}

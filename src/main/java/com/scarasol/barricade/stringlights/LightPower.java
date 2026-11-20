package com.scarasol.barricade.stringlights;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Runtime demand only; the box entity owns all usable energy. */
final class LightPower {
    private final Map<Long, Boolean> lampChunks = new HashMap<>();
    private final Map<BlockPos, Source> sources = new HashMap<>();
    private final Map<LightSpatial.Lamp, BlockPos> owners = new HashMap<>();
    private final Consumer<BlockPos> relight;

    LightPower(Consumer<BlockPos> relight) { this.relight = relight; }

    void watch(long chunk) { lampChunks.putIfAbsent(chunk, false); }

    void unload(long chunk, LightSpatial.Section section) {
        if (lampChunks.remove(chunk) != null && section != null) {
            for (var lamp : section.lamps) track(lamp, false);
        }
    }

    void track(LightSpatial.Lamp lamp) {
        track(lamp, lampChunks.getOrDefault(LightSpatial.chunk(lamp.bulb().cell()), false));
    }

    private void track(LightSpatial.Lamp lamp, boolean active) {
        BlockPos next = active && !lamp.broken() ? lamp.source() : null;
        BlockPos old = owners.get(lamp);
        if (java.util.Objects.equals(old, next)) return;
        if (old != null) {
            Source source = sources.get(old);
            if (source != null) source.lamps.remove(lamp);
            owners.remove(lamp);
        }
        if (next != null) {
            sources.computeIfAbsent(next, ignored -> new Source()).lamps.add(lamp);
            owners.put(lamp, next);
        }
        relight.accept(lamp.bulb().cell());
    }

    void remove(LightSpatial.Lamp lamp) { track(lamp, false); }

    void dropBox(BlockPos pos) {
        Source source = sources.remove(pos);
        if (source == null) return;
        source.lamps.forEach(lamp -> {
            owners.remove(lamp);
            relight.accept(lamp.bulb().cell());
        });
    }

    void stop(BlockPos pos) {
        Source source = sources.get(pos);
        if (source != null && source.supplying) {
            source.supplying = false;
            source.lamps.forEach(lamp -> relight.accept(lamp.bulb().cell()));
        }
    }

    boolean lit(LightSpatial.Lamp lamp) {
        Source source = sources.get(owners.get(lamp));
        return source != null && source.supplying;
    }

    void tick(ServerLevel level, LightSpatial spatial, Map<BlockPos, PowerBoxEntity> boxes) {
        var chunks = lampChunks.entrySet().iterator();
        while (chunks.hasNext()) {
            var entry = chunks.next();
            LightSpatial.Section section = spatial.sections.get(entry.getKey());
            if (section == null || section.lamps.isEmpty()) {
                chunks.remove();
                continue;
            }
            boolean active = level.shouldTickBlocksAt(entry.getKey());
            if (active != entry.getValue()) {
                for (var lamp : section.lamps) track(lamp, active);
                entry.setValue(active);
            }
        }
        for (var entry : boxes.entrySet()) {
            BlockPos pos = entry.getKey();
            PowerBoxEntity box = entry.getValue();
            Source source = sources.get(pos);
            if (!level.shouldTickBlocksAt(ChunkPos.asLong(pos))) {
                box.stop();
                stop(pos);
            } else {
                box.settle(source == null ? 0 : source.lamps.size());
                if (source != null && box.supplying() != source.supplying) {
                    source.supplying = box.supplying();
                    for (var lamp : source.lamps) relight.accept(lamp.bulb().cell());
                }
            }
            if (level.getGameTime() % 5 == 0) box.syncStatus();
        }
    }

    private static final class Source {
        final Set<LightSpatial.Lamp> lamps = new HashSet<>();
        boolean supplying;
    }
}

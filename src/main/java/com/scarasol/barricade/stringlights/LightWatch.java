package com.scarasol.barricade.stringlights;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

final class LightWatch {
    private final Map<Long, Set<ServerPlayer>> watchers = new HashMap<>();
    private final Map<ServerPlayer, Set<Long>> watched = new HashMap<>();

    void add(ServerPlayer player, long chunk) {
        watchers.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(player);
        watched.computeIfAbsent(player, ignored -> new HashSet<>()).add(chunk);
    }

    void remove(ServerPlayer player, long chunk) {
        var players = watchers.get(chunk);
        if (players != null && players.remove(player) && players.isEmpty()) watchers.remove(chunk);
        var chunks = watched.get(player);
        if (chunks != null && chunks.remove(chunk) && chunks.isEmpty()) watched.remove(player);
    }

    void remove(ServerPlayer player) {
        Set<Long> chunks = watched.remove(player);
        if (chunks == null) return;
        for (long chunk : chunks) {
            Set<ServerPlayer> players = watchers.get(chunk);
            players.remove(player);
            if (players.isEmpty()) watchers.remove(chunk);
        }
    }

    void send(LightWorld world, ServerPlayer player, long chunk) {
        var data = world.spatial.sections.get(chunk);
        if (data != null) LightNetwork.send(player, world.level, chunk, LightView.write(world, data));
    }

    void flush(LightWorld world, Set<Long> chunks) {
        for (long chunk : chunks) {
            var players = watchers.get(chunk);
            if (players == null) continue;
            var data = world.spatial.sections.get(chunk);
            var snapshot = data == null ? null : LightView.write(world, data);
            for (var player : players) LightNetwork.send(player, world.level, chunk, snapshot);
        }
    }

    void light(LightWorld world, Set<Long> structure, Map<Long, Set<LightGraph.Span>> changes) {
        for (var entry : changes.entrySet()) {
            long chunk = entry.getKey();
            var players = watchers.get(chunk);
            if (players == null || structure.contains(chunk)) continue;
            var list = new net.minecraft.nbt.ListTag();
            for (var span : entry.getValue()) {
                if (world.graph.span(span.id) != span) continue;
                var tag = new net.minecraft.nbt.CompoundTag();
                tag.putUUID("id", span.id);
                tag.putInt("lit", LightView.lightMask(world, span));
                list.add(tag);
            }
            var root = new net.minecraft.nbt.CompoundTag();
            root.put("lights", list);
            for (var player : players) LightNetwork.send(player, world.level, chunk, root);
        }
    }
}

package com.scarasol.barricade.window.client;

import com.scarasol.barricade.window.BoardCell;
import com.scarasol.barricade.window.BoardGroup;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Event-driven visibility masks, indexed only by chunks that contain group members. */
final class WindowBoardsVisibility {
    private final Map<Long, Set<State>> chunks = new HashMap<>();
    private final Set<State> states = new HashSet<>();

    static final class State {
        private BoardGroup group;
        private final Map<BoardCell, BlockPos> positions = new HashMap<>();
        private final Set<Long> chunks = new HashSet<>();
        private Set<BoardCell> hidden;
        private boolean dirty = true;

        State(BoardGroup group) {
            this.group = group;
            for (BoardCell cell : group.shape()) {
                BlockPos pos = group.plane().block(cell);
                positions.put(cell, pos);
                chunks.add(ChunkPos.asLong(pos));
            }
        }
        void update(BoardGroup group) {
            if (!this.group.damage().equals(group.damage())) dirty = true;
            this.group = group;
        }
        boolean dirty() { return dirty; }
        Set<BoardCell> hidden(Predicate<BlockPos> present) {
            if (!dirty) return hidden;
            Set<BoardCell> next = new HashSet<>(group.damage());
            positions.forEach((cell, pos) -> {
                if (!next.contains(cell) && !present.test(pos)) next.add(cell);
            });
            if (hidden == null || !hidden.equals(next)) hidden = Set.copyOf(next);
            dirty = false;
            return hidden;
        }
    }
    State track(BoardGroup group) {
        State state = new State(group);
        states.add(state);
        for (long chunk : state.chunks) chunks.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(state);
        return state;
    }
    void untrack(State state) {
        states.remove(state);
        for (long chunk : state.chunks) {
            Set<State> members = chunks.get(chunk);
            members.remove(state);
            if (members.isEmpty()) chunks.remove(chunk);
        }
    }
    void blockChanged(BlockPos pos) {
        Set<State> members = chunks.get(ChunkPos.asLong(pos));
        if (members == null) return;
        for (State state : members) {
            var plane = state.group.plane();
            if (pos.get(plane.facing().getAxis()) == plane.coordinate()
                    && state.group.shape().contains(plane.cell(pos))) state.dirty = true;
        }
    }
    void chunkChanged(ChunkPos chunk) {
        Set<State> members = chunks.get(chunk.toLong());
        if (members != null) members.forEach(state -> state.dirty = true);
    }
    void invalidateAll() { states.forEach(state -> state.dirty = true); }
    void clear() {
        chunks.clear();
        states.clear();
    }
}

package com.scarasol.barricade.stringlights;

import java.util.UUID;
import com.scarasol.barricade.Barricade;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

final class LightData extends SavedData {
    private static final String KEY = "barricade_string_lights";
    final LightGraph graph = new LightGraph();

    static LightData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(tag -> {
            try {
                return load(tag);
            } catch (RuntimeException error) {
                Barricade.LOGGER.warn("Discarding unreadable string-light layout in {}", level.dimension().location(), error);
                return fresh();
            }
        }, LightData::fresh, KEY);
    }

    private static LightData fresh() {
        var data = new LightData();
        data.setDirty();
        return data;
    }

    static LightData load(CompoundTag root) {
        if (root.getInt("version") != 1 || !root.contains("nodes", Tag.TAG_LIST)
                || !root.contains("spans", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Unreadable string-light store");
        }
        var data = new LightData();
        for (Tag entry : entries(root, "nodes")) {
            CompoundTag tag = (CompoundTag) entry;
            LightGraph.Node node = new LightGraph.Node(tag.getUUID("id"), readPoint(tag, "p"),
                    BlockPos.of(tag.getLong("surface")), Direction.valueOf(tag.getString("face")),
                    tag.getBoolean("outlet"));
            data.graph.add(node);
            if (tag.contains("box") && !data.graph.attach(BlockPos.of(tag.getLong("box")), node)) {
                throw new IllegalArgumentException("Duplicate light box");
            }
        }
        for (Tag entry : entries(root, "spans")) {
            CompoundTag tag = (CompoundTag) entry;
            LightGraph.Node a = data.graph.node(tag.getUUID("a"));
            LightGraph.Node b = data.graph.node(tag.getUUID("b"));
            if (a == null || b == null) throw new IllegalArgumentException("Missing light span endpoint");
            LightCurve curve = new LightCurve(a.anchor, b.anchor);
            int broken = tag.getInt("broken");
            if (broken >>> curve.bulbs().size() != 0) throw new IllegalArgumentException("Invalid light damage mask");
            data.graph.join(new LightGraph.Span(tag.getUUID("id"), a, b, curve, broken));
        }
        return data;
    }

    private static ListTag entries(CompoundTag root, String key) {
        ListTag list = (ListTag) root.get(key);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid light list: " + key);
        }
        return list;
    }

    @Override public CompoundTag save(CompoundTag root) {
        var nodes = new ListTag();
        for (LightGraph.Node node : graph.nodes()) {
            var tag = new CompoundTag();
            tag.putUUID("id", node.id);
            writePoint(tag, "p", node.anchor);
            tag.putLong("surface", node.surface.asLong());
            tag.putString("face", node.face.name());
            tag.putBoolean("outlet", node.outlet);
            if (node.box() != null) tag.putLong("box", node.box().asLong());
            nodes.add(tag);
        }
        var spans = new ListTag();
        for (LightGraph.Span span : graph.spans()) {
            var tag = new CompoundTag();
            tag.putUUID("id", span.id);
            tag.putUUID("a", span.a.id);
            tag.putUUID("b", span.b.id);
            tag.putInt("broken", span.broken());
            spans.add(tag);
        }
        root.putInt("version", 1);
        root.put("nodes", nodes);
        root.put("spans", spans);
        return root;
    }

    static void writePoint(CompoundTag tag, String key, Vec3 point) {
        tag.putDouble(key + "x", point.x);
        tag.putDouble(key + "y", point.y);
        tag.putDouble(key + "z", point.z);
    }

    static Vec3 readPoint(CompoundTag tag, String key) {
        if (!tag.contains(key + "x", Tag.TAG_DOUBLE) || !tag.contains(key + "y", Tag.TAG_DOUBLE)
                || !tag.contains(key + "z", Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Missing light point");
        double x = tag.getDouble(key + "x"), y = tag.getDouble(key + "y"), z = tag.getDouble(key + "z");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Invalid light point");
        }
        return new Vec3(x, y, z);
    }
}

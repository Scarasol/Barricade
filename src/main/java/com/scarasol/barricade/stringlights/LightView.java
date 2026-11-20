package com.scarasol.barricade.stringlights;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** Immutable client snapshot, indexed by watched chunk. No balances or mutable graph internals. */
public record LightView(Map<UUID, Node> nodes, Map<UUID, Span> spans) {
    public record Node(UUID id, Vec3 anchor, BlockPos surface, Direction face, boolean outlet,
                       boolean supported, BlockPos box, BlockPos source) {
        public Vec3 connection() { return box == null ? anchor : PowerBoxBlock.outlet(box, face); }
    }
    public record Span(UUID id, LightCable cable, int broken, int lit) {
        public Span(UUID id, LightCurve curve, int broken, int lit) {
            this(id, new LightCable(curve, curve.start(), curve.end()), broken, lit);
        }

        public LightCurve curve() { return cable.curve(); }
        public boolean broken(int index) { return (broken & (1 << index)) != 0; }
        public boolean lit(int index) { return (lit & (1 << index)) != 0; }
    }

    public static LightView read(CompoundTag root, java.util.function.BiFunction<UUID, CompoundTag, LightCurve> curves) {
        var nodes = new java.util.HashMap<UUID, Node>();
        for (Tag entry : root.getList("nodes", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            UUID id = tag.getUUID("id");
            nodes.put(id, new Node(id, LightData.readPoint(tag, "p"), BlockPos.of(tag.getLong("surface")),
                    Direction.valueOf(tag.getString("face")), tag.getBoolean("outlet"), tag.getBoolean("supported"),
                    tag.contains("box") ? BlockPos.of(tag.getLong("box")) : null,
                    tag.contains("source") ? BlockPos.of(tag.getLong("source")) : null));
        }
        var spans = new java.util.HashMap<UUID, Span>();
        for (Tag entry : root.getList("spans", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            UUID id = tag.getUUID("id");
            LightCurve curve = curves.apply(id, tag);
            Vec3 start = tag.contains("sx") ? LightData.readPoint(tag, "s") : curve.start();
            Vec3 end = tag.contains("ex") ? LightData.readPoint(tag, "e") : curve.end();
            spans.put(id, new Span(id, new LightCable(curve, start, end), tag.getInt("broken"), tag.getInt("lit")));
        }
        return new LightView(Map.copyOf(nodes), Map.copyOf(spans));
    }

    public static LightCurve readCurve(CompoundTag tag) {
        return new LightCurve(LightData.readPoint(tag, "a"), LightData.readPoint(tag, "b"));
    }

    static CompoundTag write(LightWorld world, LightSpatial.Section section) {
        var nodes = new ListTag();
        for (var node : section.nodes) {
            var tag = new CompoundTag();
            tag.putUUID("id", node.id);
            LightData.writePoint(tag, "p", node.anchor);
            tag.putLong("surface", node.surface.asLong());
            tag.putString("face", node.face.name());
            tag.putBoolean("outlet", node.outlet);
            tag.putBoolean("supported", world.supported(node));
            if (node.box() != null) tag.putLong("box", node.box().asLong());
            if (node.source() != null) tag.putLong("source", node.source().asLong());
            nodes.add(tag);
        }
        var spans = new ListTag();
        for (var span : section.spans) {
            var tag = new CompoundTag();
            tag.putUUID("id", span.id);
            LightData.writePoint(tag, "a", span.curve.start());
            LightData.writePoint(tag, "b", span.curve.end());
            LightCable cable = span.cable();
            if (!cable.start().equals(span.curve.start())) LightData.writePoint(tag, "s", cable.start());
            if (!cable.end().equals(span.curve.end())) LightData.writePoint(tag, "e", cable.end());
            tag.putInt("broken", span.broken());
            tag.putInt("lit", lightMask(world, span));
            spans.add(tag);
        }
        var root = new CompoundTag();
        root.put("nodes", nodes);
        root.put("spans", spans);
        return root;
    }

    static int lightMask(LightWorld world, LightGraph.Span span) {
        int lit = 0;
        for (int i = 0; i < span.curve.bulbs().size(); i++) {
            if (world.isLit(new LightSpatial.Lamp(span, i))) lit |= 1 << i;
        }
        return lit;
    }

    public LightView relight(CompoundTag payload) {
        var changed = new java.util.HashMap<>(spans);
        for (Tag entry : payload.getList("lights", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            UUID id = tag.getUUID("id");
            Span current = changed.get(id);
            if (current != null) changed.put(id, new Span(id, current.cable, current.broken, tag.getInt("lit")));
        }
        return new LightView(nodes, Map.copyOf(changed));
    }
}

package com.scarasol.barricade.stringlights;

import com.scarasol.barricade.Barricade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/** Structure-local copies of the inputs needed to rebuild an installation. */
public final class LightStructures {
    private static final String KEY = "barricade:string_lights";

    private LightStructures() {}

    public static BlockState templateState(BlockState state) {
        if (!state.is(StringLights.BULB.get())) return state;
        return (state.getValue(BulbBlock.WATER) ? Blocks.WATER : Blocks.AIR).defaultBlockState();
    }

    public static void write(CompoundTag root, CompoundTag layout) {
        if (layout == null) root.remove(KEY);
        else root.put(KEY, layout.copy());
    }

    public static CompoundTag read(CompoundTag root) {
        return root.contains(KEY, Tag.TAG_COMPOUND) ? root.getCompound(KEY).copy() : null;
    }

    public static CompoundTag capture(ServerLevel level, BlockPos origin, Vec3i size, Block ignored) {
        LightWorld world = LightWorld.current(level);
        if (world == null) return null;
        var end = origin.offset(size).offset(-1, -1, -1);
        var bounds = BoundingBox.fromCorners(origin, end);
        var spans = new HashSet<LightGraph.Span>();
        for (int x = origin.getX() >> 4; x <= end.getX() >> 4; x++) {
            for (int z = origin.getZ() >> 4; z <= end.getZ() >> 4; z++) {
                var section = world.spatial.sections.get(ChunkPos.asLong(x, z));
                if (section != null) spans.addAll(section.spans);
            }
        }
        var nodes = new ListTag();
        var edges = new ListTag();
        var indices = new HashMap<LightGraph.Node, Integer>();
        for (var span : spans) {
            if (!inside(span.a, bounds) || !inside(span.b, bounds)
                    || span.curve.pieces().stream().anyMatch(piece -> !bounds.isInside(piece.cell()))) continue;
            var edge = new CompoundTag();
            edge.putInt("a", nodeIndex(span.a, nodes, indices, origin, ignored));
            edge.putInt("b", nodeIndex(span.b, nodes, indices, origin, ignored));
            int count = span.curve.bulbs().size();
            int damage = span.curve.start().equals(span.a.anchor) ? span.broken() : reverse(span.broken(), count);
            edge.putInt("broken", damage);
            edges.add(edge);
        }
        if (edges.isEmpty()) return null;
        var layout = new CompoundTag();
        layout.put("nodes", nodes);
        layout.put("spans", edges);
        return layout;
    }

    private static boolean inside(LightGraph.Node node, BoundingBox bounds) {
        return bounds.isInside(node.surface) && bounds.isInside(BlockPos.containing(node.anchor))
                && (node.box() == null || bounds.isInside(node.box()));
    }

    private static int nodeIndex(LightGraph.Node node, ListTag nodes, Map<LightGraph.Node, Integer> indices,
                                 BlockPos origin, Block ignored) {
        return indices.computeIfAbsent(node, key -> {
            var tag = new CompoundTag();
            LightData.writePoint(tag, "p", node.anchor.subtract(Vec3.atLowerCornerOf(origin)));
            tag.putLong("surface", node.surface.subtract(origin).asLong());
            tag.putString("face", node.face.name());
            tag.putBoolean("outlet", node.outlet);
            if (node.box() != null && ignored != StringLights.BOX.get()) tag.putLong("box", node.box().subtract(origin).asLong());
            int index = nodes.size();
            nodes.add(tag);
            return index;
        });
    }

    public static void place(ServerLevel level, BlockPos origin, StructurePlaceSettings settings,
                             BoundingBox bounds, CompoundTag layout) {
        // Filtered placements (including integrity below 100%) do not represent a complete copy.
        if (settings.getBoundingBox() != null || !settings.getProcessors().isEmpty()) return;
        LightGraph copy;
        try {
            copy = decode(level, origin, settings, bounds, layout);
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            Barricade.LOGGER.warn("Ignoring unreadable string lights in structure at {}", origin, error);
            return;
        }
        LightWorld.get(level).placeStructure(copy);
    }

    private static LightGraph decode(ServerLevel level, BlockPos origin, StructurePlaceSettings settings,
                                     BoundingBox bounds, CompoundTag layout) {
        var nodes = new ArrayList<LightGraph.Node>();
        var graph = new LightGraph();
        for (Tag entry : layout.getList("nodes", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) entry;
            var surface = transformed(settings, origin, tag.getLong("surface"));
            var face = settings.getRotation().rotate(settings.getMirror().mirror(Direction.valueOf(tag.getString("face"))));
            var anchor = StructureTemplate.transformedVec3d(settings, LightData.readPoint(tag, "p")).add(Vec3.atLowerCornerOf(origin));
            var node = new LightGraph.Node(UUID.randomUUID(), anchor, surface, face, tag.getBoolean("outlet"));
            if (!inside(node, bounds)) throw new IllegalArgumentException("Structure light node outside template");
            graph.add(node);
            nodes.add(node);
            if (tag.contains("box", Tag.TAG_LONG)) {
                var box = transformed(settings, origin, tag.getLong("box"));
                if (!bounds.isInside(box) || !(node.outlet ? surface : surface.relative(face)).equals(box)) {
                    throw new IllegalArgumentException("Invalid structure light box position");
                }
                if (level.getBlockEntity(box) instanceof PowerBoxEntity entity
                        && entity.getBlockState().getValue(PowerBoxBlock.FACE) == face && !graph.attach(box, node)) {
                    throw new IllegalArgumentException("Invalid structure light box");
                }
            }
        }
        for (Tag entry : layout.getList("spans", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) entry;
            var a = nodes.get(tag.getInt("a"));
            var b = nodes.get(tag.getInt("b"));
            var curve = new LightCurve(a.anchor, b.anchor);
            if (curve.pieces().stream().anyMatch(piece -> !bounds.isInside(piece.cell()))) {
                throw new IllegalArgumentException("Structure light span outside template");
            }
            int count = curve.bulbs().size();
            int damage = tag.getInt("broken");
            if (damage >>> count != 0) throw new IllegalArgumentException("Invalid structure light damage");
            if (!curve.start().equals(a.anchor)) damage = reverse(damage, count);
            graph.join(new LightGraph.Span(UUID.randomUUID(), a, b, curve, damage));
        }
        return graph;
    }

    private static BlockPos transformed(StructurePlaceSettings settings, BlockPos origin, long position) {
        return StructureTemplate.calculateRelativePosition(settings, BlockPos.of(position)).offset(origin);
    }

    private static int reverse(int damage, int count) { return Integer.reverse(damage) >>> (32 - count); }
}

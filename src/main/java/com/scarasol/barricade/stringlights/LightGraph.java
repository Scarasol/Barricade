package com.scarasol.barricade.stringlights;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Installation topology. Components are derived; only nodes, edges and box connections persist. */
final class LightGraph {
    private final Map<UUID, Node> nodes = new HashMap<>();
    private final Map<UUID, Span> spans = new HashMap<>();
    private final Map<BlockPos, Node> boxes = new HashMap<>();

    Node node(UUID id) { return nodes.get(id); }
    Collection<Node> nodes() { return nodes.values(); }
    Collection<Span> spans() { return spans.values(); }
    Node box(BlockPos pos) { return boxes.get(pos); }
    Span span(UUID id) { return spans.get(id); }

    void addStructure(LightGraph copy) {
        nodes.putAll(copy.nodes);
        spans.putAll(copy.spans);
        boxes.putAll(copy.boxes);
    }

    void add(Node node) {
        if (nodes.putIfAbsent(node.id, node) != null) throw new IllegalArgumentException("Duplicate light node");
    }

    boolean canJoin(Node a, Node b) {
        if (a == b) return false;
        if (a.component != b.component && a.source() != null && b.source() != null) return false;
        for (Span edge : a.edges) if (edge.other(a) == b) return false;
        return true;
    }

    void join(Span span) {
        Node a = span.a, b = span.b;
        if (!canJoin(a, b) || spans.putIfAbsent(span.id, span) != null) {
            throw new IllegalArgumentException("Invalid light span");
        }
        a.edges.add(span);
        b.edges.add(span);
        if (a.component == b.component) return;
        Component large = a.component, small = b.component;
        if (large.nodes.size() < small.nodes.size()) {
            large = b.component;
            small = a.component;
        }
        if (large.source == null) large.source = small.source;
        for (Node node : small.nodes) node.component = large;
        large.nodes.addAll(small.nodes);
    }

    boolean attach(BlockPos box, Node node) {
        if (node.source() != null || boxes.putIfAbsent(box, node) != null) return false;
        node.box = box.immutable();
        node.component.source = node.box;
        return true;
    }

    Node detach(BlockPos box) {
        Node node = boxes.remove(box);
        if (node != null) {
            node.box = null;
            node.component.source = null;
            prune(node);
        }
        return node;
    }

    boolean remove(Span span) {
        if (!spans.remove(span.id, span)) return false;
        Node a = span.a, b = span.b;
        a.edges.remove(span);
        b.edges.remove(span);
        Component existing = a.component;
        Set<Node> reachable = new HashSet<>();
        var queue = new ArrayDeque<Node>();
        queue.add(a);
        reachable.add(a);
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            for (Span remaining : node.edges) {
                Node next = remaining.other(node);
                if (next == b) {
                    prune(a);
                    prune(b);
                    return true;
                }
                if (reachable.add(next)) queue.addLast(next);
            }
        }
        Component split = new Component(reachable);
        for (Node node : reachable) {
            node.component = split;
            if (node.box != null) {
                split.source = node.box;
                existing.source = null;
            }
        }
        existing.nodes.removeAll(reachable);
        prune(a);
        prune(b);
        return true;
    }

    private void prune(Node node) {
        if (node.box != null || !node.edges.isEmpty()) return;
        node.component.nodes.remove(node);
        nodes.remove(node.id);
    }

    private static final class Component {
        final Set<Node> nodes;
        BlockPos source;
        Component(Set<Node> nodes) { this.nodes = nodes; }
    }

    static final class Node {
        final UUID id;
        final Vec3 anchor;
        final BlockPos surface;
        final Direction face;
        final boolean outlet;
        final Set<Span> edges = new HashSet<>();
        private Component component = new Component(new HashSet<>());
        private BlockPos box;

        Node(UUID id, Vec3 anchor, BlockPos surface, Direction face, boolean outlet) {
            this.id = id;
            this.anchor = anchor;
            this.surface = surface;
            this.face = face;
            this.outlet = outlet;
            component.nodes.add(this);
        }

        @Nullable BlockPos source() { return component.source; }
        @Nullable BlockPos box() { return box; }
        Vec3 connection() { return box == null ? anchor : PowerBoxBlock.outlet(box, face); }
        Collection<Node> component() { return component.nodes; }
    }

    static final class Span {
        final UUID id;
        final Node a;
        final Node b;
        final LightCurve curve;
        private int broken;

        Span(UUID id, Node a, Node b, LightCurve curve, int broken) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.curve = curve;
            this.broken = broken;
        }

        Node other(Node node) { return node == a ? b : a; }
        LightCable cable() { return LightCable.between(curve, a.anchor, a.connection(), b.connection()); }
        boolean broken(int bulb) { return (broken & (1 << bulb)) != 0; }
        int broken() { return broken; }
        void damage(int bulb) { broken |= 1 << bulb; }
        void repair(int bulb) { broken &= ~(1 << bulb); }
        int refund() { return Math.max(0, curve.cost() - Integer.bitCount(broken)); }
    }
}

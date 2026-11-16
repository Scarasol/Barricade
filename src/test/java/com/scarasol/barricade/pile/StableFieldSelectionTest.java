package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Finite geometry proofs, not a production packer, GPU fallback, or performance benchmark. */
class StableFieldSelectionTest {
    private record Node(int id, long rank, GridPos owner, List<OrientedBox> body) {
        Node { body = List.copyOf(body); }
    }

    private static Node cube(int id, long rank, float x, int owner) {
        return new Node(id, rank, new GridPos(owner, 0, 0),
                List.of(OrientedBox.axisAligned(x, 0, 0, x + 1, 1, 1)));
    }

    private static boolean conflicts(Node first, Node second) {
        return first.id != second.id && first.body.stream().anyMatch(a ->
                second.body.stream().anyMatch(b -> a.intersects(b, .001f)));
    }

    private static boolean earlier(Node first, Node second) {
        return first.rank < second.rank || first.rank == second.rank && first.id < second.id;
    }

    private static Set<Node> minima(Collection<Node> universe) {
        Set<Node> kept = new HashSet<>();
        for (Node node : universe)
            if (universe.stream().noneMatch(other -> earlier(other, node) && conflicts(other, node)))
                kept.add(node);
        return Set.copyOf(kept);
    }

    private static Set<Node> visible(Collection<Node> accepted, Set<GridPos> domain,
            Collection<OrientedBox> permanent, Collection<OrientedBox> embedded) {
        Set<Node> result = new HashSet<>();
        for (Node node : accepted)
            if (domain.contains(node.owner) && java.util.stream.Stream.concat(
                    permanent.stream(), embedded.stream()).noneMatch(mask ->
                        node.body.stream().anyMatch(box -> box.intersects(mask, .001f))))
                result.add(node);
        return Set.copyOf(result);
    }

    private static final class GreedyOracle {
        final List<Node> universe;
        final Map<Integer, Boolean> memo = new HashMap<>();
        int visited;

        GreedyOracle(List<Node> universe) { this.universe = List.copyOf(universe); }

        boolean accepted(Node node) {
            Boolean known = memo.get(node.id);
            if (known != null) return known;
            visited++;
            boolean result = universe.stream().filter(other -> earlier(other, node) && conflicts(other, node))
                    .noneMatch(this::accepted);
            memo.put(node.id, result);
            return result;
        }
    }

    @Test void clippingCandidatesBeforeSelectionCanEvictAnExistingObjectOnGrowth() {
        Node old = cube(1, 10, 0, 0), newcomer = cube(2, 0, .75f, 1);
        Set<Node> before = minima(List.of(old)), after = minima(List.of(old, newcomer));
        assertEquals(Set.of(old), before);
        assertEquals(Set.of(newcomer), after);
        assertFalse(after.containsAll(before),
                "Stable random numbers alone do not make a changing conflict graph stable");
    }

    @Test void selectingTheFixedUniverseBeforeMaskingPreservesEveryOldObjectAndMergeOrder() {
        Node a = cube(1, 0, 0, 0), b = cube(2, 1, .75f, 1);
        Node c = cube(3, 0, 2, 2), d = cube(4, 1, 2.75f, 3);
        List<Node> universe = List.of(a, b, c, d);
        Set<Node> field = minima(universe);
        Set<GridPos> first = Set.of(a.owner), second = Set.of(c.owner);
        Set<GridPos> merged = Set.of(a.owner, b.owner, c.owner, d.owner);
        Set<Node> left = visible(field, first, List.of(), List.of());
        Set<Node> right = visible(field, second, List.of(), List.of());
        Set<Node> all = visible(field, merged, List.of(), List.of());
        assertTrue(all.containsAll(left));
        assertTrue(all.containsAll(right));
        List<Node> reversed = new ArrayList<>(universe);
        Collections.reverse(reversed);
        assertEquals(field, minima(reversed));
        for (Node previous : left)
            assertSame(previous, all.stream().filter(node -> node.id == previous.id).findFirst().orElseThrow());
        assertEquals(Set.of(a, c), all);
    }

    @Test void virtualNeighborsProtectStabilityButCanLeaveAnIsolatedCellEmpty() {
        Node inside = cube(1, 10, 0, 0), outside = cube(2, 0, .75f, 1);
        Set<Node> field = minima(List.of(inside, outside));
        assertTrue(visible(field, Set.of(inside.owner), List.of(), List.of()).isEmpty(),
                "A locality proof is not a proof of useful packing density");
        assertEquals(Set.of(outside),
                visible(field, Set.of(inside.owner, outside.owner), List.of(), List.of()));
    }

    @Test void localMinimaAreCollisionFreeButNotNecessarilyMaximal() {
        Node a = cube(1, 0, 0, 0), b = cube(2, 1, .75f, 1), c = cube(3, 2, 1.5f, 2);
        List<Node> universe = List.of(a, b, c);
        Set<Node> local = minima(universe);
        assertEquals(Set.of(a), local);
        assertTrue(local.stream().noneMatch(existing -> conflicts(existing, c)),
                "A still-placeable object was rejected by the one-hop rule");
        GreedyOracle oracle = new GreedyOracle(universe);
        assertTrue(oracle.accepted(a));
        assertFalse(oracle.accepted(b));
        assertTrue(oracle.accepted(c));
    }

    @Test void canonicalGreedySelectionCanHaveDependenciesBeyondAnySingleNeighborHalo() {
        List<Node> chain = new ArrayList<>();
        for (int id = 0; id < 32; id++) chain.add(cube(id, id, .75f * id, id));
        GreedyOracle global = new GreedyOracle(chain);
        assertFalse(global.accepted(chain.get(31)));
        assertEquals(32, global.visited);
        GreedyOracle truncated = new GreedyOracle(chain.subList(1, chain.size()));
        assertTrue(truncated.accepted(chain.get(31)),
                "Clipping an unresolved dependency can change a supposedly stable result");
    }

    @Test void embeddingAndPermanentDamageAreSeparateViewsOfAnUnchangedField() {
        Node a = cube(1, 0, 0, 0), b = cube(2, 1, 2, 2), c = cube(3, 2, 4, 4);
        Set<Node> field = minima(List.of(a, b, c));
        Set<GridPos> domain = Set.of(a.owner, b.owner, c.owner);
        List<OrientedBox> permanent = List.of(c.body.get(0));
        Set<Node> unembedded = visible(field, domain, permanent, List.of());
        assertEquals(Set.of(a, b), unembedded);
        assertEquals(Set.of(b), visible(field, domain, permanent, a.body));
        assertEquals(Set.of(a), visible(field, domain, permanent, b.body));
        assertEquals(unembedded, visible(field, domain, permanent, List.of()));
        assertEquals(Set.of(a, b, c), field);
        assertFalse(unembedded.contains(c), "Removing an embed must not undo permanent destruction");
    }

    @Test void conflictEdgesUseCompoundGeometryRatherThanOnlyTheWorldBoundingBox() {
        Node frame = new Node(1, 0, new GridPos(0, 0, 0), List.of(
                OrientedBox.axisAligned(0, 0, 0, .1f, 1, 1),
                OrientedBox.axisAligned(.9f, 0, 0, 1, 1, 1)));
        Node center = new Node(2, 1, new GridPos(0, 0, 0),
                List.of(OrientedBox.axisAligned(.3f, .2f, .2f, .7f, .8f, .8f)));
        assertFalse(conflicts(frame, center));
        assertEquals(Set.of(frame, center), minima(List.of(frame, center)));
    }
}

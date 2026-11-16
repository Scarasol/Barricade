package com.scarasol.barricade.recipe;

import com.scarasol.barricade.pile.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Pure worker computation. Coordinates stay local even millions of blocks from spawn. */
public final class ArchivedPileReplay {
    private ArchivedPileReplay() { }
    public record Key(long birth, int ordinal) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int order = Long.compare(birth, other.birth);
            return order == 0 ? Integer.compare(ordinal, other.ordinal) : order;
        }
    }
    public record ObjectPiece(Key key, BlockPos owner, PilePiece piece) {
        public ObjectPiece { owner = owner.immutable(); }
    }
    public record DamageShape(BlockPos pos, List<OrientedBox> boxes, boolean embedded) {
        public DamageShape(BlockPos pos, List<OrientedBox> boxes) { this(pos, boxes, false); }
        public DamageShape { pos = pos.immutable(); boxes = List.copyOf(boxes); }
    }
    public record Prepared(ArchivedPileRecipe recipe, List<PileModel> models, List<OrientedBox> obstacles,
                           List<OrientedBox> unknown, List<DamageShape> damage) {
        public Prepared {
            models = List.copyOf(models);
            obstacles = List.copyOf(obstacles);
            unknown = List.copyOf(unknown);
            damage = List.copyOf(damage);
        }
    }
    public static BlockPos origin(Set<BlockPos> domain) {
        return new BlockPos(domain.stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                domain.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                domain.stream().mapToInt(BlockPos::getZ).min().orElseThrow());
    }
    public static List<ObjectPiece> replay(Prepared prepared, List<List<ObjectPiece>> parents,
            List<List<ObjectPiece>> nearby,
            java.util.function.Function<LayoutInput, LayoutResult> generator) {
        ArchivedPileRecipe node = prepared.recipe;
        Map<Key, ObjectPiece> merged = new TreeMap<>();
        parents.forEach(parent -> parent.forEach(piece -> merged.putIfAbsent(piece.key, piece)));
        List<ObjectPiece> survivors = new ArrayList<>(merged.values());
        for (DamageShape damage : prepared.damage) survivors.removeIf(piece -> hit(piece, damage));
        List<ObjectPiece> retained = new ArrayList<>();
        for (ObjectPiece piece : survivors) {
            if (node.domain().contains(piece.owner)) retained.add(piece);
            else {
                var modelCenter = piece.piece.model().collisionCenter();
                var center = piece.piece.transformPoint(modelCenter.x, modelCenter.y, modelCenter.z);
                BlockPos owner = node.domain().stream().min(Comparator.<BlockPos>comparingDouble(pos ->
                        pos.distToCenterSqr(piece.owner.getX() + (double) center.x, piece.owner.getY() + (double) center.y,
                                piece.owner.getZ() + (double) center.z)).thenComparing(Comparator.naturalOrder())).orElseThrow();
                try {
                    retained.add(new ObjectPiece(piece.key, owner, piece.piece.translated(piece.owner.getX() - owner.getX(),
                            piece.owner.getY() - owner.getY(), piece.owner.getZ() - owner.getZ())));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("Surviving object has no legal storage owner", exception);
                }
            }
        }
        if (node.active().isEmpty()) return List.copyOf(retained);
        if (prepared.models.isEmpty()) throw new IllegalStateException("No usable materials for " + node.palette());
        BlockPos origin = origin(node.domain());
        List<OrientedBox> neighbors = new ArrayList<>();
        Map<Key, ObjectPiece> neighborObjects = new TreeMap<>();
        nearby.forEach(group -> group.forEach(object -> neighborObjects.putIfAbsent(object.key, object)));
        neighborObjects.values().forEach(object -> neighbors.addAll(object.piece.colliders(
                object.owner.getX() - origin.getX(), object.owner.getY() - origin.getY(), object.owner.getZ() - origin.getZ())));
        Set<GridPos> domain = relative(node.domain(), origin), active = relative(node.active(), origin);
        Map<RegionPiece, ObjectPiece> identities = new HashMap<>();
        List<RegionPiece> existing = new ArrayList<>();
        for (ObjectPiece piece : retained) {
            RegionPiece relative = new RegionPiece(relative(piece.owner, origin), piece.piece);
            existing.add(relative);
            identities.put(relative, piece);
        }
        Set<GridPos> repack = relative(node.repack(), origin);
        int pass = 0;
        LayoutResult result;
        do {
            result = generator.apply(new LayoutInput(node.seed(), pass++, domain,
                    prepared.models, existing, neighbors, prepared.obstacles, prepared.unknown, active, repack, true));
            existing = result.pieces();
            active = result.work();
            repack = Set.of();
        } while (result.continueFilling());
        List<ObjectPiece> output = new ArrayList<>();
        int ordinal = 0;
        for (RegionPiece piece : result.pieces()) {
            ObjectPiece previous = identities.get(piece);
            output.add(previous != null ? previous : new ObjectPiece(new Key(node.id(), ordinal++),
                    origin.offset(piece.owner().x(), piece.owner().y(), piece.owner().z()), piece.piece()));
        }
        return List.copyOf(output);
    }
    public static boolean hit(ObjectPiece object, DamageShape damage) {
        PilePiece piece = object.piece;
        int dx = object.owner.getX() - damage.pos.getX(), dy = object.owner.getY() - damage.pos.getY(),
                dz = object.owner.getZ() - damage.pos.getZ();
        // A cheap broad phase avoids constructing transformed shapes for distant objects.
        if (Math.abs(dx) > 24 || Math.abs(dy) > 24 || Math.abs(dz) > 24) return false;
        var modelCenter = piece.model().collisionCenter();
        var center = piece.transformPoint(modelCenter.x, modelCenter.y, modelCenter.z).add(dx, dy, dz);
        if (damage.boxes.stream().anyMatch(box -> box.contains(center.x, center.y, center.z))) return true;
        return piece.colliders(dx, dy, dz).stream().anyMatch(box ->
                damage.boxes.stream().anyMatch(other -> damage.embedded
                        ? box.intersects(other, 0.001f) : box.substantiallyIntersects(other)));
    }
    private static GridPos relative(BlockPos pos, BlockPos origin) {
        return new GridPos(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
    }
    private static Set<GridPos> relative(Set<BlockPos> positions, BlockPos origin) {
        Set<GridPos> result = new HashSet<>();
        positions.forEach(pos -> result.add(relative(pos, origin)));
        return Set.copyOf(result);
    }
}

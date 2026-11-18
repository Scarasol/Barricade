package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.EmbeddedBlock;
import com.scarasol.barricade.recipe.FieldDefinition;
import com.scarasol.barricade.recipe.FieldRegion;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import java.util.function.Function;
import java.util.function.Predicate;
import org.joml.Vector3f;

/** Load-local initial contacts plus temporary current embedded coverage. */
final class TerrainShapes {
    static final float TOLERANCE = 1f / 32;
    private final Level level;
    private final Predicate<BlockPos> loaded;
    private final Function<PileModel, StaticMesh> meshes;
    private final Map<BlockPos, List<OrientedBox>> shapes = new HashMap<>();
    private final Map<FieldDefinition, Map<BlockPos, List<FieldVisibility.Shape>>> initial = new HashMap<>();
    private final Map<BlockPos, FieldVisibility.Shape> embeddedShapes = new HashMap<>();
    private final RenderIndex<BlockPos> embeddedIndex = new RenderIndex<>();
    TerrainShapes(ClientLevel level) { this(level, level::hasChunkAt); }
    TerrainShapes(Level level, Predicate<BlockPos> loaded) { this(level, loaded, model -> MaterialMeshes.get(model, level)); }
    TerrainShapes(Level level, Predicate<BlockPos> loaded, Function<PileModel, StaticMesh> meshes) {
        this.level = level; this.loaded = loaded; this.meshes = meshes;
    }
    void clear() { initial.clear(); invalidateEmbedded(); }
    void invalidateEmbedded() { shapes.clear(); embeddedShapes.clear(); embeddedIndex.clear(); }
    void synchronize(Map<UUID, FieldRegion> previous, Map<UUID, FieldRegion> current) {
        Map<FieldDefinition, Set<BlockPos>> logical = new HashMap<>();
        for (var region : current.values())
            logical.computeIfAbsent(region.definition(), ignored -> new HashSet<>()).addAll(region.baseline());
        initial.keySet().retainAll(logical.keySet());
        initial.forEach((definition, cells) -> cells.keySet().retainAll(logical.get(definition)));
        for (var region : previous.values()) {
            FieldRegion placed = current.get(region.id());
            var contacts = initial.get(region.definition());
            if (placed != null && placed.definition().equals(region.definition()) && contacts != null)
                for (BlockPos cell : region.mask().cells())
                    if (placed.domain().contains(cell)) contacts.remove(cell);
        }
    }
    View view(Set<BlockPos> logical, Map<BlockPos, EmbeddedBlock> embedded) {
        return new View(logical, embedded);
    }
    private record Neighborhood(boolean known, List<FieldVisibility.Shape> obstacles) { }
    /** One display pass; ordinary contacts outlive embedded and world refreshes. */
    final class View {
        private final Set<BlockPos> logical;
        private final Map<BlockPos, EmbeddedBlock> embedded;
        private final Map<BlockPos, Neighborhood> neighborhoods = new HashMap<>();
        private boolean indexed;
        View(Set<BlockPos> logical, Map<BlockPos, EmbeddedBlock> embedded) {
            this.logical = logical; this.embedded = embedded;
        }
        boolean visible(FieldObject object) {
            return penetration(object) <= TOLERANCE + 1e-6f;
        }
        float penetration(FieldObject object) {
            List<OrientedBox> boxes = object.piece().colliders(0, 0, 0);
            float depth = 0;
            List<FieldVisibility.Shape> contacts = initial.computeIfAbsent(object.key().definition(), ignored -> new HashMap<>())
                    .computeIfAbsent(object.owner(), this::initialContacts);
            contacts.removeIf(shape -> logical.contains(shape.pos()));
            for (FieldVisibility.Shape shape : contacts) {
                int dx = object.owner().getX() - shape.pos().getX(), dy = object.owner().getY() - shape.pos().getY(),
                        dz = object.owner().getZ() - shape.pos().getZ();
                for (OrientedBox box : boxes) for (OrientedBox other : shape.boxes())
                    depth = Math.max(depth, box.penetration(other, dx, dy, dz));
            }
            if (embedded.isEmpty()) return depth;
            Set<BlockPos> scanned = new HashSet<>();
            for (BlockPos cell : FieldVisibility.cells(object, boxes)) {
                Neighborhood neighborhood = neighborhoods.computeIfAbsent(cell, this::scan);
                if (!neighborhood.known) return Float.POSITIVE_INFINITY;
                for (FieldVisibility.Shape shape : neighborhood.obstacles) {
                    if (!scanned.add(shape.pos())) continue;
                    int dx = object.owner().getX() - shape.pos().getX(), dy = object.owner().getY() - shape.pos().getY(),
                            dz = object.owner().getZ() - shape.pos().getZ();
                    for (OrientedBox box : boxes) for (OrientedBox other : shape.boxes())
                        depth = Math.max(depth, box.penetration(other, dx, dy, dz));
                }
            }
            return depth;
        }
        private List<FieldVisibility.Shape> initialContacts(BlockPos cell) {
            List<FieldVisibility.Shape> result = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                // A destroyed or embedded barricade cell is not ordinary terrain.
                if (pos.equals(cell) || logical.contains(pos) || !loaded.test(pos)) continue;
                var state = level.getBlockState(pos);
                if (state.isAir() || state.getBlock() instanceof com.scarasol.barricade.block.BarricadeBlock) continue;
                List<OrientedBox> boxes = nativeShapes(pos.immutable());
                if (!boxes.isEmpty()) result.add(new FieldVisibility.Shape(pos, boxes, false));
            }
            return result;
        }
        private Neighborhood scan(BlockPos cell) {
            if (!indexed) {
                embedded.values().forEach(TerrainShapes.this::indexEmbedded);
                indexed = true;
            }
            // Retain the existing local wait for registered anchors whose world state is not ready.
            for (BlockPos near : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                if (embedded.containsKey(near) && (!loaded.test(near) || !embeddedShapes.containsKey(near)))
                    return new Neighborhood(false, List.of());
            }
            List<FieldVisibility.Shape> obstacles = new ArrayList<>();
            for (BlockPos pos : embeddedIndex.intersecting(new AABB(cell))) {
                if (embedded.containsKey(pos)) {
                    if (!loaded.test(pos)) return new Neighborhood(false, List.of());
                    obstacles.add(embeddedShapes.get(pos));
                }
            }
            return new Neighborhood(true, List.copyOf(obstacles));
        }
    }
    private void indexEmbedded(EmbeddedBlock block) {
        BlockPos pos = block.pos();
        if (embeddedShapes.containsKey(pos) || !loaded.test(pos)) return;
        var state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof com.scarasol.barricade.block.BarricadeBlock) return;
        var model = block.model(level);
        StaticMesh mesh = meshes.apply(model);
        FieldVisibility.Shape shape;
        if (mesh == null) shape = new FieldVisibility.Shape(pos, nativeShapes(pos), true);
        else {
            var piece = block.transform(model);
            var bounds = mesh.bounds;
            shape = new FieldVisibility.Shape(pos, List.of(new OrientedBox(
                    piece.transformPoint((float) (bounds.minX + bounds.maxX) * .5f,
                            (float) (bounds.minY + bounds.maxY) * .5f,
                            (float) (bounds.minZ + bounds.maxZ) * .5f),
                    new Vector3f((float) bounds.getXsize(), (float) bounds.getYsize(),
                            (float) bounds.getZsize()).mul(piece.scale() * .5f), piece.rotation())), true);
        }
        embeddedShapes.put(pos, shape);
        AABB bounds = null;
        for (OrientedBox box : shape.boxes()) {
            AABB part = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).move(pos);
            bounds = bounds == null ? part : bounds.minmax(part);
        }
        if (bounds != null) embeddedIndex.put(pos, bounds);
    }
    private List<OrientedBox> nativeShapes(BlockPos pos) {
        return shapes.computeIfAbsent(pos, key -> {
            var state = level.getBlockState(key);
            if (state.getBlock() instanceof com.scarasol.barricade.block.BarricadeBlock) return List.of();
            return state.getCollisionShape(level, key).toAabbs().stream().map(box ->
                    OrientedBox.axisAligned((float) box.minX, (float) box.minY, (float) box.minZ,
                            (float) box.maxX, (float) box.maxY, (float) box.maxZ)).toList();
        });
    }
}

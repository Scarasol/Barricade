package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.EmbeddedBlock;
import com.scarasol.barricade.recipe.FieldDefinition;
import com.scarasol.barricade.recipe.FieldRegion;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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
    TerrainShapes(ClientLevel level) { this(level, level::hasChunkAt); }
    TerrainShapes(Level level, Predicate<BlockPos> loaded) { this(level, loaded, model -> MaterialMeshes.get(model, level)); }
    TerrainShapes(Level level, Predicate<BlockPos> loaded, Function<PileModel, StaticMesh> meshes) {
        this.level = level; this.loaded = loaded; this.meshes = meshes;
    }
    void clear() { initial.clear(); invalidateEmbedded(); }
    void invalidateEmbedded() { shapes.clear(); embeddedShapes.clear(); }
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
            List<FieldVisibility.Shape> obstacles = new ArrayList<>();
            // Only registered embedded blocks can change the display after placement.
            for (BlockPos near : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                BlockPos pos = near.immutable();
                EmbeddedBlock block = embedded.get(pos);
                if (block != null) {
                    if (!loaded.test(pos)) return new Neighborhood(false, List.of());
                    FieldVisibility.Shape shape = embeddedShapes.get(pos);
                    if (shape == null) {
                        var state = level.getBlockState(pos);
                        if (state.isAir() || state.getBlock() instanceof com.scarasol.barricade.block.BarricadeBlock)
                            return new Neighborhood(false, List.of());
                        var model = block.model(level);
                        StaticMesh mesh = meshes.apply(model);
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
                    }
                    obstacles.add(shape);
                }
            }
            return new Neighborhood(true, List.copyOf(obstacles));
        }
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

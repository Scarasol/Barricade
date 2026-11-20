package com.scarasol.barricade.stringlights;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class LightTargets {
    private static LongFunction<LightView> clientView = ignored -> null;
    private static Function<BlockPos, VoxelShape> clientOutline = ignored -> Shapes.empty();
    private static Function<BlockPos, List<LightCurve.Bulb>> clientHit = ignored -> List.of();
    private LightTargets() { }

    public static void client(LongFunction<LightView> views, Function<BlockPos, VoxelShape> outlines,
                              Function<BlockPos, List<LightCurve.Bulb>> targets) {
        clientView = views;
        clientOutline = outlines;
        clientHit = targets;
    }

    static LightView.Node clientNode(Target target) {
        BlockPos pos = BlockPos.containing(target.hit);
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            var view = clientView.apply(ChunkPos.asLong(x, z));
            if (view != null && view.nodes().containsKey(target.node)) return view.nodes().get(target.node);
        }
        return null;
    }

    static VoxelShape bulbShape(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            return LightWorld.get(server).spatial.outline(pos);
        } else if (level instanceof Level client && client.isClientSide) {
            return clientOutline.apply(pos);
        }
        return Shapes.empty();
    }

    public static VoxelShape addBulb(VoxelShape current, LightCurve.Bulb bulb, BlockPos pos) {
        return Shapes.or(current, Shapes.create(bulb.glassTarget().move(-pos.getX(), -pos.getY(), -pos.getZ())),
                Shapes.create(bulb.tipTarget().move(-pos.getX(), -pos.getY(), -pos.getZ())),
                Shapes.create(bulb.socket().move(-pos.getX(), -pos.getY(), -pos.getZ())));
    }

    public static Ray ray(Player player) {
        Vec3 origin = player.getEyePosition();
        Vec3 end = origin.add(player.getLookAngle().scale(player.getBlockReach()));
        var block = player.level().clip(new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return new Ray(origin, end, closerBulb(player.level(), origin, block));
    }

    public static BlockHitResult closerBulb(Level level, Vec3 origin, BlockHitResult ordinary) {
        Vec3 end = ordinary.getLocation();
        var nearest = new OutlineHit(origin.distanceToSqr(end));
        LightWorld world = level instanceof ServerLevel server ? LightWorld.get(server) : null;
        BlockGetter.traverseBlocks(origin, end, nearest, (hit, cell) -> {
            if (!level.hasChunkAt(cell)) return null;
            if (world == null) {
                for (var bulb : clientHit.apply(cell)) consider(level, origin, end, bulb, hit);
            } else {
                for (var lamp : world.spatial.hitCell(cell)) {
                    if (!lamp.broken()) consider(level, origin, end, lamp.bulb(), hit);
                }
            }
            return null;
        }, ignored -> null);
        return nearest.hit == null ? ordinary : nearest.hit;
    }

    private static void consider(Level level, Vec3 from, Vec3 to, LightCurve.Bulb bulb, OutlineHit nearest) {
        if (!level.hasChunkAt(bulb.cell()) || !level.getBlockState(bulb.cell()).is(StringLights.BULB.get())) return;
        BlockHitResult hit = AABB.clip(List.of(bulb.glassTarget(), bulb.tipTarget(), bulb.socket()), from, to, BlockPos.ZERO);
        if (hit != null) {
            double distance = from.distanceToSqr(hit.getLocation());
            if (distance < nearest.distance) {
                nearest.hit = hit.withPosition(bulb.cell());
                nearest.distance = distance;
            }
        }
    }

    private static final class OutlineHit {
        double distance;
        BlockHitResult hit;
        OutlineHit(double distance) { this.distance = distance; }
    }

    public record Ray(Vec3 origin, Vec3 end, BlockHitResult block) {
        Vec3 visibleEnd() { return block.getType() == HitResult.Type.MISS ? end : block.getLocation(); }
    }

    public record Target(UUID node, UUID span, int bulb, Vec3 hit) { }
    public enum Tool { WIRE, SHEARS, BOX }

    public static Target pick(Player player, Ray ray, Tool tool) {
        Level level = player.level();
        var nodes = new HashMap<UUID, LightView.Node>();
        var spans = new HashMap<UUID, LightView.Span>();
        Vec3 end = ray.visibleEnd();
        AABB bounds = new AABB(ray.origin, end).inflate(tool == Tool.SHEARS ? 2 : 0.24);
        int x0 = BlockPos.containing(bounds.minX, 0, bounds.minZ).getX() >> 4;
        int x1 = BlockPos.containing(bounds.maxX, 0, bounds.maxZ).getX() >> 4;
        int z0 = BlockPos.containing(bounds.minX, 0, bounds.minZ).getZ() >> 4;
        int z1 = BlockPos.containing(bounds.maxX, 0, bounds.maxZ).getZ() >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            long key = ChunkPos.asLong(x, z);
            if (level instanceof ServerLevel server) {
                var section = LightWorld.get(server).spatial.sections.get(key);
                if (section == null) continue;
                for (var node : section.nodes) nodes.put(node.id, view(node));
                for (var span : section.spans) spans.put(span.id, new LightView.Span(span.id, span.cable(), span.broken(), 0));
            } else {
                LightView view = clientView.apply(key);
                if (view != null) {
                    nodes.putAll(view.nodes());
                    spans.putAll(view.spans());
                }
            }
        }
        var closest = new Pick(ray.origin, end);
        if (tool == Tool.WIRE) {
            for (var span : spans.values()) {
                for (int i = 0; i < span.curve().bulbs().size(); i++) {
                    if (!span.broken(i)) continue;
                    var bulb = span.curve().bulbs().get(i);
                    closest.add(null, span.id(), i, bulb.socket().inflate(1.0 / 16));
                }
            }
            if (closest.target != null) return closest.target;
        }
        if (tool != Tool.SHEARS) {
            for (var node : nodes.values()) {
                if (tool == Tool.BOX && (node.outlet() || node.box() != null)) continue;
                Vec3 point = node.connection();
                double radius = node.box() == null ? 0.105 : 0.145;
                closest.add(node.id(), null, -1, new AABB(point, point).inflate(radius));
            }
        } else if (ray.block().getType() == HitResult.Type.MISS
                || !level.getBlockState(ray.block().getBlockPos()).is(StringLights.BOX.get())) {
            for (var span : spans.values()) {
                for (var piece : span.curve().pieces()) {
                    span.cable().walk(piece, (from, to, a, b) -> closest.cable(span.id(), a, b));
                }
            }
        }
        return closest.target;
    }

    static LightView.Node view(LightGraph.Node node) {
        return new LightView.Node(node.id, node.anchor, node.surface, node.face, node.outlet,
                true, node.box(), node.source());
    }

    public static Vec3 anchor(BlockHitResult hit) {
        return hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(1.0 / 16));
    }

    public static BlockHitResult attachment(Player player, Ray ray) {
        BlockHitResult hit = ray.block;
        if (hit.getType() == HitResult.Type.MISS) return hit;
        var level = player.level();
        BlockPos pos = hit.getBlockPos();
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        var physical = shape.clip(ray.origin, ray.end, pos);
        return physical == null ? hit : physical;
    }

    public static boolean supports(Level level, BlockPos support, Direction face, Vec3 anchor) {
        if (!level.hasChunkAt(support)) return false;
        var state = level.getBlockState(support);
        if (state.is(StringLights.BOX.get()) || state.is(StringLights.BULB.get())) return false;
        Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 local = anchor.subtract(Vec3.atLowerCornerOf(support));
        Vec3 inside = local.subtract(normal.scale(1.0 / 16 + 0.0001));
        Vec3 outside = local.subtract(normal.scale(1.0 / 16 - 0.0001));
        var shape = state.getCollisionShape(level, support);
        for (AABB box : shape.toAabbs()) {
            if (contains(box, inside) && !contains(box, outside)) return true;
        }
        return false;
    }

    private static boolean contains(AABB box, Vec3 point) {
        return box.inflate(1e-7).contains(point);
    }

    public static boolean canBuild(Player player, LightCurve curve) {
        Level level = player.level();
        for (var piece : curve.pieces()) {
            BlockPos pos = piece.cell();
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
            double radius = LightCurve.CABLE_WIDTH / Math.sqrt(2);
            for (BlockPos cell : cells(curve.bounds(piece).inflate(radius))) {
                if (!level.hasChunkAt(cell)) continue;
                var shape = level.getBlockState(cell).getCollisionShape(level, cell);
                for (AABB local : shape.toAabbs()) {
                    if (curve.intersects(piece, local.move(cell).inflate(radius))) return false;
                }
            }
        }
        for (var bulb : curve.bulbs()) if (!canFit(level, bulb)) return false;
        return true;
    }

    static boolean canFit(Level level, LightCurve.Bulb bulb) {
        if (!LightWorld.canPlace(level, bulb.cell())) return false;
        AABB glass = bulb.glass(), tip = bulb.tip(), socket = bulb.socket();
        for (BlockPos cell : cells(bulb.bounds())) {
            if (!level.hasChunkAt(cell)) continue;
            var shape = level.getBlockState(cell).getCollisionShape(level, cell);
            for (AABB part : shape.toAabbs()) {
                AABB box = part.move(cell);
                if (box.intersects(glass) || box.intersects(tip) || box.intersects(socket)) return false;
            }
        }
        return true;
    }

    public static Iterable<BlockPos> cells(AABB box) {
        return BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }

    private static final class Pick {
        private final Vec3 from, to;
        private double best;
        private Target target;

        Pick(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
            best = from.distanceToSqr(to) + 1e-10;
        }

        void add(UUID node, UUID span, int bulb, AABB box) {
            Vec3 hit = box.contains(from) ? from : box.clip(from, to).orElse(null);
            if (hit != null) accept(node, span, bulb, hit);
        }

        void accept(UUID node, UUID span, int bulb, Vec3 hit) {
            double distance = from.distanceToSqr(hit);
            UUID id = node == null ? span : node;
            UUID old = target == null ? null : target.node == null ? target.span : target.node;
            if (distance < best - 1e-11 || (Math.abs(distance - best) <= 1e-11 && old != null && id.compareTo(old) < 0)) {
                target = new Target(node, span, bulb, hit);
                best = distance;
            }
        }

        void cable(UUID id, Vec3 a, Vec3 b) {
            Vec3 u = to.subtract(from), v = b.subtract(a), w = from.subtract(a);
            double uu = u.lengthSqr(), vv = v.lengthSqr(), uv = u.dot(v);
            if (uu == 0 || vv == 0) return;
            double uw = u.dot(w), vw = v.dot(w);
            double det = uu * vv - uv * uv;
            double s = det > 1e-12 ? clamp((uv * vw - vv * uw) / det) : 0;
            double t = clamp((uv * s + vw) / vv);
            s = clamp((uv * t - uw) / uu);
            Vec3 hit = from.add(u.scale(s));
            if (hit.distanceToSqr(a.add(v.scale(t))) <= 0.095 * 0.095) accept(null, id, -1, hit);
        }

        private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    }
}

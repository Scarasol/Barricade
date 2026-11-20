package com.scarasol.barricade.stringlights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Adapter entry points take the source weapon's actual already-clipped path, not a second world ray. */
public final class LightProjectiles {
    private LightProjectiles() { }

    public static final class Hit extends BlockHitResult {
        private final LightSpatial.Lamp lamp;

        private Hit(LightSpatial.Lamp lamp, Vec3 point, Direction face, boolean inside) {
            super(point, face, lamp.bulb().cell(), inside);
            this.lamp = lamp;
        }

        public boolean sameLamp(Hit other) { return lamp.equals(other.lamp); }
    }

    public static List<Hit> hits(ServerLevel level, Vec3 from, Vec3 to) {
        if (from.equals(to)) return List.of();
        LightWorld world = LightWorld.get(level);
        if (world.spatial.sections.isEmpty()) return List.of();
        var candidates = new HashSet<LightSpatial.Lamp>();
        BlockGetter.traverseBlocks(from, to, world, (source, pos) -> {
            var local = source.spatial.hitCell(pos);
            if (!local.isEmpty() && source.loaded(pos) != null) candidates.addAll(local);
            return null;
        }, ignored -> null);
        if (candidates.isEmpty()) return List.of();
        Vec3 travel = to.subtract(from);
        double lengthSquared = travel.lengthSqr();
        var hits = new ArrayList<Hit>();
        for (var lamp : candidates) {
            var chunk = world.loaded(lamp.bulb().cell());
            if (lamp.broken() || chunk == null || !chunk.getBlockState(lamp.bulb().cell()).is(StringLights.BULB.get())) continue;
            Hit a = clip(lamp, lamp.bulb().glassTarget(), from, to, travel);
            Hit b = clip(lamp, lamp.bulb().tipTarget(), from, to, travel);
            Hit hit = a == null || (b != null && b.getLocation().distanceToSqr(from) < a.getLocation().distanceToSqr(from)) ? b : a;
            if (hit != null && hit.getLocation().distanceToSqr(from) < lengthSquared - 1e-12) hits.add(hit);
        }
        hits.sort(Comparator.comparingDouble((Hit hit) -> hit.getLocation().distanceToSqr(from))
                .thenComparing(hit -> hit.lamp.span().id).thenComparingInt(hit -> hit.lamp.index()));
        return hits;
    }

    private static Hit clip(LightSpatial.Lamp lamp, AABB box, Vec3 from, Vec3 to, Vec3 travel) {
        boolean inside = box.contains(from);
        BlockHitResult result = inside ? null : AABB.clip(List.of(box), from, to, net.minecraft.core.BlockPos.ZERO);
        if (!inside && result == null) return null;
        Vec3 point = inside ? from : result.getLocation();
        Direction face = inside ? Direction.getNearest(-travel.x, -travel.y, -travel.z) : result.getDirection();
        return new Hit(lamp, point, face, inside);
    }

    public static BlockHitResult first(Projectile shot, Vec3 from, Vec3 to, BlockHitResult terminal) {
        if (shot.level() instanceof ServerLevel level) {
            for (Hit hit : hits(level, from, to)) if (shot.mayInteract(level, hit.getBlockPos())) return hit;
        }
        return terminal;
    }

    public static void accepted(Projectile shot, BlockHitResult result) {
        if (result instanceof Hit hit && shot.level() instanceof ServerLevel level
                && shot.mayInteract(level, hit.getBlockPos())) LightWorld.get(level).damage(hit.lamp, true);
    }

    public static void pass(Projectile shot, Vec3 from, Vec3 to, Predicate<Hit> allowed, Set<Hit> attempted) {
        if (!(shot.level() instanceof ServerLevel level)) return;
        for (Hit hit : hits(level, from, to)) {
            if (!shot.mayInteract(level, hit.getBlockPos()) || attempted.stream().anyMatch(hit::sameLamp)) continue;
            attempted.add(hit);
            if (allowed.test(hit)) accepted(shot, hit);
        }
    }

    /**
     * Applies glass-only hits for a source-owned instantaneous ray. The caller must pass its actual
     * unobstructed segment and let its normal impact/protection event approve each candidate.
     */
    public static int hitscan(net.minecraft.world.entity.player.Player shooter, Vec3 from, Vec3 end,
                              Predicate<Hit> allowed) {
        if (!(shooter.level() instanceof ServerLevel level)) return 0;
        var world = LightWorld.get(level);
        int broken = 0;
        for (Hit hit : hits(level, from, end)) {
            if (level.mayInteract(shooter, hit.getBlockPos()) && allowed.test(hit) && world.damage(hit.lamp, true)) broken++;
        }
        return broken;
    }
}

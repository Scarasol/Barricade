package com.scarasol.barricade.stringlights.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.scarasol.barricade.stringlights.LightCable;
import com.scarasol.barricade.stringlights.LightCurve;
import com.scarasol.barricade.stringlights.LightTargets;
import com.scarasol.barricade.stringlights.LightTools;
import com.scarasol.barricade.stringlights.LightView;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

record LightPreview(Component message, LightCable cable, AABB marker, boolean valid) {
    static LightPreview update(LightPreview previous, Player player, LightTargets.Tool tool,
                               LightView.Node start, LongFunction<LightView> view) {
        if (tool == null || player.isSecondaryUseActive()) return null;
        var ray = LightTargets.ray(player);
        var target = LightTargets.pick(player, ray, tool);
        LightView.Node end = target == null || target.node() == null ? null : node(view, target.hit(), target.node());
        if (tool == LightTargets.Tool.BOX) {
            if (end == null) return null;
            boolean valid = end.source() == null;
            BlockPos cell = end.surface().relative(end.face());
            return new LightPreview(text(valid ? "mount" : "mount_busy"), null,
                    new AABB(cell).deflate(0.03), valid);
        }
        if (target != null && target.span() != null) {
            var span = span(view, target.hit(), target.span());
            if (span == null) return null;
            if (tool == LightTargets.Tool.SHEARS) return new LightPreview(text("cut"), span.cable(), null, true);
            var bulb = span.curve().bulbs().get(target.bulb());
            return new LightPreview(text("repair"), null, bulb.socket().inflate(0.01), true);
        }
        if (tool == LightTargets.Tool.SHEARS) return null;
        if (start == null || (end != null && end.box() != null && end.id().equals(start.id()))) {
            return end == null ? null : new LightPreview(text("select"), null, marker(end.connection()), true);
        }
        if (end != null && end.box() != null) return error("box_end", end.anchor());
        Vec3 anchor;
        if (end != null) {
            anchor = end.anchor();
            if (start.id().equals(end.id()) || (start.source() != null && end.source() != null
                    && !start.source().equals(end.source()))) return error("connection", anchor);
        } else {
            var hit = LightTargets.attachment(player, ray);
            if (hit.getType() == HitResult.Type.MISS) return null;
            anchor = LightTargets.anchor(hit);
            if (!LightTargets.supports(player.level(), hit.getBlockPos(), hit.getDirection(), anchor)) {
                return error("surface", anchor);
            }
        }
        double lengthSquared = start.anchor().distanceToSqr(anchor);
        if (lengthSquared > LightCurve.MAX_DISTANCE_SQUARED + 1e-10) return error("distance", anchor);
        if (lengthSquared == 0) return error("connection", anchor);
        LightCurve curve = previous == null || previous.cable == null ? null : previous.cable.curve();
        if (curve == null || !matches(curve, start.anchor(), anchor)) curve = new LightCurve(start.anchor(), anchor);
        boolean clear = LightTargets.canBuild(player, curve);
        boolean enough = player.isCreative() || LightTools.count(player) >= curve.cost();
        Component metrics = Component.translatable("string_lights.preview.span",
                String.format(Locale.ROOT, "%.2f", curve.length()), curve.cost(), curve.bulbs().size());
        Component message = clear && enough ? metrics : Component.translatable(
                "string_lights.preview.invalid", Component.translatable("string_lights.error." + (clear ? "materials" : "blocked")), metrics);
        var cable = LightCable.between(curve, start.anchor(), start.connection(), anchor);
        return new LightPreview(message, cable, marker(anchor), clear && enough);
    }

    private static boolean matches(LightCurve curve, Vec3 a, Vec3 b) {
        return curve.start().equals(a) && curve.end().equals(b) || curve.start().equals(b) && curve.end().equals(a);
    }

    private static LightPreview error(String key, Vec3 anchor) {
        return new LightPreview(Component.translatable("string_lights.error." + key), null, marker(anchor), false);
    }

    private static Component text(String key) { return Component.translatable("string_lights.preview." + key); }
    private static AABB marker(Vec3 anchor) { return new AABB(anchor, anchor).inflate(0.085); }

    private static LightView.Node node(LongFunction<LightView> views, Vec3 hit, UUID id) {
        return nearby(views, hit, entry -> entry.nodes().get(id));
    }

    private static LightView.Span span(LongFunction<LightView> views, Vec3 hit, UUID id) {
        return nearby(views, hit, entry -> entry.spans().get(id));
    }

    private static <T> T nearby(LongFunction<LightView> views, Vec3 hit, java.util.function.Function<LightView, T> pick) {
        int x = BlockPos.containing(hit).getX() >> 4, z = BlockPos.containing(hit).getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            LightView view = views.apply(ChunkPos.asLong(x + dx, z + dz));
            if (view != null) {
                T value = pick.apply(view);
                if (value != null) return value;
            }
        }
        return null;
    }

    void render(RenderLevelStageEvent event) {
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var lines = RenderType.lines();
        VertexConsumer out = buffers.getBuffer(lines);
        float r = valid ? 0.35f : 1, g = valid ? 0.91f : 0.33f, b = valid ? 0.79f : 0.28f;
        if (cable != null) {
            for (var piece : cable.curve().pieces()) {
                cable.walk(piece, (from, to, a, bPoint) -> {
                    Vec3 normal = bPoint.subtract(a).normalize();
                    vertex(out, pose.last(), a.subtract(camera), normal, r, g, b);
                    vertex(out, pose.last(), bPoint.subtract(camera), normal, r, g, b);
                });
            }
            if (marker != null) for (var bulb : cable.curve().bulbs()) {
                LevelRenderer.renderLineBox(pose, out, bulb.glass().minmax(bulb.tip()).move(camera.scale(-1)),
                        r, g, b, 0.85f);
            }
        }
        if (marker != null) LevelRenderer.renderLineBox(pose, out, marker.move(camera.scale(-1)), r, g, b, 0.85f);
        buffers.endBatch(lines);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 p, Vec3 normal, float r, float g, float b) {
        out.vertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z).color(r, g, b, 0.9f)
                .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
    }
}

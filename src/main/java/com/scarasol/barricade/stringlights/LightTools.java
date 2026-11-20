package com.scarasol.barricade.stringlights;

import com.scarasol.barricade.Barricade;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID)
public final class LightTools {
    private static final Map<ServerPlayer, Selection> SELECTED = new HashMap<>();
    private LightTools() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void useBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseBlock() == net.minecraftforge.eventbus.api.Event.Result.DENY
                || event.getUseItem() == net.minecraftforge.eventbus.api.Event.Result.DENY) return;
        var player = event.getEntity();
        var hand = event.getHand();
        var stack = player.getItemInHand(hand);
        boolean wire = stack.is(StringLights.WIRE.get());
        if (!wire && (!stack.is(Items.SHEARS) || player.isSecondaryUseActive())) return;
        var ray = LightTargets.ray(player);
        var target = player.isSecondaryUseActive() ? null : LightTargets.pick(player, ray,
                wire ? LightTargets.Tool.WIRE : LightTargets.Tool.SHEARS);
        if (!wire && (target == null || target.span() == null)) return;
        if (player instanceof ServerPlayer server && server.level() instanceof ServerLevel) {
            click(server, hand, ray, target);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void useItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        ItemStack stack = event.getItemStack();
        boolean wire = stack.is(StringLights.WIRE.get());
        boolean box = stack.is(StringLights.BOX_ITEM.get());
        if (!wire && !box && (!stack.is(Items.SHEARS) || player.isSecondaryUseActive())) return;
        var ray = LightTargets.ray(player);
        if (ray.block().getType() != HitResult.Type.MISS && !(wire && player.isSecondaryUseActive())) return;
        var target = LightTargets.pick(player, ray, box ? LightTargets.Tool.BOX
                : wire ? LightTargets.Tool.WIRE : LightTargets.Tool.SHEARS);
        if (!wire && target == null) return;
        InteractionResult result = InteractionResult.sidedSuccess(event.getLevel().isClientSide);
        if (player instanceof ServerPlayer server) {
            if (box) {
                var node = LightWorld.get(server.serverLevel()).graph.node(target.node());
                if (node == null || !server.getAbilities().mayBuild) return;
                Vec3 contact = node.anchor.subtract(Vec3.atLowerCornerOf(node.face.getNormal()).scale(1.0 / 16));
                var hit = new BlockHitResult(contact, node.face, node.surface, false);
                result = stack.useOn(new UseOnContext(player, hand, hit));
            } else click(server, hand, ray, target);
        }
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    private static void click(ServerPlayer player, InteractionHand hand, LightTargets.Ray ray, LightTargets.Target target) {
        if (!player.isAlive() || player.isSpectator() || !player.getAbilities().mayBuild) return;
        var world = LightWorld.get(player.serverLevel());
        var stack = player.getItemInHand(hand);
        if (stack.is(StringLights.WIRE.get())) {
            if (player.isSecondaryUseActive()) {
                clear(player, true);
                return;
            }
            if (target != null && target.span() != null) {
                var span = world.graph.span(target.span());
                if (span != null && world.repair(player, new LightSpatial.Lamp(span, target.bulb()))) {
                    spend(player, stack, 1);
                } else message(player, "blocked");
                return;
            }
            var selection = SELECTED.get(player);
            var start = selection == null || selection.level != world.level ? null : world.graph.node(selection.node);
            var end = target == null || target.node() == null ? null : world.graph.node(target.node());
            if (start == null || (end != null && end.box() != null && end == start)) {
                if (end != null && (!end.outlet || end.box() != null)) select(player, world.level, end, hand);
                else message(player, "start");
                return;
            }
            if (end == null) {
                var hit = LightTargets.attachment(player, ray);
                if (hit.getType() == HitResult.Type.MISS) return;
                var anchor = LightTargets.anchor(hit);
                if (!LightTargets.supports(world.level, hit.getBlockPos(), hit.getDirection(), anchor)
                        || !world.level.mayInteract(player, hit.getBlockPos())) {
                    message(player, "surface");
                    return;
                }
                end = new LightGraph.Node(UUID.randomUUID(), anchor, hit.getBlockPos().immutable(), hit.getDirection(), false);
            } else if (end.box() != null) {
                message(player, "box_end");
                return;
            } else if (!world.level.mayInteract(player, end.surface)) return;
            if (start.anchor.distanceToSqr(end.anchor) > LightCurve.MAX_DISTANCE_SQUARED + 1e-10) {
                message(player, "distance");
                return;
            }
            if (start.anchor.equals(end.anchor) || !world.graph.canJoin(start, end)) {
                message(player, "connection");
                return;
            }
            var curve = new LightCurve(start.anchor, end.anchor);
            if (!player.isCreative() && count(player) < curve.cost()) {
                message(player, "materials");
                return;
            }
            if (!LightTargets.canBuild(player, curve) || !world.join(player, start, end, curve)) {
                message(player, "blocked");
                return;
            }
            spend(player, stack, curve.cost());
            if (player.getItemInHand(hand).is(StringLights.WIRE.get())) select(player, world.level, end, hand);
            else clear(player, true);
        } else if (stack.is(Items.SHEARS) && target != null && target.span() != null) {
            var span = world.graph.span(target.span());
            if (span == null || !world.level.mayInteract(player, BlockPos.containing(target.hit()))) return;
            var pos = BlockPos.containing(target.hit());
            if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.level.BlockEvent.BreakEvent(
                    world.level, pos, world.level.getBlockState(pos), player))) return;
            int refund = world.cut(span);
            if (refund >= 0 && !player.isCreative()) {
                if (refund > 0) net.minecraft.world.level.block.Block.popResource(world.level, pos,
                        new ItemStack(StringLights.WIRE.get(), refund));
                stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
            }
        }
    }

    static LightView.Node boxMount(Player player) {
        var target = LightTargets.pick(player, LightTargets.ray(player), LightTargets.Tool.BOX);
        if (target == null) return null;
        if (player.level() instanceof ServerLevel level) {
            var node = LightWorld.get(level).graph.node(target.node());
            return node == null ? null : LightTargets.view(node);
        }
        return LightTargets.clientNode(target);
    }

    private static void select(ServerPlayer player, ServerLevel level, LightGraph.Node node, InteractionHand hand) {
        SELECTED.put(player, new Selection(level, node.id, hand));
        LightNetwork.select(player, node.id);
    }

    private static void clear(ServerPlayer player, boolean inform) {
        if (SELECTED.remove(player) != null && inform) LightNetwork.select(player, null);
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable("string_lights.error." + key), true);
    }

    public static int count(Player player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var item = player.getInventory().getItem(i);
            if (item.is(StringLights.WIRE.get())) count += item.getCount();
        }
        return count;
    }

    private static void spend(ServerPlayer player, ItemStack preferred, int amount) {
        if (player.isCreative()) return;
        for (int i = 0; i < player.getInventory().getContainerSize() && amount > 0; i++) {
            var stack = player.getInventory().getItem(i);
            if (stack != preferred && stack.is(StringLights.WIRE.get())) {
                int take = Math.min(stack.getCount(), amount);
                stack.shrink(take);
                amount -= take;
            }
        }
        preferred.shrink(amount);
    }

    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        var selection = SELECTED.get(player);
        if (selection != null && (player.level() != selection.level
                || !player.getItemInHand(selection.hand).is(StringLights.WIRE.get())
                || LightWorld.get(selection.level).graph.node(selection.node) == null)) clear(player, true);
    }

    @SubscribeEvent public static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player, true);
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player, false);
    }

    private record Selection(ServerLevel level, UUID node, InteractionHand hand) { }
}

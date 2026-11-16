package com.scarasol.barricade.compat.bettercombat;

import com.scarasol.barricade.window.WindowSealBlock;
import net.bettercombat.api.AttackHand;
import net.bettercombat.client.collision.TargetFinder;
import net.bettercombat.logic.PlayerAttackHelper;
import net.bettercombat.logic.PlayerAttackProperties;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Loaded only by the optional, client-side Better Combat mixins. */
public final class BetterCombatCompat {
    private BetterCombatCompat() {}

    public static AttackHand currentAttack(Player player) {
        return PlayerAttackHelper.getCurrentAttack(player, ((PlayerAttackProperties) player).getComboCount());
    }

    public static boolean canAttackThrough(AttackHand hand) {
        if (hand.itemStack().is(WindowSealBlock.ATTACK_THROUGH_ITEMS)) return true;
        String category = hand.attributes().category();
        return "spear".equals(category) || "lance".equals(category) || "trident".equals(category);
    }

    public static HitResult pick(Player player, HitResult original, AttackHand hand) {
        if (!(original instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK
                || !canAttackThrough(hand)
                || !(player.level().getBlockState(block.getBlockPos()).getBlock() instanceof WindowSealBlock))
            return original;

        // Use the same range, hitbox and target relations as the eventual Better Combat attack.
        var targets = TargetFinder.findAttackTargets(player, null, hand.attack(), hand.attributes().attackRange());
        Entity nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Entity target : targets) {
            double candidate = player.distanceToSqr(target);
            if (candidate < distance) {
                nearest = target;
                distance = candidate;
            }
        }
        return nearest == null ? original : new EntityHitResult(nearest);
    }
}

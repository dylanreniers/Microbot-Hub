package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Default state: keep the fight going. Eats/drinks to the configured thresholds and (re)issues an
 * attack on the Mad Angel when idle. During a sweep we still attack FOR DPS, but only between cleaves —
 * once we've stopped moving on the safe tile (which is adjacent to the boss, so we're in melee range)
 * and no dodge walk is pending, so we never interrupt the strafe. During a blast we likewise attack IF
 * the boss is in melee range of the marked tile we're standing on (attacking in place doesn't move us
 * off it); if it isn't adjacent we hold and don't attack. Only clicks when we're NOT already attacking
 * her, to avoid spam.
 */
@Slf4j
public class AttackAction implements MadAngelAction {

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String key() {
        return "attack";
    }

    @Override
    public boolean needsExecution(MadAngelState state) {
        MadAngelContext ctx = state.context();
        if (ctx.isBlastActive()) {
            // Reaching + holding the marked tile is the priority (BlastStandAction, order 210). Only once
            // we're standing EXACTLY on it do we attack for DPS, and only if the boss is orthogonally
            // adjacent (attacking in place won't move us off the tile). If the tile moves between bounces,
            // player != tile again → we stop attacking and re-walk to the new tile before resuming.
            WorldPoint tile = ctx.getBlastTile();
            WorldPoint player = Rs2Player.getWorldLocation();
            boolean onMarkedTile = tile != null && tile.equals(player);
            NPC angel = ctx.getCurrentTarget() != null ? ctx.getCurrentTarget().getNpc() : null;
            return onMarkedTile && MadAngelHelpers.isInMeleeRange(Microbot.getClient(), angel);
        }
        if (ctx.isSweepActive() || ctx.isSweepPlanActive()) {
            // Between cleaves: re-attack for DPS, but only after the dodge walk has finished (we've
            // stopped moving) and none is pending — otherwise we'd cancel the strafe to the safe tile.
            return !ctx.isSweepDodgeWalkPending() && !Rs2Player.isMoving();
        }
        return true;
    }

    @Override
    public Object execute(MadAngelState state) {
        MadAngelContext ctx = state.context();

        Rs2Player.eatAt(state.config().minEatPercent());
        Rs2Player.drinkPrayerPotionAt(state.config().minPrayerPercent());

        Rs2NpcModel target = MadAngelHelpers.getTarget(ctx);
        ctx.setCurrentTarget(target);
        if (target == null || target.getNpc().isDead()) {
            return false;
        }

        // Already auto-attacking her — do NOT re-click. engagedWithAngel is computed on the client thread
        // in the plugin's onGameTick (getInteracting() throws off-thread). The interaction only drops when
        // a dodge/blast moves us off her, which is exactly when we want to re-engage — this stops the
        // "click after every attack" spam that the isAnimating gap allowed.
        if (ctx.isEngagedWithAngel()) {
            return false;
        }

        // Otherwise only re-issue while idle, as a backstop against clicking mid-animation.
        if (Rs2Player.isAnimating(1600)) {
            return false;
        }

        boolean didWeAttack = target.click("Attack");
        if (didWeAttack) {
            ctx.setFailedAttacks(0);
        } else {
            ctx.setFailedAttacks(ctx.getFailedAttacks() + 1);
            if (ctx.getFailedAttacks() >= 7) {
                ctx.setCurrentTarget(null); // force re-acquire next tick
                ctx.setFailedAttacks(0);
            }
        }
        return didWeAttack;
    }
}

package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.ArmorEquiped;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.AttackStyle;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/**
 * FIGHTING phase: the "Rhaaaa"-cry style-switch prediction and positioning. On the cry it pre-prays
 * the deterministic next overhead (never-same rule) and opens a gap to read the melee tell (a melee
 * gorilla closes on us; range/magic stay at distance). Ground-truth correction on the actual attack
 * animation is handled by the plugin's event-driven fail-check. Runs with no client-thread round-trips
 * so the follow check stays responsive every tick.
 */
@Slf4j
public class GorillaAttacksAction implements GorillaAction {

    @Override
    public int order() {
        return 600;
    }

    @Override
    public String key() {
        return "gorilla-attacks";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        GorillaContext ctx = state.context();
        return ctx.getBotStatus() == State.FIGHTING
                && ctx.getCurrentTarget() != null
                && !ctx.getCurrentTarget().getNpc().isDead();
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        Rs2NpcModel target = ctx.getCurrentTarget();

        // Cheap per-tick reads ONLY — no client-thread invoke() round-trips, so the "is it following?"
        // check runs every ~50ms without stalling. Ground-truth attack style is handled entirely by the
        // plugin's event-driven fail-check (onAnimationChanged); here we only predict and position.
        WorldPoint gorillaPos = target.getWorldLocation();
        WorldPoint playerPos = Rs2Player.getWorldLocation();
        if (gorillaPos == null || playerPos == null) {
            return null;
        }
        int dist = gorillaPos.distanceTo(playerPos);
        boolean weRanging = ctx.getCurrentGear() == ArmorEquiped.RANGED
                || ctx.getCurrentGear() == ArmorEquiped.MAGIC;

        Rs2PrayerEnum newDefensivePrayer = null;
        boolean movedThisTick = false;

        if (ctx.isStyleSwitchCryPending()) {
            // The cry's prayer was already pre-set immediately in the plugin's event handler. Here we only
            // open the read gap (worker thread) — and only if we're too close; if already at range, stay
            // put so we don't walk across the room each cry.
            ctx.setStyleSwitchCryPending(false);
            AttackStyle prev = ctx.getPreviousAttackStyle();
            if ((prev == AttackStyle.MAGIC || prev == AttackStyle.RANGED) && dist < 4 && !Rs2Player.isMoving()) {
                movedThisTick = GorillaHelpers.moveAwayFromTarget(ctx, 4);
            }
        } else if (ctx.isAwaitingStyleSwitch()) {
            // Melee tell: a melee gorilla must CLOSE the gap to reach us; a range/magic one attacks from
            // afar. Only trust "within 2 tiles" AFTER the gap has actually opened (>= 4 reached) — before
            // that we're still mid-step and close, which would falsely read magic/range as melee.
            AttackStyle prev = ctx.getPreviousAttackStyle();
            if (prev == AttackStyle.MAGIC || prev == AttackStyle.RANGED) {
                if (!ctx.isAwaitingGapOpened() && dist >= 4) {
                    ctx.setAwaitingGapOpened(true);
                }
                if (ctx.isAwaitingGapOpened() && dist <= 2) {
                    newDefensivePrayer = Rs2PrayerEnum.PROTECT_MELEE;
                }
            }
        }

        // Re-pray whenever the needed overhead isn't ACTUALLY active (safe if points ran out then a
        // restore was drunk — we don't get stuck praying the wrong/no overhead).
        if (newDefensivePrayer != null && !Rs2Prayer.isPrayerActive(newDefensivePrayer)) {
            GorillaHelpers.switchDefensivePrayer(ctx, newDefensivePrayer);
        }

        // While ranging AND the gorilla is using range/magic, keep a ~3–4 tile gap so a melee switch shows
        // up immediately as movement. Only step when we're NOT already walking, otherwise we'd fire a new
        // walk every 50 ms tick before the last step finishes and sprint around the room.
        if (!movedThisTick && weRanging && ctx.getCurrentAttackStyle() != AttackStyle.MELEE
                && ctx.getCurrentDefensivePrayer() != Rs2PrayerEnum.PROTECT_MELEE
                && dist < 3 && !Rs2Player.isMoving()) {
            GorillaHelpers.moveAwayFromTarget(ctx, 4);
        }

        ctx.setLastGorillaLocation(gorillaPos);
        return null;
    }
}

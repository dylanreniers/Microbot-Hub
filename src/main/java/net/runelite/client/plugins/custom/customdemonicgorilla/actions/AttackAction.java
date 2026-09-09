package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaConfig;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.ArmorEquiped;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * FIGHTING phase: sustain the fight — eat/drink to the configured thresholds and (re)issue an attack
 * on the current gorilla when idle, optionally firing a special attack. Corresponds to the old
 * {@code attackGorilla}. Only clicks when not already animating, to avoid spam-clicking the target.
 */
@Slf4j
public class AttackAction implements GorillaAction {

    /** Hard cap on the melee-read hold. Long enough for the per-tick approach tell to resolve (~2-3 ticks),
     *  short enough that a stuck/flinched gorilla doesn't stall the fight. */
    private static final long MELEE_READ_HOLD_MS = 1500;

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String key() {
        return "attack";
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
        CustomDemonicGorillaConfig config = state.config();

        Rs2Player.eatAt(config.minEatPercent());
        Rs2Player.drinkPrayerPotionAt(config.minPrayerPercent());

        // Don't re-issue an attack while a boulder is inbound/landing: clicking the gorilla can path us back
        // onto the danger tile the dodge just cleared. BoulderDodgeAction keeps us safe each tick; we resume
        // attacking the moment the danger window closes. (Replaces the old blocking sleep in the dodge.)
        if (System.currentTimeMillis() < ctx.getBoulderDangerUntilMs()) {
            return false;
        }

        // While reading the "Rhaaaa" style-switch tell in melee gear: after the cry we step ~4 tiles away
        // to watch whether the gorilla walks to us (melee) or stays (range/magic). If we re-clicked the
        // gorilla here we'd immediately path back into melee range and defeat the read. So HOLD position
        // while awaiting the switch and we've actually backed off (not adjacent). eat/drink above still run.
        // The hold is CAPPED: normally the gorilla's next attack clears 'awaiting' (onAnimationChanged), but
        // if it's stuck behind another gorilla / a wall it never attacks, so without a cap we'd wait forever
        // and bleed DPS. After MELEE_READ_HOLD_MS (a couple ticks — enough for the onGameTick tell to fire)
        // we abandon the read and re-engage.
        if (ctx.isAwaitingStyleSwitch() && ctx.getCurrentGear() == ArmorEquiped.MELEE) {
            if (System.currentTimeMillis() - ctx.getStyleSwitchArmedMs() > MELEE_READ_HOLD_MS) {
                ctx.setAwaitingStyleSwitch(false);
                ctx.setAwaitingGapOpened(false);
            } else {
                WorldPoint gp = ctx.getCurrentTarget().getWorldLocation();
                WorldPoint pp = Rs2Player.getWorldLocation();
                if (gp != null && pp != null && gp.distanceTo(pp) > 1) {
                    return false; // holding briefly to read the tell
                }
            }
        }

        // Only re-issue an attack when we are idle - avoids spam-clicking the same target
        if (Rs2Player.isAnimating(1600)) {
            return false;
        }

        if (config.enableAutoSpecialAttacks()) {
            Rs2Combat.setSpecState(true, 500);
        }

        boolean didWeAttack = ctx.getCurrentTarget().click("Attack");
        if (didWeAttack) {
            ctx.setFailedAttacks(0);
        } else {
            ctx.setFailedAttacks(ctx.getFailedAttacks() + 1);
            if (ctx.getFailedAttacks() >= 7) {
                ctx.setCurrentTarget(GorillaHelpers.getTarget(ctx, true));
                ctx.setFailedAttacks(0);
            }
        }
        return didWeAttack;
    }
}

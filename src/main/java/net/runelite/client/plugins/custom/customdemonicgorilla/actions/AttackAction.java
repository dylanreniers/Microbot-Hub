package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaConfig;
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

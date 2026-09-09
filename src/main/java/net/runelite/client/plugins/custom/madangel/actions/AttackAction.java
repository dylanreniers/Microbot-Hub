package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Default state: keep the fight going. Eats/drinks to the configured thresholds and (re)issues an
 * attack on the Mad Angel when idle. Suppressed while a sweep dodge or blast reaction is pending, so
 * we never path back into the cleave/energy-ball we just stepped out of — those reactions clear and
 * we re-engage on the next tick. Only clicks when we're NOT already attacking her, to avoid spam.
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
        // Hold while a reaction owns our position: through the whole sweep (dodge once + hold) and blast.
        return !ctx.isSweepActive() && !ctx.isSweepPending() && !ctx.isBlastActive();
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

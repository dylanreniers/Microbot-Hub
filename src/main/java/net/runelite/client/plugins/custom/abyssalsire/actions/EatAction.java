package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Eats to keep health above the configured threshold, every tick. Before a kill starts (phase 1, not
 * yet engaged) it eats up to the higher "start kill" threshold instead — this runs first (order 100),
 * so we heal and free inventory slots BEFORE {@link GearSwitchAction} swaps to the range gear.
 */
@Slf4j
public class EatAction implements SireAction {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String key() {
        return "eat";
    }

    @Override
    public boolean needsExecution(SireState state) {
        return Rs2Player.getHealthPercentage() <= targetPercent(state.context());
    }

    @Override
    public Object execute(SireState state) {
        int target = targetPercent(state.context());
        boolean ate = Rs2Player.eatAt(target, true);
        if (ate) {
            log.info("[sire] eating (target {}%)", target);
        }
        return ate;
    }

    /**
     * The HP% to eat up to: the higher "start kill" threshold before a kill begins (so we enter healthy
     * and clear inventory space for the gear switch), otherwise the normal in-combat eat threshold.
     */
    private int targetPercent(SireContext ctx) {
        if (ctx.getPhase() == SirePhase.PHASE1 && !ctx.isFightStarted()) {
            return Math.max(ctx.getEatPercent(), ctx.getStartKillMinHpPercent());
        }
        return ctx.getEatPercent();
    }
}

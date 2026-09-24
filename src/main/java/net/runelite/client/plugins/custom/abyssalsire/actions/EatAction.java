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

    /** OSRS food cooldown is 3 ticks (~1.8s). Don't re-eat before then: extra clicks land nothing, and
     *  spamming them every tick made every tick read as a consume and permanently stalled the attack. */
    private static final long FOOD_COOLDOWN_MS = 1_800;

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
        SireContext ctx = state.context();
        // Standing in a miasma pool? Don't eat — dodge first. The move must win the tick so we vacate the
        // pool immediately instead of spending it on food (the phase action walks us out this tick).
        if (SireHelpers.standingInMiasma(ctx)) {
            return false;
        }
        // Respect the food cooldown so we don't spam-click food (and so the intervening ticks are free to
        // attack the boss instead of registering as consumes).
        if (System.currentTimeMillis() - ctx.getLastEatMs() < FOOD_COOLDOWN_MS) {
            return false;
        }
        return Rs2Player.getHealthPercentage() <= targetPercent(ctx);
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();
        int target = targetPercent(ctx);
        boolean ate = Rs2Player.eatAt(target, true);
        if (ate) {
            ctx.setLastEatMs(System.currentTimeMillis());
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

package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Pre-fight world hop: while idle at the original spot, if another player is in the arena, hop to a
 * fresh world so we don't share the Sire. Only runs when no fight is active ({@link SirePhase#IDLE}),
 * so it never hops mid-kill.
 */
@Slf4j
public class HopAction implements SireAction {

    private static final int ARENA_RADIUS = 15;

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String key() {
        return "hop";
    }

    @Override
    public boolean needsExecution(SireState state) {
        SireContext ctx = state.context();
        // Only before we've engaged this fight: idle, or in phase 1 but no barrage cast yet.
        return ctx.isHopEnabled()
                && !ctx.isFightStarted()
                && ctx.getBarrageCastAtMs() == 0
                && (ctx.getPhase() == SirePhase.IDLE || ctx.getPhase() == SirePhase.PHASE1)
                && SireHelpers.atTile(SireConstants.ORIGINAL_POSITION);
    }

    @Override
    public Object execute(SireState state) {
        boolean hopped = Rs2Player.hopIfPlayerDetected(1, 0, ARENA_RADIUS);
        if (hopped) {
            log.info("[sire] player detected at the Sire — hopping worlds");
        }
        return hopped;
    }
}

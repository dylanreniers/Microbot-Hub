package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Reaction: the angel's sweep cleave. The <b>when</b> and <b>where</b> are decided by the tick schedule in
 * {@code MadAngelPlugin.onGameTick} (client thread): at each scheduled cleave it computes the safe tile —
 * the side of the 3x3 boss OPPOSITE the sword (which alternates per cleave) — in the boss's orientation
 * frame, and stores it as {@code sweepDodgeTile}. This action just executes the walk on the script thread
 * (so walking/logging stay off the client thread). Driving off the tick schedule rather than per-cleave
 * AnimationChanged events is deliberate: repeated-id cleaves don't fire AnimationChanged, so an
 * event-driven dodge misses them — the tick schedule doesn't.
 */
@Slf4j
public class SweepDodgeAction implements MadAngelAction {

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String key() {
        return "sweep-dodge";
    }

    @Override
    public boolean needsExecution(MadAngelState state) {
        MadAngelContext ctx = state.context();
        return state.config().enableSweepDodge()
                && !state.config().sweepDodgeDryRun()
                && ctx.isSweepDodgeWalkPending()
                && ctx.getSweepDodgeTile() != null;
    }

    @Override
    public Object execute(MadAngelState state) {
        MadAngelContext ctx = state.context();
        ctx.setSweepDodgeWalkPending(false); // consumed; the next scheduled cleave re-arms it

        WorldPoint dest = ctx.getSweepDodgeTile();
        WorldPoint player = Rs2Player.getWorldLocation();
        if (dest == null || player == null || dest.equals(player)) {
            return null; // already on the safe tile (side didn't change this cleave)
        }
        log.info("[mad-angel] sweep -> dodge to {} (from {})", dest, player);
        Rs2Walker.walkFastCanvas(dest);
        return dest;
    }
}

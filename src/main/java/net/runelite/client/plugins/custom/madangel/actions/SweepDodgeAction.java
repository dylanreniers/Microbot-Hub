package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Reaction: the angel's sweep cleave. The plugin fixes two tiles at the first cleave — our origin and
 * the tile straight THROUGH her — and this action ping-pongs between them on each cleave (so we keep
 * crossing to the far side without drift). Between cleaves {@code AttackAction} holds (gated on
 * sweepActive) instead of re-approaching, and re-engages once the burst ends.
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
        return state.config().enableSweepDodge() && state.context().isSweepPending();
    }

    @Override
    public Object execute(MadAngelState state) {
        MadAngelContext ctx = state.context();
        ctx.setSweepPending(false); // consumed per cleave; the next cleave animation re-arms it

        // Ping-pong between the two tiles fixed at the sweep's first cleave.
        WorldPoint dest = ctx.isSweepNextIsThrough() ? ctx.getSweepThroughTile() : ctx.getSweepOriginTile();
        ctx.setSweepNextIsThrough(!ctx.isSweepNextIsThrough()); // alternate for the next cleave

        WorldPoint player = Rs2Player.getWorldLocation();
        if (dest == null || player == null || dest.equals(player)) {
            return null;
        }
        log.info("[mad-angel] sweep -> dodge to {} (from {})", dest, player);
        Rs2Walker.walkFastCanvas(dest);
        return dest;
    }
}

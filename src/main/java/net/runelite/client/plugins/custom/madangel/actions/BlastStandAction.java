package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Reaction: the angel's blast ("burst"). A marked tile (graphics object 1448) appears and an energy
 * ball (projectile 4015) flies at it — standing on the tile bounces it back. We walk onto the marked
 * tile ONCE and hold there through every bounce; the plugin's {@code onGameTick} locks the tile to the
 * id-1448 object and clears {@code blastActive} once the ball has been gone a grace period, at which
 * point {@link AttackAction} re-engages.
 */
@Slf4j
public class BlastStandAction implements MadAngelAction {

    @Override
    public int order() {
        return 210;
    }

    @Override
    public String key() {
        return "blast-stand";
    }

    @Override
    public boolean needsExecution(MadAngelState state) {
        return state.config().enableBlastDodge() && state.context().isBlastActive();
    }

    @Override
    public Object execute(MadAngelState state) {
        MadAngelContext ctx = state.context();

        WorldPoint tile = ctx.getBlastTile();
        if (tile == null) {
            return null; // marked tile (id-1448) not located yet — onGameTick locks it
        }

        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null || player.equals(tile)) {
            return tile; // already standing on the marked tile — hold, don't click
        }
        // Not on the tile: click ONCE and only re-click if we've stopped moving and still aren't there
        // (a failed/stalled path). The isMoving guard + short debounce stop the per-tick spam-click.
        if (Rs2Player.isMoving() || System.currentTimeMillis() - ctx.getLastBlastWalkMs() < 400) {
            return tile;
        }
        log.info("[mad-angel] blast -> standing on marked tile {}", tile);
        Rs2Walker.walkFastCanvas(tile);
        ctx.setLastBlastWalkMs(System.currentTimeMillis());
        return tile;
    }
}

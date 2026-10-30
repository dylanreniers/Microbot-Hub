package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Comparator;
import java.util.Set;

/**
 * Lava pools: if we're standing on a tile where a lava graphics object (1246) spawned, walk to the
 * nearest walkable tile without one — the same approach as the Tormented Demon special dodge.
 */
@Slf4j
public class LavaDodgeAction implements CerberusAction {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String key() {
        return "lava-dodge";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        if (!state.config().dodgeLava() || !state.context().isFightActive()) {
            return false;
        }
        WorldPoint player = Rs2Player.getWorldLocation();
        return player != null && state.context().lavaTiles().contains(player);
    }

    @Override
    public Object execute(CerberusState state) {
        Set<WorldPoint> lava = state.context().lavaTiles();
        WorldPoint player = Rs2Player.getWorldLocation();
        WorldPoint safe = Rs2Tile.getWalkableTilesAroundPlayer(CerberusConstants.LAVA_DODGE_RADIUS).stream()
                .filter(tile -> !lava.contains(tile))
                .min(Comparator.comparingInt(player::distanceTo))
                .orElse(null);
        if (safe == null) {
            log.warn("[cerberus] standing in lava at {} but found no safe tile", player);
            return null;
        }
        if (!state.claimClick()) {
            return null; // a prayer switch took this tick's click; dodge next tick
        }
        log.info("[cerberus] lava at {} — dodging to {}", player, safe);
        Rs2Walker.walkFastCanvas(safe);
        return safe;
    }
}

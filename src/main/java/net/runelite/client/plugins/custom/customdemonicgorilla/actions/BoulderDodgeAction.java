package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.ArrayList;
import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

/**
 * FIGHTING phase: dodge the AOE boulder. Armed by the plugin's ProjectileMoved handler from the
 * projectile's target tile (early — well before impact). Runs regardless of whether we currently have
 * a target, so we still dodge during the between-kills loot wait when no gorilla is engaged.
 */
@Slf4j
public class BoulderDodgeAction implements GorillaAction {

    @Override
    public int order() {
        return 250;
    }

    @Override
    public String key() {
        return "boulder-dodge";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        return state.context().getBotStatus() == State.FIGHTING && state.context().isBoulderDodgePending();
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        ctx.setBoulderDodgePending(false);
        WorldPoint danger = ctx.getBoulderTargetTile();
        long landsAt = ctx.getBoulderLandsAtMs();
        if (danger == null) {
            return null;
        }

        WorldPoint player = Rs2Player.getWorldLocation();
        if (danger.equals(player)) {
            // Standing on the boulder tile — step off to a safe tile.
            List<WorldPoint> dangerousWorldPoints = new ArrayList<>(Rs2Tile.getDangerousGraphicsObjectTiles().keySet());
            dangerousWorldPoints.add(danger);
            WorldPoint safeTile = GorillaHelpers.findSafeTile(ctx, player, dangerousWorldPoints);
            log.info("[gorilla-boulder] on tile, dodging danger={} -> safe={}", danger, safeTile);
            if (safeTile != null) {
                Rs2Walker.walkFastCanvas(safeTile);
            }
        } else {
            log.info("[gorilla-boulder] off tile (danger={} player={}) — holding until it lands", danger, player);
        }

        // Hold off the tile until the boulder lands, so the melee re-attack can't walk us back onto it
        // before impact. Prayer stays protected meanwhile — the AnimationChanged fail-check is independent
        // of this tick. Bounded so a bad landing estimate can't stall us.
        long sleepMs = landsAt - System.currentTimeMillis() + 300;
        if (sleepMs > 0) {
            sleep((int) Math.min(sleepMs, 3000));
        }
        return null;
    }
}

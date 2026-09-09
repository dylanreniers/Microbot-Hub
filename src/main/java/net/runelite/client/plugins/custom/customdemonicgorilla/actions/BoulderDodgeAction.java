package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FIGHTING phase: dodge the AOE boulder(s). The plugin's ProjectileMoved handler records every inbound
 * boulder's landing tile (early — well before impact) plus a danger window. This action re-evaluates
 * EVERY tick while that window is open (it never sleeps): if we're standing on any tile a boulder is
 * about to hit, it steps to a nearby tile clear of ALL inbound boulders. The old version dodged once and
 * then blocked the whole pipeline on a multi-tick sleep, so during a barrage the next boulder landed on
 * the tile we'd just fled to while we sat asleep on it — the "hit a few times in a row" bug.
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
        GorillaContext ctx = state.context();
        // Active whenever a dodge was just armed OR a boulder is still in its danger window — so we keep
        // re-checking each tick through a barrage instead of acting once and going quiet.
        return ctx.getBotStatus() == State.FIGHTING
                && (ctx.isBoulderDodgePending() || System.currentTimeMillis() < ctx.getBoulderDangerUntilMs());
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        long now = System.currentTimeMillis();
        ctx.setBoulderDodgePending(false);
        ctx.pruneInboundBoulders(now);

        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) {
            return null;
        }

        // Full danger set: every inbound boulder's landing tile plus any already-spawned AOE graphics tiles.
        Set<WorldPoint> danger = new HashSet<>(ctx.getInboundBoulders().keySet());
        danger.addAll(Rs2Tile.getDangerousGraphicsObjectTiles().keySet());

        if (danger.contains(player)) {
            // Standing on a tile a boulder will hit — step to a nearby tile clear of ALL inbound boulders.
            List<WorldPoint> dangerList = new ArrayList<>(danger);
            WorldPoint safeTile = GorillaHelpers.findSafeTile(ctx, player, dangerList);
            log.info("[gorilla-boulder] on danger tile, dodging player={} danger={} -> safe={}", player, danger, safeTile);
            if (safeTile != null) {
                Rs2Walker.walkFastCanvas(safeTile);
            }
        }
        // Not on a danger tile: stay put. AttackAction suppresses re-approach while the danger window is
        // open (see its boulder guard), so we can't path back onto a boulder — no blocking sleep needed.
        return null;
    }
}

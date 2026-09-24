package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.grounditems.GroundItem;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * After the Sire dies (prayers are already dropped in onSireDeath), picks up EVERYTHING on the ground
 * within range — every drop regardless of value or tradeability (value >= 0, untradeables and coins),
 * the same "loot everything" pass the Tormented Demon script uses. Once the ground is clear it hands off
 * to the restock trip (or walks back and resets when restock is off).
 */
@Slf4j
public class LootAction implements SireAction {

    private static final int LOOT_RANGE = 15;
    /** Grace for the drop to land after death. */
    private static final long INITIAL_LOOT_WAIT_MS = 2500;
    /** Deadline extension after each successful pickup, or while loot is still on the ground. */
    private static final long LOOT_EXTEND_MS = 1500;
    /** Hard cap on the whole loot phase, so a genuinely unreachable drop can't stall us forever. */
    private static final long MAX_LOOT_MS = 12_000;

    @Override
    public int order() {
        return 700;
    }

    @Override
    public String key() {
        return "loot";
    }

    @Override
    public boolean needsExecution(SireState state) {
        // Stop once the restock trip is armed — ReturnToSireAction owns the between-kills flow then.
        return state.context().getPhase() == SirePhase.DEAD && !state.context().isPrepPending();
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();
        final long now = System.currentTimeMillis();
        if (ctx.getLootDeadlineMs() == 0) {
            ctx.setLootDeadlineMs(now + INITIAL_LOOT_WAIT_MS);
            ctx.setLootStartMs(now);
        }

        // Inventory full but there's still loot on the ground? Eat one food to free a slot so we can pick
        // it up, and extend the deadline so we don't time out while making room. (No food -> fall through.)
        if (Rs2Inventory.isFull() && nearestLoot() != null) {
            if (Rs2Player.eatAt(100, true)) {
                log.info("[sire] inventory full — eating to make space for loot");
                ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
                return "loot-eat-for-space";
            }
        }

        // Loot EVERYTHING within range: value >= 0 catches all drops, plus untradeables and coins. Owner
        // filter off so it grabs the whole pile.
        LootingParameters all = new LootingParameters(0, Integer.MAX_VALUE, LOOT_RANGE, 1, 0, false, false);
        boolean looted = Rs2GroundItem.lootItemBasedOnValue(all);
        looted |= Rs2GroundItem.lootUntradables(all);
        looted |= Rs2GroundItem.lootCoins(all);
        if (looted) {
            ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
            return "loot";
        }

        // Nothing picked up this tick, but if anything's still on the ground keep at it (drop still
        // settling, mid-move from a dodge, or a menu click collided) — bounded by MAX_LOOT_MS.
        if (now - ctx.getLootStartMs() < MAX_LOOT_MS && nearestLoot() != null) {
            ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
            return "loot-wait";
        }

        if (now >= ctx.getLootDeadlineMs()) {
            // Looting done. If the restock trip is enabled, hand off to ReturnToSireAction (house ->
            // resupply/restore -> travel back); it resets the context when it's done.
            if (ctx.isRestockEnabled()) {
                log.info("[sire] loot complete — starting the between-kills restock trip");
                ctx.setPrepPending(true);
                return "loot-done-restock";
            }
            // No restock: walk back to the original spot and reset so phase 1 loops in place.
            WorldPoint pos = SireHelpers.playerLocation();
            if (pos != null && pos.distanceTo(SireConstants.ORIGINAL_POSITION) > 1) {
                SireHelpers.walkTo(SireConstants.ORIGINAL_POSITION);
                return "loot-walk-back";
            }
            log.info("[sire] loot complete, back at the original spot — ready for the next kill");
            ctx.reset();
            return "loot-complete";
        }
        return "loot-wait";
    }

    /** The nearest ground item within {@link #LOOT_RANGE}, or null if the ground is clear. */
    private GroundItem nearestLoot() {
        WorldPoint me = SireHelpers.playerLocation();
        if (me == null) {
            return null;
        }
        GroundItem nearest = null;
        int best = Integer.MAX_VALUE;
        for (GroundItem item : Rs2GroundItem.getGroundItems().values()) {
            if (item == null || item.getLocation() == null) {
                continue;
            }
            int dist = item.getLocation().distanceTo(me);
            if (dist <= LOOT_RANGE && dist < best) {
                best = dist;
                nearest = item;
            }
        }
        return nearest;
    }
}

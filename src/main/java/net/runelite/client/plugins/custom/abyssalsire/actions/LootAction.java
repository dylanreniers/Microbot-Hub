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
 * After the Sire dies (prayers are already dropped in onSireDeath), loots the drop: every untradeable
 * (the Unsired) plus anything at or above the configured value. Once looting is done it walks back to
 * the original spot and resets the context, so the next fight's phase 1 starts from there.
 */
@Slf4j
public class LootAction implements SireAction {

    private static final int LOOT_RANGE = 15;
    /** Grace for the drop to land after death. */
    private static final long INITIAL_LOOT_WAIT_MS = 2500;
    /** Deadline extension after each successful pickup. */
    private static final long LOOT_EXTEND_MS = 1500;

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
        }

        // Inventory full but there's loot we want on the ground? Eat one food to free a slot so we can
        // pick it up. Only eats when there's actually wanted loot down, and extends the loot deadline so
        // we don't time out while making room. (No food -> nothing we can do; fall through.)
        if (Rs2Inventory.isFull() && wantedLootPresent(ctx)) {
            if (Rs2Player.eatAt(100, true)) {
                log.info("[sire] inventory full — eating to make space for loot");
                ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
                return "loot-eat-for-space";
            }
        }

        // Always grab untradeables (Unsired / pet), then anything worth at least the configured value.
        LootingParameters untradeables = new LootingParameters(LOOT_RANGE, 1, 1, 0, false, false);
        boolean lootedUntradeables = Rs2GroundItem.lootUntradables(untradeables);

        LootingParameters valuable = new LootingParameters(
                ctx.getLootMinValue(), Integer.MAX_VALUE, LOOT_RANGE, 1, 0, false, false);
        boolean lootedValuable = Rs2GroundItem.lootItemBasedOnValue(valuable);

        if (lootedUntradeables || lootedValuable) {
            ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
            return "loot";
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

    /**
     * True if there's loot we actually want within range: anything at/above the configured value, or any
     * untradeable (e.g. the Unsired — coins are tradeable, so this excludes them). Used to decide whether
     * a full inventory is worth eating for.
     */
    private boolean wantedLootPresent(SireContext ctx) {
        if (Rs2GroundItem.isItemBasedOnValueOnGround(ctx.getLootMinValue(), LOOT_RANGE)) {
            return true;
        }
        WorldPoint me = SireHelpers.playerLocation();
        if (me == null) {
            return false;
        }
        for (GroundItem item : Rs2GroundItem.getGroundItems().values()) {
            if (item != null && !item.isTradeable() && item.getLocation() != null
                    && item.getLocation().distanceTo(me) <= LOOT_RANGE) {
                return true;
            }
        }
        return false;
    }
}

package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;

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
        return state.context().getPhase() == SirePhase.DEAD;
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();
        final long now = System.currentTimeMillis();
        if (ctx.getLootDeadlineMs() == 0) {
            ctx.setLootDeadlineMs(now + INITIAL_LOOT_WAIT_MS);
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
            // Looting done — walk back to the original spot before resetting, so phase 1 of the next
            // kill starts from the barrage position with clean pathing.
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
}

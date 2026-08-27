package net.runelite.client.plugins.custom.zulrah.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.zulrah.ZulrahConfig;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import javax.inject.Inject;

/**
 * After Zulrah dies, pick up the kill's drops. Only runs between fights (no active phase) while the
 * loot flag armed by {@link net.runelite.client.plugins.custom.zulrah.ZulrahScript#onZulrahDeath()}
 * is set. Loots one ground item per tick off the scene (see {@link #lootNextDrop()}); a deadline
 * (extended on each pickup) covers the delay before the drop spawns and stops us shortly after the
 * last item, so we don't idle forever if nothing is there.
 */
@Slf4j
public class LootAction implements ZulrahAction {

    /** How long to keep trying after death before giving up if we never find/loot anything. */
    public static final long INITIAL_LOOT_WAIT_MS = 3000L;
    /** Extra time granted after each successful pickup, so we keep going while loot remains. */
    private static final long LOOT_EXTEND_MS = 1500L;
    private static final int LOOT_RANGE = 20;
    /**
     * Substring of items we never loot. The empty butterfly jars we drop after releasing a moonlight
     * moth for prayer (see {@link DrinkPrayerAction}/{@link DropButterflyJarAction}) land within loot
     * range, so without this the loot pass would pick them straight back up — and DropButterflyJarAction
     * would drop them again, spinning forever.
     */
    private static final String LOOT_IGNORE = "Butterfly jar";

    private final ZulrahConfig config;

    @Inject
    public LootAction(ZulrahConfig config) {
        this.config = config;
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public String key() {
        return "loot";
    }

    @Override
    public boolean needsExecution(ZulrahState state) {
        FightContext ctx = state.context();
        // Only between fights: once the next Zulrah surfaces (phase set) combat takes over.
        return ctx.isLootPending() && ctx.getPhase() == null;
    }

    @Override
    public Object execute(ZulrahState state) {
        FightContext ctx = state.context();
        long now = System.currentTimeMillis();

        boolean looted = lootNextDrop();
        if (looted) {
            ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
            return "looting";
        }
        // Nothing to loot this tick: wait out the grace window (drop may not have spawned yet), then
        // stop. A full inventory also lands here, so we don't spin once we can't pick anything up.
        if (now >= ctx.getLootDeadlineMs()) {
            ctx.setLootPending(false);
            // Read the toggle LIVE (not a start-up snapshot) so flipping it in the config panel takes
            // effect without restarting, and so a stale/false snapshot can never silently swallow the
            // hand-off. When enabled, arm the between-kills bank-and-travel routine (ReturnToZulrahAction).
            boolean restock = config.restockBetweenKills();
            log.info("Loot pickup complete. Restock between kills is {}.", restock ? "ON — arming resupply" : "OFF");
            if (restock) {
                ctx.setPrepSkipBank(false); // between-kills restock is always the full bank + travel trip
                ctx.setPrepPending(true);
            }
            return "done";
        }
        return "waiting";
    }

    /**
     * Picks up the nearest lootable drop off the scene, skipping empty butterfly jars. Reads the scene
     * directly (like the old {@code Rs2GroundItem.lootAllItemBasedOnValue}) rather than the
     * GroundItemsPlugin's collected table, so looting works whether or not that plugin is enabled.
     * Loots one item per call; the deadline logic keeps us running while more remain.
     *
     * @return true if an item was interacted with this call
     */
    private boolean lootNextDrop() {
        RS2Item[] groundItems = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Rs2GroundItem.getAll(LOOT_RANGE))
                .orElse(new RS2Item[]{});
        for (RS2Item item : groundItems) {
            String name = item.getItem().getName();
            if (name != null && name.toLowerCase().contains(LOOT_IGNORE.toLowerCase())) {
                continue;
            }
            if (Rs2Inventory.isFull(name)) {
                continue;
            }
            return Rs2GroundItem.interact(item);
        }
        return false;
    }
}

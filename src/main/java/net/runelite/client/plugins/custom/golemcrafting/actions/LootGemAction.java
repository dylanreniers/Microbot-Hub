package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * Picks up a finished golem's OWN drops from the ground (never another player's): a Jeweller's chisel
 * (rare) is always taken; uncut gems are taken only when the gem-bag feature is on and then stashed into
 * the bag. Runs at a higher priority than {@link CraftGolemAction} so loot is grabbed before the next
 * golem starts. Guarded on inventory space so it never spams the "not enough inventory space" message.
 */
@Slf4j
public class LootGemAction implements GolemAction {

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String key() {
        return "loot-gem";
    }

    @Override
    public boolean needsExecution(GolemState state) {
        return state.context().getPhase() == GolemPhase.CRAFTING
                && GolemHelpers.groundLootPresent(state.context().isUseGemBag());
    }

    @Override
    public Object execute(GolemState state) {
        boolean useGemBag = state.context().isUseGemBag();

        // If the inventory is full because of gems we've picked up, stash them into the bag first to free
        // a slot, then loot next tick.
        if (Rs2Inventory.isFull() && useGemBag && GolemHelpers.hasLooseUncut()) {
            GolemHelpers.fillGemBag();
            return "fill-for-space";
        }

        String looted = GolemHelpers.lootOwnedOne(useGemBag);
        if (looted == null) {
            return "loot-none";
        }
        if (looted.equalsIgnoreCase(GolemConstants.JEWELLERS_CHISEL_NAME)) {
            log.info("[golem] picked up a Jeweller's chisel!");
            return "loot-chisel";
        }
        // A gem — stash it into the bag so it doesn't take a batch slot.
        state.context().setGemsLooted(state.context().getGemsLooted() + 1);
        if (useGemBag) {
            GolemHelpers.fillGemBag();
        }
        return "loot-" + looted;
    }
}

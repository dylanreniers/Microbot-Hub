package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;

/**
 * Loots the uncut gem a finished golem drops — but only when a gem bag is carried (per the user's rule),
 * since without one there's no inventory room for gems during a 25-sunstone trip. Runs at a higher
 * priority than {@link CraftGolemAction} so gems are grabbed before the next golem is started.
 */
@Slf4j
public class LootGemAction implements GolemAction {

    private static final int LOOT_RANGE = 6;
    private static final String[] GEM_NAMES = {
            "Uncut sapphire", "Uncut emerald", "Uncut ruby", "Uncut diamond"
    };

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
                && GolemHelpers.hasGemBag()
                && gemOnGround();
    }

    @Override
    public Object execute(GolemState state) {
        for (String gem : GEM_NAMES) {
            if (Rs2GroundItem.exists(gem, LOOT_RANGE) && Rs2GroundItem.loot(gem, LOOT_RANGE)) {
                state.context().setGemsLooted(state.context().getGemsLooted() + 1);
                return "loot-" + gem;
            }
        }
        return "loot-none";
    }

    private boolean gemOnGround() {
        for (String gem : GEM_NAMES) {
            if (Rs2GroundItem.exists(gem, LOOT_RANGE)) {
                return true;
            }
        }
        return false;
    }
}

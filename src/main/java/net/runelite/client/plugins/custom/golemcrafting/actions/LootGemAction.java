package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;

/**
 * Picks up a finished golem's drops from the ground. A Jeweller's chisel (rare) is ALWAYS taken; uncut
 * gems are only taken when a gem bag is carried (per the user's rule) — without one there's no inventory
 * room for gems during a 25-sunstone trip. Runs at a higher priority than {@link CraftGolemAction} so
 * loot is grabbed before the next golem is started.
 */
@Slf4j
public class LootGemAction implements GolemAction {

    private static final int LOOT_RANGE = 6;
    private static final String JEWELLERS_CHISEL = "Jeweller's chisel";
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
        if (state.context().getPhase() != GolemPhase.CRAFTING) {
            return false;
        }
        // Always act for a Jeweller's chisel; only for gems when a gem bag is carried.
        return chiselOnGround() || (GolemHelpers.hasGemBag() && gemOnGround());
    }

    @Override
    public Object execute(GolemState state) {
        // The rare Jeweller's chisel is always worth grabbing, gem bag or not.
        if (Rs2GroundItem.exists(JEWELLERS_CHISEL, LOOT_RANGE) && Rs2GroundItem.loot(JEWELLERS_CHISEL, LOOT_RANGE)) {
            log.info("[golem] picked up a Jeweller's chisel!");
            return "loot-chisel";
        }
        if (GolemHelpers.hasGemBag()) {
            for (String gem : GEM_NAMES) {
                if (Rs2GroundItem.exists(gem, LOOT_RANGE) && Rs2GroundItem.loot(gem, LOOT_RANGE)) {
                    state.context().setGemsLooted(state.context().getGemsLooted() + 1);
                    return "loot-" + gem;
                }
            }
        }
        return "loot-none";
    }

    private boolean chiselOnGround() {
        return Rs2GroundItem.exists(JEWELLERS_CHISEL, LOOT_RANGE);
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

package net.runelite.client.plugins.custom.customdemonicgorilla;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CustomDemonicGorillaLooterScript extends Script {
    int minFreeSlots = 0;

    public CustomDemonicGorillaLooterScript() {

    }

    public boolean run(CustomDemonicGorillaConfig config, GorillaContext context) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                GorillaContext.State status = context.getBotStatus();
                if (status == GorillaContext.State.BANKING || status == GorillaContext.State.TRAVEL_TO_GORILLAS) {
                    Microbot.pauseAllScripts.compareAndSet(true, false);
                    return;
                }
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;

                // When the inventory is full, eat one food to open a slot IF loot we actually want is still
                // on the ground. We can't lean on the loot library's own eatFoodForSpace here: it only fires
                // when a whole ground-item entry can't fit (0 free slots at entry time). A stack that
                // overflows mid-pickup (e.g. 3 sharks dropped, 2 free slots) passes its space check with room
                // to spare, grabs the 2 that fit, and leaves the rest — the eat never triggers and we stall.
                // Handling it ourselves also lets us gate on "is this loot worth a food", so we don't burn
                // food when the only thing left is junk (or ashes we won't pick up while full).
                boolean full = Rs2Inventory.isFull() || Rs2Inventory.emptySlotCount() <= minFreeSlots;
                if (full) {
                    if (!hasDesiredLootNearby(config) || Rs2Inventory.getInventoryFood().isEmpty()) {
                        return; // nothing we want within reach, or no food to make room — wait for a slot
                    }
                    if (Rs2Player.useFood()) {
                        Rs2Player.waitForAnimation(1200);
                    }
                }

                boolean looted = lootItemsOnName(config);
                if (config.scatterAshes()) {
                    looted |= lootAndScatterMalicious();
                }
                looted |= lootRunes(config);
                looted |= lootCoins(config);
                looted |= lootUntradeableItems(config);

                // Tell the combat pipeline looting is still active: push the loot-wait deadline out so it
                // keeps holding off the next gorilla until pickups stop (see AcquireTargetAction).
                if (looted) {
                    context.setLootDeadlineMs(System.currentTimeMillis() + GorillaContext.LOOT_PICKUP_GRACE_MS);
                }

            } catch (Exception ex) {
                System.out.println("Demonic Gorilla Looter (Custom): " + ex.getMessage());
            }

        }, 0, 200, TimeUnit.MILLISECONDS);
        return true;
    }

    /** Loot range shared by all the loot queries below (the {@code range} arg of their LootingParameters). */
    private static final int LOOT_RANGE = 15;

    /**
     * True when there's still loot we care about on the ground within loot range — the configured item
     * names, plus runes and coins. Used to decide whether a full inventory is worth eating a food for; we
     * deliberately ignore junk and the Malicious ashes (which we only pick up when NOT full) so we don't
     * burn food on things we won't keep. Substring/ownership matching mirrors {@code lootItemsBasedOnNames}.
     */
    private boolean hasDesiredLootNearby(CustomDemonicGorillaConfig config) {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) {
            return false;
        }
        Set<String> needles = new HashSet<>();
        String configured = config.lootItems();
        if (configured != null) {
            for (String name : configured.split(",")) {
                String trimmed = name.trim().toLowerCase();
                if (!trimmed.isEmpty()) {
                    needles.add(trimmed);
                }
            }
        }
        needles.add(" rune");
        needles.add("coins");
        if (needles.isEmpty()) {
            return false;
        }
        boolean myLootOnly = config.lootMyLootOnly();
        return Rs2GroundItem.getGroundItems().values().stream().anyMatch(gi -> {
            if (gi.getLocation() == null || gi.getLocation().distanceTo(me) >= LOOT_RANGE) {
                return false;
            }
            if (myLootOnly && gi.getOwnership() != net.runelite.api.TileItem.OWNERSHIP_SELF) {
                return false;
            }
            String name = gi.getName() == null ? "" : gi.getName().trim().toLowerCase();
            for (String needle : needles) {
                if (name.contains(needle)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean lootUntradeableItems(CustomDemonicGorillaConfig config) {
        LootingParameters untradeableItemsParams = new LootingParameters(
                15,
                1,
                1,
                minFreeSlots,
                false,
                config.lootMyLootOnly(),
                "untradeable"
        );
        untradeableItemsParams.setEatFoodForSpace(true);
        if (Rs2GroundItem.lootUntradables(untradeableItemsParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
            return true;
        }
        return false;
    }

    private boolean lootRunes(CustomDemonicGorillaConfig config) {
        LootingParameters runesParams = new LootingParameters(
                15,
                1,
                1,
                minFreeSlots,
                false,
                config.lootMyLootOnly(),
                " rune"
        );
        runesParams.setEatFoodForSpace(true);
        if (Rs2GroundItem.lootItemsBasedOnNames(runesParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
            return true;
        }
        return false;
    }

    private boolean lootCoins(CustomDemonicGorillaConfig config) {
        LootingParameters coinsParams = new LootingParameters(
                15,
                1,
                1,
                minFreeSlots,
                false,
                config.lootMyLootOnly(),
                "coins"
        );
        coinsParams.setEatFoodForSpace(true);
        if (Rs2GroundItem.lootCoins(coinsParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
            return true;
        }
        return false;
    }

    private boolean lootItemsOnName(CustomDemonicGorillaConfig config) {
        String configured = config.lootItems();
        if (configured == null || configured.trim().isEmpty()) {
            return false;
        }
        LootingParameters valueParams = new LootingParameters(
                15,
                1,
                1,
                minFreeSlots,
                false,
                config.lootMyLootOnly(),
                configured.trim().split(",")
        );
        valueParams.setEatFoodForSpace(true);
        if (Rs2GroundItem.lootItemsBasedOnNames(valueParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
            return true;
        }
        return false;
    }

    private boolean lootAndScatterMalicious() {
        String ashesName = "Malicious ashes";

        if (!Rs2Inventory.isFull() && Rs2GroundItem.lootItemsBasedOnNames(new LootingParameters(10, 1, 1, 0, false, true, ashesName))) {
            sleepUntil(() -> Rs2Inventory.contains(ashesName), 2000);

            if (Rs2Inventory.contains(ashesName)) {
                Rs2Inventory.interact(ashesName, "Scatter");
                sleep(Rs2Random.between(450, 750)); // Wait briefly for scattering action
            }
            return true;
        }
        return false;
    }
}

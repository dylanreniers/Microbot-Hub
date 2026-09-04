package net.runelite.client.plugins.custom.customdemonicgorilla;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.util.concurrent.TimeUnit;

public class CustomDemonicGorillaLooterScript extends Script {
    int minFreeSlots = 0;

    public CustomDemonicGorillaLooterScript() {

    }

    public boolean run(CustomDemonicGorillaConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (CustomDemonicGorillaScript.BOT_STATUS.equals(CustomDemonicGorillaScript.State.BANKING) || CustomDemonicGorillaScript.BOT_STATUS.equals(CustomDemonicGorillaScript.State.TRAVEL_TO_GORILLAS)) {
                    Microbot.pauseAllScripts.compareAndSet(true, false);
                    return;
                }
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;
                if (Rs2Inventory.isFull() || Rs2Inventory.emptySlotCount() <= minFreeSlots) return;
                lootItemsOnName(config);
                if (config.scatterAshes()) {
                    lootAndScatterMalicious();
                }
                lootRunes(config);
                lootCoins(config);
                lootUntradeableItems(config);

            } catch (Exception ex) {
                System.out.println("Demonic Gorilla Looter (Custom): " + ex.getMessage());
            }

        }, 0, 200, TimeUnit.MILLISECONDS);
        return true;
    }

    private void lootUntradeableItems(CustomDemonicGorillaConfig config) {
        LootingParameters untradeableItemsParams = new LootingParameters(
                15,
                1,
                1,
                minFreeSlots,
                false,
                config.lootMyLootOnly(),
                "untradeable"
        );
        if (Rs2GroundItem.lootUntradables(untradeableItemsParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
        }
    }

    private void lootRunes(CustomDemonicGorillaConfig config) {
        LootingParameters runesParams = new LootingParameters(
                15,
                1,
                1,
                minFreeSlots,
                false,
                config.lootMyLootOnly(),
                " rune"
        );
        if (Rs2GroundItem.lootItemsBasedOnNames(runesParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
        }
    }

    private void lootCoins(CustomDemonicGorillaConfig config) {
        LootingParameters coinsParams = new LootingParameters(
                15,
                1,
                1,
                minFreeSlots,
                false,
                config.lootMyLootOnly(),
                "coins"
        );
        if (Rs2GroundItem.lootCoins(coinsParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
        }
    }

    private void lootItemsOnName(CustomDemonicGorillaConfig config) {
        String configured = config.lootItems();
        if (configured == null || configured.trim().isEmpty()) {
            return;
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
        if (Rs2GroundItem.lootItemsBasedOnNames(valueParams)) {
            Microbot.pauseAllScripts.compareAndSet(true, false);
        }
    }

    private void lootAndScatterMalicious() {
        String ashesName = "Malicious ashes";

        if (!Rs2Inventory.isFull() && Rs2GroundItem.lootItemsBasedOnNames(new LootingParameters(10, 1, 1, 0, false, true, ashesName))) {
            sleepUntil(() -> Rs2Inventory.contains(ashesName), 2000);

            if (Rs2Inventory.contains(ashesName)) {
                Rs2Inventory.interact(ashesName, "Scatter");
                sleep(Rs2Random.between(450, 750)); // Wait briefly for scattering action
            }
        }
    }
}

package net.runelite.client.plugins.custom.mortmyrecollector;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.shared.FeroxService;
import net.runelite.client.plugins.util.AbstractScript;

import javax.inject.Inject;

@Slf4j
public class MortMyreFungusCollectorScript extends AbstractScript {

    private static final WorldPoint STANDING_LOCATION = new WorldPoint(3667, 3255, 0);
    private static final int FUNGI_ON_LOG = 3509;

    @Inject
    private FeroxService feroxService;

    @Override
    public void tick() {

        switch(getState()) {
            case BANKING:
                handleBanking();
                break;
            case PICKING:
                handlePicking();
            case WALKING_TO_LOGS:
                handleWalking();
        }
    }

    private void handlePicking() {
        if (!Rs2Inventory.isFull()) {
            Rs2Equipment.interact(EquipmentInventorySlot.WEAPON, "Bloom");
            sleep(1200, 1800);
            var fungiToPick = Microbot.getRs2TileObjectCache()
                    .query().
                    withId(FUNGI_ON_LOG)
                    .toListOnClientThread();

            fungiToPick.forEach(fungi -> {
                if (!Rs2Inventory.isFull()) {
                    fungi.click("Pick");
                    Rs2Inventory.waitForInventoryChanges(5000);
                }
            });

            if (!Rs2Player.getWorldLocation().equals(STANDING_LOCATION)) {
                Rs2Walker.walkFastCanvas(STANDING_LOCATION, true);
            }
        }

    }
    private void handleBanking() {
        feroxService.restoreAtFerox();
        Rs2Bank.openBank();
        sleepUntil(Rs2Bank::isOpen);
        Rs2Bank.depositAll();
        sleepUntil(Rs2Inventory::isEmpty);
        checkRingOfDueling();
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
    }

    private void handleWalking() {
        Rs2Walker.walkTo(STANDING_LOCATION, 0);
        sleepUntil(() -> Rs2Player.getWorldLocation().equals(STANDING_LOCATION));
    }

    private State getState() {
        if (Rs2Inventory.isFull()) {
            return State.BANKING;
        } else if (Rs2Inventory.isEmpty() && !Rs2Player.getWorldLocation().equals(STANDING_LOCATION)) {
            return State.WALKING_TO_LOGS;
        }

        return State.PICKING;
    }

    private void checkRingOfDueling() {
        if (Rs2Equipment.get(EquipmentInventorySlot.RING) == null) {
            log.info("Not wearing a ring.");
            if (Rs2Bank.count(ItemID.RING_OF_DUELING_8) <= 0) {
                throw new RuntimeException("No ring of dueling found in bank.");
            }

            if (!Rs2Inventory.contains(ItemID.RING_OF_DUELING_8)) {
                log.info("Withdrawing ring.");
                if (Rs2Bank.withdrawX(ItemID.RING_OF_DUELING_8, 1)) {
                    sleepUntil(() -> Rs2Inventory.contains(ItemID.RING_OF_DUELING_8));
                }
            }

            Rs2Tab.switchTo(InterfaceTab.INVENTORY);

            if (Rs2Inventory.contains(ItemID.RING_OF_DUELING_8) && Rs2Inventory.interact(ItemID.RING_OF_DUELING_8, "Wear")) {
                log.info("Wearing ring.");
                sleepUntil(() ->
                {
                    Rs2ItemModel ring = Rs2Equipment.get(EquipmentInventorySlot.RING);
                    return ring != null && ring.getName().contains("dueling");
                });
            }
        } else {
            log.info("Is already wearing a ring.");
        }
    }

    public enum State {
        WALKING_TO_LOGS,
        PICKING,
        BANKING,
    }
}

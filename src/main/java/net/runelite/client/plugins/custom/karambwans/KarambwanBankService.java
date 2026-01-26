package net.runelite.client.plugins.custom.karambwans;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;

import javax.inject.Singleton;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
@RequiredArgsConstructor
@Singleton
public class KarambwanBankService {

    public void handleBanking() {
        openBank();
        bankRefill();
        closeBank();
    }

    private void bankRefill() {
        Rs2Bank.depositAll("Raw karambwan");
        sleepUntil(() -> !Rs2Inventory.contains("Raw karambwan"));
        checkRingOfDueling();
    }

    private static void openBank() {
        if (!Rs2Bank.isOpen()) {
            log.info("Opening bank.");
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen);
        }
    }

    private static void closeBank() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
        }
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
}

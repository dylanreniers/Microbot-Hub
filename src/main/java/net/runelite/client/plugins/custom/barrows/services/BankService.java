package net.runelite.client.plugins.custom.barrows.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.custom.barrows.BarrowsConfig;
import net.runelite.client.plugins.custom.barrows.BarrowsScriptException;
import net.runelite.client.plugins.custom.barrows.MagicAttack;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import java.util.HashSet;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
@RequiredArgsConstructor
public class BankService {

    private static final String SPADE = "Spade";
    private final BarrowsConfig config;

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

    public void handleBanking(MagicAttack magicAttack, boolean outOfPoweredStaffCharges) {
        openBank();
        bankRefill(magicAttack, outOfPoweredStaffCharges);
        closeBank();
    }

    private void bankRefill(MagicAttack magicAttack, boolean outOfPoweredStaffCharges) {
        Set<String> itemsToKeep = new HashSet<>();
        config.inventorySetupMelee().getEquipment().forEach(i -> itemsToKeep.add(i.getName()));
        config.inventorySetupAhrim().getEquipment().forEach(i -> itemsToKeep.add(i.getName()));
        config.inventorySetupTunnels().getEquipment().forEach(i -> itemsToKeep.add(i.getName()));
        itemsToKeep.addAll(Set.of(
                "Teleport to house",
                "Spade",
                "Barrows teleport",
                "Law rune",
                "Dust rune",
                "Air rune",
                "Earth rune",
                "Rune pouch")
        );

        if (magicAttack != MagicAttack.POWERED_STAFF) {
            itemsToKeep.add(magicAttack.getRune());
        }

        log.info("Keeping items: {}", String.join(", ", itemsToKeep));
        Rs2Bank.depositAllExcept(itemsToKeep);

        if (outOfPoweredStaffCharges) {
            throw new BarrowsScriptException("Out of charges on staff");
        }

        checkPrayerRestorationPotions();
        checkFood();
        checkSpade();
        checkRingOfDueling();
    }

    private void checkPrayerRestorationPotions() {
        int prayerId = config.prayerRestoreType().getId();
        if (Rs2Inventory.count(prayerId) < config.prayerRestorationItems()) {
            if (Rs2Bank.getBankItem(prayerId) != null && Rs2Bank.getBankItem(prayerId).getQuantity() >= config.prayerRestorationItems()) {
                Rs2Bank.withdrawX(prayerId, config.prayerRestorationItems());
                sleepUntil(() -> Rs2Inventory.count(prayerId) == config.prayerRestorationItems());
            } else {
                throw new BarrowsScriptException("Out of prayer restoration potions");
            }
        }
    }

    private void checkFood() {
        int foodId = config.food().getId();
        if (Rs2Inventory.count(foodId) < config.foodAmount()) {
            if (Rs2Bank.getBankItem(foodId) != null && Rs2Bank.getBankItem(foodId).getQuantity() >= config.foodAmount()) {
                log.info("Withdrawing {}", config.foodAmount());
                Rs2Bank.withdrawX(foodId, config.foodAmount());
                sleepUntil(() -> Rs2Inventory.count(foodId) == config.foodAmount());
            } else {
                throw new BarrowsScriptException("Out of food");
            }
        }
    }

    private void checkSpade() {
        if (!Rs2Inventory.contains(SPADE)) {
            if (Rs2Bank.getBankItem(SPADE) != null && Rs2Bank.getBankItem(SPADE).getQuantity() >= 1) {
                Rs2Bank.withdrawOne(SPADE);
                sleepUntil(() -> Rs2Inventory.contains(SPADE));
                sleep(300, 1000);
            } else {
                throw new BarrowsScriptException("No spade found.");
            }
        }
    }

    private void checkRingOfDueling() {
        if (Rs2Equipment.get(EquipmentInventorySlot.RING) == null) {
            if (Rs2Bank.count(ItemID.RING_OF_DUELING_8) <= 0) {
                throw new BarrowsScriptException("No ring of dueling found in bank.");
            }

            if (!Rs2Inventory.contains(ItemID.RING_OF_DUELING_8)) {
                if (Rs2Bank.withdrawX(ItemID.RING_OF_DUELING_8, 1)) {
                    sleepUntil(() -> Rs2Inventory.contains(ItemID.RING_OF_DUELING_8));
                }
            }

            if (Rs2Inventory.contains(ItemID.RING_OF_DUELING_8) && Rs2Inventory.interact(ItemID.RING_OF_DUELING_8, "Wear")) {
                sleepUntil(() ->
                {
                    Rs2ItemModel ring = Rs2Equipment.get(EquipmentInventorySlot.RING);
                    return ring != null && ring.getName().contains("dueling");
                });
            }
        }
    }

    public boolean bankingRequirementsMet() {
        if (!Rs2Inventory.contains(SPADE)) {
            log.info("Missing spade");
            return false;
        }
        if (Rs2Equipment.get(EquipmentInventorySlot.RING) == null) {
            return false;
        }

        int foodId = config.food().getId();
        if (Rs2Inventory.count(foodId) < config.foodAmount()) {
            return false;
        }

        int prayerId = config.prayerRestoreType().getId();

        return Rs2Inventory.count(prayerId) >= config.prayerRestorationItems();
    }
}

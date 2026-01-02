package net.runelite.client.plugins.microbot.jewelleryenchant;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.jewelleryenchant.util.ElementalStaff;
import net.runelite.client.plugins.microbot.jewelleryenchant.util.Jewellery;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class JewelleryEnchantScript extends Script {

    private boolean initialized = false;

    @Override
    public void shutdown() {
        log.info("Shutting down.");
        initialized = false;
        mainScheduledFuture.cancel(true);
        mainScheduledFuture = null;
        super.shutdown();
    }

    public boolean run(JewelleryEnchantConfig config) {
        setFullView();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }

                Jewellery jewellery = config.jewellery();

                if (!jewellery.hasRequiredLevel()) {
                    Microbot.showMessage("Magic level too low.");
                    shutdown();
                    return;
                }

                if (!initialized) {
                    log.info("Not initialized. Restocking first, to ensure proper run.");
                    bankAndRestock(jewellery);
                    initialized = true;
                }

                createJewellery(jewellery);
                performEnchantment(jewellery);
                bankAndRestock(jewellery);

            } catch (Exception ex) {
                log.error("Error running Jewellery enchant script", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private static void createJewellery(Jewellery jewellery) {
        goToFurnace();
        smeltJewellery(jewellery);
    }

    private static void smeltJewellery(Jewellery jewellery) {
        sleepUntilTrue(() -> Rs2Widget.isGoldCraftingWidgetOpen() || Rs2Widget.isSilverCraftingWidgetOpen(), 500, 20000);
        Rs2Widget.clickWidget(jewellery.getName());
        sleepUntil(() -> !Rs2Inventory.contains(jewellery.getBarId()) && !Rs2Inventory.contains(jewellery.getGemId()), 30000);
        log.info("Done creating jewellery. Adding random sleep.");
        sleep(200, 10000);
        log.info("Done waiting.");
    }

    private static void goToFurnace() {
        Rs2TileObjectModel furnaceObject = new Rs2TileObjectQueryable()
                //.fromWorldView()
                .where(rs2TileObjectModel -> rs2TileObjectModel.getId() == ObjectID.FURNACE_16469)
                .nearest(40);

        if (furnaceObject == null) {
            log.info("Couldn't find furnace. Walking towards it.");
            Rs2Walker.walkTo(new WorldPoint(3097, 3494, 0)); // EDGEVILLE FURNACE
        }

        if (!Rs2Camera.isTileOnScreen(furnaceObject.getLocalLocation())) {
            log.info("Turning camera towards furnace.");
            Rs2Camera.turnTo(furnaceObject.getLocalLocation());
        }

        log.info("Crafting the jewellery. Waiting until finished.");
        furnaceObject.click("smelt");
    }

    private static void performEnchantment(Jewellery jewellery) {
        while (Rs2Inventory.hasItem(jewellery.getUnenchantedId())) {
            log.info("Enchanting jewellery.");
            Rs2Magic.cast(jewellery);
            log.info("Opening enchantment menu and clicking on spell. Adding random sleep.");
            sleep(200, 600);
            Rs2Inventory.interact(jewellery.getName(), "Use");
            log.info("Enchantment done. {} left to enchant. Adding sleep until next one can be enchanted", Rs2Inventory.count(jewellery.getUnenchantedId()));
            sleep(2000, 3000);
        }
    }

    private void setFullView() {
        if (Rs2Camera.getZoom() > 240) {
            Rs2Camera.setZoom(240);
        }
        if (Rs2Camera.getPitch() < 380) {
            Rs2Camera.setPitch(383);
        }
        int yaw = Rs2Camera.getYaw();
        if (yaw > 16 && yaw < 2032) {
            Rs2Camera.setYaw(0);
        }
    }

    private void bankAndRestock(Jewellery jewellery) {
        log.info("Restocking on items.");

        if (!Rs2Bank.isOpen()) {
            log.info("Opening bank.");
            Rs2Bank.openBank();
        }

        log.info("Depositing all items. Adding random sleep.");
        Rs2Bank.depositAllExcept(item -> item.getId() == Runes.COSMIC.getItemId() || item.getId() == jewellery.getMouldId());
        sleep(300, 500);

        log.info("Withdrawing all necessary items.");
        handleStaffEquipping(jewellery);
        ensureCorrectRunes(jewellery);
        handleJewelleryWithdrawal(jewellery);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 600);
    }

    private void handleStaffEquipping(Jewellery jewellery) {
        Runes elementalRune = jewellery.getElementalRune();
        if (isWearingElementalStaffFor(elementalRune)) {
            return;
        }

        int staffToWithdraw = -1;
        for (ElementalStaff staff : ElementalStaff.values()) {
            if (staff.providesRune(elementalRune) && Rs2Bank.hasItem(staff.getItemId())) {
                staffToWithdraw = staff.getItemId();
                break;
            }
        }

        if (staffToWithdraw != -1) {
            Rs2Bank.withdrawAndEquip(staffToWithdraw);
            sleepUntil(() -> isWearingElementalStaffFor(elementalRune), 3000);

            for (ElementalStaff staff : ElementalStaff.values()) {
                if (Rs2Inventory.hasItem(staff.getItemId())) {
                    // Check if the staff in inventory is the one we just equipped. If so, don't deposit it.
                    if (Rs2Equipment.isWearing(staff.getItemId())) continue;

                    Rs2Bank.depositAll(staff.getItemId());
                    sleep(200, 300);
                }
            }
        }
    }

    private boolean isWearingElementalStaffFor(Runes rune) {
        for (ElementalStaff staff : ElementalStaff.values()) {
            if (Rs2Equipment.isWearing(staff.getItemId()) && staff.providesRune(rune)) {
                return true;
            }
        }
        return false;
    }

    private void ensureCorrectRunes(Jewellery jewellery) {
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())) {
            if (Rs2Bank.hasItem(Runes.COSMIC.getItemId())) {
                Rs2Bank.withdrawAll(Runes.COSMIC.getItemId());
                sleepUntil(() -> Rs2Inventory.hasItem(Runes.COSMIC.getItemId()));
            } else {
                Microbot.showMessage("Out of Cosmic runes!");
                shutdown();
                return;
            }
        }
        for (Map.Entry<Runes, Integer> entry : jewellery.getRequiredRunes().entrySet()) {
            Runes rune = entry.getKey();
            int amount = entry.getValue();
            if (rune == Runes.COSMIC) continue;
            boolean needsRuneInInventory = !isWearingElementalStaffFor(rune);
            if (needsRuneInInventory && !Rs2Inventory.hasItemAmount(rune.getItemId(), amount)) {
                if (Rs2Bank.hasBankItem(rune.getItemId(), amount)) {
                    Rs2Bank.withdrawX(rune.getItemId(), amount);
                    sleep(200, 400);
                } else {
                    Microbot.showMessage("Out of " + rune.name() + " runes!");
                    shutdown();
                    return;
                }
            }
        }
    }

    private void handleJewelleryWithdrawal(Jewellery jewellery) {
        log.info("Looking for IDs in bank: {} and  {}", jewellery.getGemId(), jewellery.getBarId());
        log.info("Items found in bank:");
        Rs2Bank.bankItems().forEach(item -> log.info("Item ID: {}", item.getId()));
        if (!Rs2Bank.hasItem(jewellery.getGemId()) || !Rs2Bank.hasItem(jewellery.getBarId())) {
            log.info("Couldn't find bars or gems. Shutting down.");
            shutdown();
            return;
        }
        log.info("Withdrawing bars.");
        Rs2Bank.withdrawX(jewellery.getBarId(), 13);
        log.info("Withdrawing gems.");
        Rs2Bank.withdrawX(jewellery.getGemId(), 13);
        sleepUntil(() -> Rs2Inventory.hasItem(jewellery.getBarId()) && Rs2Inventory.hasItem(jewellery.getGemId()));
    }
}

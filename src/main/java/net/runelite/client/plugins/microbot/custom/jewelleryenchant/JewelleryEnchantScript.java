package net.runelite.client.plugins.microbot.custom.jewelleryenchant;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.custom.jewelleryenchant.util.ElementalStaff;
import net.runelite.client.plugins.microbot.custom.jewelleryenchant.util.Jewellery;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class JewelleryEnchantScript extends Script {

    private boolean initialized = false;

    @Inject
    private Rs2TileObjectCache rs2TileObjectCache;

    @Override
    public void shutdown() {
        log.info("Shutting down.");
        initialized = false;
        if (mainScheduledFuture != null) {
            mainScheduledFuture.cancel(true);
            mainScheduledFuture = null;
        }
        super.shutdown();
    }

    public boolean run(JewelleryEnchantConfig config) {
        setFullView();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                log.info("tick");
                if (!Microbot.isLoggedIn() || !super.run()) {
                    log.info("Not running?");
                    return;
                }

                Jewellery jewellery = config.jewellery();

                if (!jewellery.hasRequiredLevel()) {
                    Microbot.showMessage("Magic level too low.");
                    shutdown();
                    return;
                }

                handleStaffEquipping(jewellery);
                ensureCorrectRunes(jewellery);

                log.info("only enchantment? {}", config.onlyEnchant());
                if (!config.onlyEnchant()) {
                    bankAndRestock(jewellery);
                    createJewellery(jewellery);
                    if (jewellery.isAmulet()) {
                        addBallsOfWoolToAmulets(jewellery);
                    }
                    performEnchantment(jewellery);
                } else {
                    log.info("Starting enchantment only");
                    getJewelleryFromBank(jewellery);
                    performEnchantment(jewellery);
                }
            } catch (Exception ex) {
                log.error("Error running Jewellery enchant script", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private void createJewellery(Jewellery jewellery) {
        goToFurnace();
        smeltJewellery(jewellery);
    }

    private static void smeltJewellery(Jewellery jewellery) {
        sleepUntil(() -> Rs2Widget.isGoldCraftingWidgetOpen() || Rs2Widget.isSilverCraftingWidgetOpen());
        Rs2Widget.clickWidget(jewellery.getName());
        sleepUntil(() -> !Rs2Inventory.contains(jewellery.getBarId()) && !Rs2Inventory.contains(jewellery.getGemId()), 30000);
        log.info("Done creating jewellery. Adding random sleep.");
        sleep(200, 10000);
        log.info("Done waiting.");
    }

    private void goToFurnace() {
        Rs2TileObjectModel furnaceObject = getFurnace();
        if (furnaceObject == null) {
            log.info("Couldn't find furnace. Walking towards it.");
            Rs2Walker.walkTo(new WorldPoint(3097, 3494, 0)); // EDGEVILLE FURNACE
            furnaceObject = getFurnace();
        }

        if (!Rs2Camera.isTileOnScreen(furnaceObject.getLocalLocation())) {
            log.info("Turning camera towards furnace.");
            Rs2Camera.turnTo(furnaceObject.getLocalLocation());

        }

        log.info("Crafting the jewellery. Waiting until finished.");
        furnaceObject.click("smelt");
    }

    private Rs2TileObjectModel getFurnace() {
        return rs2TileObjectCache
                .query()
                .where(rs2TileObjectModel -> rs2TileObjectModel.getId() == ObjectID.FURNACE_16469)
                .nearestOnClientThread(40);
    }

    private static void performEnchantment(Jewellery jewellery) {
        while (Rs2Inventory.hasItem(jewellery.getUnenchantedId())) {
            log.info("Enchanting jewellery.");
            Rs2Magic.cast(jewellery.getMagicAction());
            log.info("Opening enchantment menu and clicking on spell. Adding random sleep.");
            sleep(200, 400);
            List<Rs2ItemModel> items = Rs2Inventory.all(model -> model.getId() == jewellery.getUnenchantedId());
            if (items.size() == 1) {
                Rs2Inventory.interact(items.get(0));
            } else if (items.size() > 1) {
                Rs2Inventory.interact(items.get(Rs2Random.between(0, items.size()))); //randomize which one to enchant
            }
            log.info("Enchantment done. {} left to enchant. Adding sleep until next one can be enchanted", Rs2Inventory.count(jewellery.getUnenchantedId()));
            sleep(2000, 2400);
        }

        log.info("Done enchanting all jewellery");
    }

    private void addBallsOfWoolToAmulets(Jewellery jewellery) {
        openBank();
        log.info("Withdrawing balls of wool.");
        Rs2Bank.withdrawX(ItemID.BALL_OF_WOOL, 13);
        sleepUntil(() -> Rs2Inventory.contains(ItemID.BALL_OF_WOOL, 13));
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        if (!Rs2Inventory.contains(jewellery.getName() + " (u)")) {
            log.error("Unstrung Amulet not found.");
        } else {
            Rs2Inventory.combine(jewellery.getName() + " (u)", "Ball of wool");
            sleepUntil(Rs2Dialogue::isInDialogue);
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            sleepUntil(() -> !Rs2Inventory.contains(jewellery.getUnstrungAmuletId()), 20000);
        }

        sleep(600, 1800);
    }

    private void getJewelleryFromBank(Jewellery jewellery) {
        openBank();
        Rs2Bank.depositAllExcept(item -> item.getId() == Runes.COSMIC.getItemId());
        sleep(300, 500);
        handleJewelleryToEnchantWithdrawal(jewellery);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
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
        openBank();
        Rs2Bank.depositAllExcept(item -> item.getId() == Runes.COSMIC.getItemId() || item.getId() == jewellery.getMouldId());
        sleep(300, 500);
        handleJewelleryWithdrawal(jewellery);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 600);
    }

    private void handleStaffEquipping(Jewellery jewellery) {
        Runes elementalRune = jewellery.getElementalRune();
        if (isWearingElementalStaffFor(elementalRune)) {
            return;
        }

        openBank();

        int staffToWithdraw = -1;
        for (ElementalStaff staff : ElementalStaff.values()) {
            if (staff.providesRune(elementalRune) && Rs2Bank.hasItem(staff.getItemId())) {
                staffToWithdraw = staff.getItemId();
                break;
            }
        }

        if (staffToWithdraw != -1) {
            Rs2Bank.withdrawAndEquip(staffToWithdraw);
            sleepUntil(() -> isWearingElementalStaffFor(elementalRune));

            for (ElementalStaff staff : ElementalStaff.values()) {
                if (Rs2Inventory.hasItem(staff.getItemId())) {
                    // Check if the staff in inventory is the one we just equipped. If so, don't deposit it.
                    if (Rs2Equipment.isWearing(staff.getItemId())) {
                        continue;
                    }

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
            openBank();
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
            if (rune == Runes.COSMIC) {
                continue;
            }
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

    private static void openBank() {
        if (!Rs2Bank.isOpen()) {
            log.info("Opening bank.");
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen);
        }
    }

    private void handleJewelleryToEnchantWithdrawal(Jewellery jewellery) {
        if (!Rs2Bank.hasItem(jewellery.getUnenchantedId())) {
            log.info("Couldn't find jewellery to enchant.");
            shutdown();
            return;
        }
        Rs2Bank.withdrawAll(jewellery.getUnenchantedId());
        sleepUntil(() -> Rs2Inventory.hasItem(jewellery.getUnenchantedId()));
        sleep(300, 1800);
    }

    private void handleJewelleryWithdrawal(Jewellery jewellery) {
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
        log.info("Everything is withdrawn");
    }
}

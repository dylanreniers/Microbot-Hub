package net.runelite.client.plugins.custom.fletching;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.custom.fletching.enums.FletchingMaterial;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.custom.fletching.enums.FletchingItem;
import net.runelite.client.plugins.custom.fletching.enums.FletchingMode;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class FletchingScript extends Script {

    // The fletching interface widget group ID
    private static final int FLETCHING_WIDGET_GROUP_ID = 17694736;

    String primaryItemToFletch = "";
    String secondaryItemToFletch = "";

    FletchingModel model = new FletchingModel();
    FletchingMode fletchingMode;

    public void run(FletchingConfig config) {
        fletchingMode = config.fletchingMode();
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyFletchingSetup();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run() || !super.isRunning() || Rs2AntibanSettings.actionCooldownActive || !configChecks(config)) {
                    return;
                }

                if (config.Afk() && Rs2Random.between(1, 100) < 10) {
                    sleep(1000, 15000);
                }

                boolean hasRequirementsToFletch;
                boolean hasRequirementsToBank;
                primaryItemToFletch = fletchingMode.getItemName();

                secondaryItemToFletch = fletchingMode == FletchingMode.STRUNG
                        ? config.fletchingMaterial().getName() + " " + config.fletchingItem().getContainsInventoryName() + " (u)"
                        : (config.fletchingMaterial().getName() + " logs").trim();
                hasRequirementsToFletch = Rs2Inventory.hasItem(primaryItemToFletch)
                        && Rs2Inventory.hasItemAmount(secondaryItemToFletch, config.fletchingItem().getAmountRequired());
                hasRequirementsToBank = !Rs2Inventory.hasItem(primaryItemToFletch)
                        || !Rs2Inventory.hasItemAmount(secondaryItemToFletch, config.fletchingItem().getAmountRequired());

                if (hasRequirementsToFletch) {
                    fletch(config);
                }
                if (hasRequirementsToBank) {
                    bankItems(config);
                }

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
    }

    private void bankItems(FletchingConfig config) {
        Rs2Bank.openBank();

        // Deposit items based on the fletching mode
        switch (fletchingMode) {
            case STRUNG:
                Rs2Bank.depositAll();
                break;
            default:
                Rs2Bank.depositAll(config.fletchingItem().getContainsInventoryName());
                Rs2Inventory.waitForInventoryChanges(5000);
                break;
        }

        // Check if the primary item is available
        if (!Rs2Bank.hasItem(primaryItemToFletch) && !Rs2Inventory.hasItem(primaryItemToFletch)) {
            Rs2Bank.closeBank();
            Microbot.status = "[Shutting down] - Reason: " + primaryItemToFletch + " not found in the bank.";
            Microbot.showMessage(Microbot.status);
            shutdown();
            return;
        }

        // Ensure the inventory isn't full without the primary item
        if (!Rs2Inventory.hasItem(primaryItemToFletch)) {
            Rs2Bank.depositAll();
        }

        // Withdraw the primary item if not already in the inventory
        if (!Rs2Inventory.hasItem(primaryItemToFletch)) {
            Rs2Bank.withdrawX(primaryItemToFletch, fletchingMode.getAmount(), true);
        }

        // Check if the secondary item is available
        if (!Rs2Bank.hasItem(secondaryItemToFletch)) {
            if (fletchingMode == FletchingMode.UNSTRUNG_STRUNG && Rs2Bank.hasBankItem("bow string")) {
                Rs2Bank.depositAll();
                fletchingMode = FletchingMode.STRUNG;
                return;
            }
            Rs2Bank.closeBank();
            Microbot.status = "[Shutting down] - Reason: " + secondaryItemToFletch + " not found in the bank.";
            Microbot.showMessage(Microbot.status);
            shutdown();
            return;
        }

        // Withdraw the secondary item if not already in the inventory
        if (!Rs2Inventory.hasItem(secondaryItemToFletch)) {
            if (fletchingMode == FletchingMode.STRUNG) {
                Rs2Bank.withdrawDeficit(secondaryItemToFletch, fletchingMode.getAmount());
            } else {
                Rs2Bank.withdrawAll(secondaryItemToFletch);
            }
        }
        if (Rs2AntibanSettings.naturalMouse) {
            // Testing if completing the mouse movement before the final item check improves the overall flow.
            // This should allow time for the inventory to update while the mouse is moving.
            // Enhances the bot's behavior to appear more natural and less automated.
            Widget closeButton = Rs2Widget.getWidget(786434).getChild(11);
            Point closePoint = Rs2UiHelper.getClickingPoint(closeButton != null ? closeButton.getBounds() : null, true);
            Rs2Random.waitEx(200, 100);
            Microbot.naturalMouse.moveTo(closePoint.getX(), closePoint.getY());
        }

        // Final check to ensure both items are in the inventory
        if (!Rs2Inventory.hasItem(primaryItemToFletch) || !Rs2Inventory.hasItem(secondaryItemToFletch)) {
            Microbot.log("waiting for inventory changes.");
            Rs2Inventory.waitForInventoryChanges(5000);
        }

        Rs2Random.waitEx(200, 100);
        Rs2Bank.closeBank();
    }


    private void fletch(FletchingConfig config) {
        Rs2Inventory.combine(primaryItemToFletch, secondaryItemToFletch);
        sleepUntil(() -> Objects.nonNull(Rs2Widget.getWidget(FLETCHING_WIDGET_GROUP_ID)));
        Rs2Keyboard.keyPress(config.fletchingItem().getOption(config.fletchingMaterial(), fletchingMode));
        sleepUntil(() -> !Rs2Inventory.hasItem(secondaryItemToFletch), 60000);
        Rs2Antiban.actionCooldown();
        Rs2Antiban.takeMicroBreakByChance();
        Rs2Bank.preHover();
    }

    private boolean configChecks(FletchingConfig config) {
        if (config.fletchingMaterial() == FletchingMaterial.REDWOOD && config.fletchingItem() != FletchingItem.SHIELD) {
            Microbot.getNotifier().notify("[Wrong Configuration] You can only make shields with redwood logs.");
            shutdown();
            return false;
        }
        return true;
    }

    @Override
    public void shutdown() {

        Rs2Antiban.resetAntibanSettings();
        super.shutdown();
    }

    @Setter
    @Getter
    public static class FletchingModel {
        private FletchingItem fletchingItem;
        private FletchingMaterial fletchingMaterial;
    }
}

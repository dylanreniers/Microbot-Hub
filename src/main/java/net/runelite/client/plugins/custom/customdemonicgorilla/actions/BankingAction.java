package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaConfig;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaPlugin;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.BankingStep;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetupsItem;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * BANKING phase: restock the banking setup, top up food/prayer, then hand off to travel. Only runs
 * while the run is in {@link State#BANKING}.
 *
 * <p>Restock mirrors the Zulrah script's proven approach and deliberately avoids
 * {@code loadInventory()}/{@code doesInventoryMatch()} — those are slot- and quantity-strict and fail
 * on real setups (scattered food slots, part-used potions, carried swap gear). Instead we equip the
 * gear, deposit everything not in the setup, top each setup item up by quantity with
 * {@link Rs2Bank#withdrawDeficit}, and verify by item PRESENCE.
 */
@Slf4j
public class BankingAction implements GorillaAction {

    /** Consecutive failed restock attempts tolerated before concluding the bank is genuinely missing
     *  supplies (rather than transient sync timing) and stopping. */
    private static final int MAX_BANK_LOAD_ATTEMPTS = 5;

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String key() {
        return "banking";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        return state.context().getBotStatus() == State.BANKING;
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        CustomDemonicGorillaConfig config = state.config();
        Rs2InventorySetup setup = ctx.getBankingGear();

        switch (ctx.getBankingStep()) {
            case BANK:
                // Already kitted (gear on + every supply carried)? Skip straight to travel. We check
                // item PRESENCE, not doesInventoryMatch() — the latter is slot/quantity-strict and flaky.
                if (setup.doesEquipmentMatch() && hasAllSetupItems(setup)) {
                    ctx.setBankingStep(BankingStep.BANK);
                    ctx.setBotStatus(State.TRAVEL_TO_GORILLAS);
                    return State.TRAVEL_TO_GORILLAS;
                }
                if (!Rs2Bank.isOpen()) {
                    Microbot.status = "Walking to bank...";
                    Rs2Bank.walkToBank();
                    Rs2Bank.openBank();
                    sleepUntil(Rs2Bank::isOpen, 5000);
                }
                // Only advance once the bank is genuinely open (otherwise the restock runs against a
                // closed bank and gets stuck). Still walking there -> stay in BANK and retry next tick.
                if (Rs2Bank.isOpen()) {
                    ctx.setBankingStep(BankingStep.LOAD_INVENTORY);
                }
                break;

            case LOAD_INVENTORY:
                if (!Rs2Bank.isOpen()) {
                    ctx.setBankingStep(BankingStep.BANK);
                    break;
                }
                Microbot.status = "Restocking...";

                // 1. Equip the banking gear. Prefer wearEquipment() (equips pieces we already carry — e.g.
                //    after switching weapons mid-fight); fall back to loadEquipment() to pull anything not
                //    carried from the bank.
                if (!setup.doesEquipmentMatch()) {
                    setup.wearEquipment();
                    sleepUntil(setup::doesEquipmentMatch, 3000);
                    if (!setup.doesEquipmentMatch()) {
                        setup.loadEquipment();
                        sleepUntil(setup::doesEquipmentMatch, 3000);
                    }
                }

                // 2. Desired inventory as id -> total quantity.
                Map<Integer, Integer> desired = buildDesired(setup);

                // 3. Deposit everything that is NOT part of the setup (loot, part-used potions, ...).
                Rs2Bank.depositAllExcept(item -> item != null && desired.containsKey(item.getId()));
                sleep(Rs2Random.between(400, 800));

                // 4. Top each setup item up to its target quantity (slot-agnostic, unlike loadInventory()).
                for (Map.Entry<Integer, Integer> want : desired.entrySet()) {
                    Rs2Bank.withdrawDeficit(want.getKey(), want.getValue());
                    Rs2Inventory.waitForInventoryChanges(1800);
                }

                // 5. Heal / restore before travelling, then refill whatever that consumed.
                topUpBeforeTravel(config);
                for (Map.Entry<Integer, Integer> want : desired.entrySet()) {
                    Rs2Bank.withdrawDeficit(want.getKey(), want.getValue());
                    Rs2Inventory.waitForInventoryChanges(1200);
                }

                Rs2Bank.closeBank();

                // 6. Verify by presence: gear on + every setup item carried.
                if (setup.doesEquipmentMatch() && hasAllSetupItems(setup)) {
                    ctx.setBankLoadAttempts(0);
                    ctx.setBankingStep(BankingStep.BANK);
                    ctx.setBotStatus(State.TRAVEL_TO_GORILLAS);
                } else {
                    int attempts = ctx.getBankLoadAttempts() + 1;
                    ctx.setBankLoadAttempts(attempts);
                    GorillaHelpers.logOnce(ctx, "Banking restock incomplete (attempt " + attempts + "/"
                            + MAX_BANK_LOAD_ATTEMPTS + "): equipmentMatch=" + setup.doesEquipmentMatch()
                            + " missingItemId=" + firstMissingItemId(setup) + "; retrying.");
                    if (attempts >= MAX_BANK_LOAD_ATTEMPTS) {
                        Microbot.showMessage("Failed to restock the banking setup after " + MAX_BANK_LOAD_ATTEMPTS
                                + " attempts. Make sure your bank contains every item in the setup. Stopping.");
                        Microbot.stopPlugin(CustomDemonicGorillaPlugin.class);
                    }
                    // else: stay in LOAD_INVENTORY and retry next tick.
                }
                break;
        }
        return ctx.getBankingStep();
    }

    /** The setup's inventory as id -> total quantity, skipping dummies/blank rows. */
    private static Map<Integer, Integer> buildDesired(Rs2InventorySetup setup) {
        Map<Integer, Integer> desired = new LinkedHashMap<>();
        for (InventorySetupsItem item : setup.getInventoryItems()) {
            if (item == null || item.getId() <= 0 || InventorySetupsItem.itemIsDummy(item)) {
                continue;
            }
            desired.merge(item.getId(), Math.max(1, item.getQuantity()), Integer::sum);
        }
        return desired;
    }

    /** True if every non-dummy setup inventory item is currently carried (presence, not exact slots). */
    private static boolean hasAllSetupItems(Rs2InventorySetup setup) {
        return firstMissingItemId(setup) == -1;
    }

    /** The id of the first setup inventory item missing from the inventory, or -1 if all present. */
    private static int firstMissingItemId(Rs2InventorySetup setup) {
        for (Integer id : buildDesired(setup).keySet()) {
            if (!Rs2Inventory.hasItem(id)) {
                return id;
            }
        }
        return -1;
    }

    /** Eat to a safe HP and top prayer up before the trip; bounded so it can't loop forever. */
    private static void topUpBeforeTravel(CustomDemonicGorillaConfig config) {
        for (int i = 0; i < 6; i++) {
            boolean ate = Rs2Player.eatAt(80);
            boolean drank = Rs2Player.drinkPrayerPotionAt(config.minEatPercent());
            if (!ate && !drank && Rs2Player.getHealthPercentage() >= 70) {
                break;
            }
            sleep(Rs2Random.between(600, 1000));
        }
    }
}

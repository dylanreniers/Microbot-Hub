package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Refills the fur pouch at the Wyrmscraig bank chest (62390) when it runs dry. The chest isn't a
 * registered Microbot bank, so it's opened directly by object. Furs are withdrawn from the bank, the
 * pouch is Filled from them, and the leftovers deposited so the inventory returns clean for mining.
 */
@Slf4j
public class BankRefillAction implements GolemAction {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String key() {
        return "bank";
    }

    @Override
    public boolean needsExecution(GolemState state) {
        return state.context().getPhase() == GolemPhase.BANKING;
    }

    @Override
    public Object execute(GolemState state) {
        GolemContext ctx = state.context();
        ctx.setStatus("Refilling furs at the bank");

        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) {
            return "bank-no-loc";
        }
        if (me.distanceTo(GolemConstants.BANK_CHEST_TILE) > 4) {
            Rs2Walker.walkTo(GolemConstants.BANK_CHEST_TILE, 3);
            return "bank-walk";
        }

        if (!Rs2Bank.isOpen()) {
            GameObject chest = Rs2GameObject.getGameObject(GolemConstants.BANK_CHEST);
            if (chest == null) {
                Rs2Walker.walkTo(GolemConstants.BANK_CHEST_TILE, 2);
                return "bank-find-chest";
            }
            // Try the standard bank-open, then fall back to "Use" (chest's default open action).
            if (!Rs2Bank.openBank(chest)) {
                Rs2GameObject.interact(chest, "Use");
                sleepUntil(Rs2Bank::isOpen, 3000);
            }
            return "bank-open";
        }

        String furName = ctx.getFurName();
        if (!Rs2Bank.hasBankItem(furName)) {
            log.warn("[golem] no '{}' in the bank to refill with", furName);
            Rs2Bank.closeBank();
            ctx.setFurRemaining(0); // MINING's empty-bank guard will stop after retrying
            ctx.setPhase(GolemPhase.MINING);
            return "bank-no-furs";
        }

        // Clear the inventory of everything but the tools/pouch/gem bag, then load furs and Fill.
        Rs2Bank.depositAllExcept(GolemConstants.CHISEL, GolemConstants.HAMMER,
                GolemConstants.FUR_POUCH_OPEN, GolemConstants.FUR_POUCH_CLOSED,
                GolemConstants.GEM_BAG, GolemConstants.GEM_BAG_OPEN);
        Rs2Bank.withdrawAll(furName);
        sleepUntil(() -> Rs2Inventory.hasItem(furName), 2000);

        Rs2Inventory.interact(GolemConstants.FUR_POUCH_OPEN, GolemConstants.ACTION_FILL);
        sleepUntil(() -> !Rs2Inventory.hasItem(furName), 2000);

        // Any furs that didn't fit go back, then leave clean.
        Rs2Bank.depositAll(furName);
        Rs2Bank.closeBank();

        log.info("[golem] pouch refilled — resuming");
        ctx.setFurRemaining(-1); // re-Check the new count in MINING
        ctx.setTripActive(false);
        ctx.setPhase(GolemPhase.MINING);
        return "bank-done";
    }
}

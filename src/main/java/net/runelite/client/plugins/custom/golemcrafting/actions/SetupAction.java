package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * One-time preflight: verify the required tools are present, make sure the fur pouch is open (furs are
 * auto-pulled from an OPEN pouch on Insert-core), then hand off to the mining phase. Fails fast with a
 * message if a tool is missing.
 */
@Slf4j
public class SetupAction implements GolemAction {

    @Override
    public int order() {
        return 0;
    }

    @Override
    public String key() {
        return "setup";
    }

    @Override
    public boolean needsExecution(GolemState state) {
        return state.context().getPhase() == GolemPhase.SETUP;
    }

    @Override
    public Object execute(GolemState state) {
        GolemContext ctx = state.context();

        if (!GolemHelpers.hasChisel()) {
            return stop(ctx, "No chisel (regular or jeweller's) in inventory.");
        }
        if (!Rs2Inventory.hasItem(GolemConstants.HAMMER)) {
            return stop(ctx, "No hammer in inventory.");
        }
        if (!GolemHelpers.hasFurPouch()) {
            return stop(ctx, "No large fur pouch in inventory.");
        }

        // Ensure the pouch is open so furs feed the golem automatically. The closed pouch (29303) has an
        // "Open" action; once open it becomes 29470.
        if (Rs2Inventory.hasItem(GolemConstants.FUR_POUCH_CLOSED)
                && !Rs2Inventory.hasItem(GolemConstants.FUR_POUCH_OPEN)) {
            Rs2Inventory.interact(GolemConstants.FUR_POUCH_CLOSED, GolemConstants.ACTION_OPEN);
            return "opening-pouch";
        }

        log.info("[golem] setup ok — chisel/hammer/pouch present, gem bag: {}", GolemHelpers.hasGemBag());
        // Batch size is computed at the start of mining, once the fur count is known (via a pouch Check).
        ctx.setTripActive(false);
        ctx.setFurRemaining(-1);
        ctx.setPhase(GolemPhase.MINING);
        ctx.setStatus("Setup complete");
        return "setup-ok";
    }

    private Object stop(GolemContext ctx, String reason) {
        log.warn("[golem] setup failed: {}", reason);
        Microbot.showMessage("Golem Crafting: " + reason);
        ctx.setStatus(reason);
        ctx.setPhase(GolemPhase.STOPPED);
        return "setup-failed";
    }
}

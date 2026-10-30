package net.runelite.client.plugins.custom.cerberus.actions;

import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * Drops the empty Butterfly jars left behind by released moonlight moths, one per tick (one click per
 * tick), and never while a protection flick is due. {@link LootAction} ignores them so they aren't picked
 * straight back up.
 */
public class DropJarsAction implements CerberusAction {

    @Override
    public int order() {
        return 600;
    }

    @Override
    public String key() {
        return "drop-jars";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        CerberusContext.Phase phase = state.context().getPhase();
        return (phase == CerberusContext.Phase.FIGHT || phase == CerberusContext.Phase.LOOT)
                && !state.context().isPrayerCritical()
                && Rs2Inventory.hasItem(CerberusConstants.BUTTERFLY_JAR, true);
    }

    @Override
    public Object execute(CerberusState state) {
        if (!state.claimClick()) {
            return false;
        }
        return Rs2Inventory.drop(CerberusConstants.BUTTERFLY_JAR, true);
    }
}

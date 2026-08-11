package net.runelite.client.plugins.custom.microhunter.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * Keeps space free by releasing caught ferrets when the inventory is nearly full. Ferrets don't
 * stack, so a full inventory would otherwise stall the whole loop. Releases one per tick (the action
 * re-arms next tick until none remain), which keeps it non-blocking. Chinchompas stack and never
 * need this.
 */
@Slf4j
public class DropCatchAction implements HunterAction {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String key() {
        return "drop-catch";
    }

    @Override
    public boolean needsExecution(HunterState state) {
        if (state.busy() || state.breakImminent()) {
            return false;
        }
        return Rs2Inventory.emptySlotCount() <= 1 && Rs2Inventory.contains(ItemID.FERRET);
    }

    @Override
    public Object execute(HunterState state) {
        boolean released = Rs2Inventory.interact(ItemID.FERRET, "Release");
        if (released) {
            log.info("Releasing a ferret to free inventory space.");
        }
        return released;
    }
}

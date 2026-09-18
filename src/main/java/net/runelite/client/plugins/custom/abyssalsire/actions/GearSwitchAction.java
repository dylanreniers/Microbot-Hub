package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;

/**
 * Swaps to the phase's setup (range for phase 1, melee for phases 2-3). Equipping is mouseless and
 * does not interrupt movement, but the combat actions hold their attack click until the gear matches
 * (see {@link SireHelpers#gearReady}), so this always has a free tick to equip a mismatched piece.
 * {@code wearEquipment()} equips the mismatched pieces per call.
 */
@Slf4j
public class GearSwitchAction implements SireAction {

    @Override
    public int order() {
        return 400;
    }

    @Override
    public String key() {
        return "gear-switch";
    }

    @Override
    public boolean needsExecution(SireState state) {
        Rs2InventorySetup setup = SireHelpers.desiredSetup(state.context());
        return setup != null && !setup.doesEquipmentMatch();
    }

    @Override
    public Object execute(SireState state) {
        Rs2InventorySetup setup = SireHelpers.desiredSetup(state.context());
        log.info("[sire] equipping {} setup", state.context().getPhase() == SirePhase.PHASE1 ? "range" : "melee");
        setup.wearEquipment();
        return setup.doesEquipmentMatch();
    }
}

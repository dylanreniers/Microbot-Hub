package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Swaps to the phase's setup (range for phase 1, melee for phases 2-3). Equipping is mouseless and
 * does not interrupt movement, but the combat actions hold their attack click until the gear matches
 * (see {@link SireHelpers#gearReady}), so this always has a free tick to equip a mismatched piece.
 * {@code wearEquipment()} equips the mismatched pieces per call.
 *
 * <p>Before a kill starts, the swap is HELD until the pre-kill eat is done (EatAction, order 100):
 * eating first heals and frees inventory slots so the gear swap always has room.
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
        SireContext ctx = state.context();
        Rs2InventorySetup setup = SireHelpers.desiredSetup(ctx);
        if (setup == null || setup.doesEquipmentMatch()) {
            return false;
        }
        // Eat first: hold the swap while a pre-kill top-up is still pending, so there's inventory room.
        return !prekillEatPending(ctx);
    }

    @Override
    public Object execute(SireState state) {
        Rs2InventorySetup setup = SireHelpers.desiredSetup(state.context());
        log.info("[sire] equipping {} setup", state.context().getPhase() == SirePhase.PHASE1 ? "range" : "melee");
        setup.wearEquipment();
        return setup.doesEquipmentMatch();
    }

    /** True while, before a kill, we still need to (and can) eat up to the start-kill HP threshold. */
    private boolean prekillEatPending(SireContext ctx) {
        return ctx.getPhase() == SirePhase.PHASE1
                && !ctx.isFightStarted()
                && Rs2Player.getHealthPercentage() <= ctx.getStartKillMinHpPercent()
                && !Rs2Inventory.getInventoryFood().isEmpty();
    }
}

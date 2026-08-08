package net.runelite.client.plugins.custom.zulrah.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;

/**
 * Swaps to the correct setup for the phase, INLINE on the tick thread (never a worker thread) so it
 * can't drive the mouse at the same time as attacking/walking. When this action executes, the
 * combat actions this tick see {@code state.executed("equip-gear") == true} and stand down, so the
 * swap and combat never overlap. Bounded so genuinely-missing gear doesn't block combat forever.
 */
@Slf4j
public class EquipGearAction implements ZulrahAction {

    static final String KEY = "equip-gear";
    private static final int MAX_GEAR_SWAP_ATTEMPTS = 2;

    @Override
    public int order() {
        return 600;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean needsExecution(ZulrahState state) {
        FightContext ctx = state.context();
        if (ctx.getPhase() == null || ctx.getGearSwapAttempts() >= MAX_GEAR_SWAP_ATTEMPTS) {
            return false;
        }
        // Move into position before switching gear, so we don't stand swapping in a cloud / melee
        // range. Until we're on the stand tile, the positioning actions run and walk us there.
        if (!ZulrahHelpers.atTargetTile(ctx)) {
            return false;
        }
        Rs2InventorySetup setup = ZulrahHelpers.desiredSetup(ctx, ctx.getPhase());
        return setup != null && !setup.doesEquipmentMatch();
    }

    @Override
    public Object execute(ZulrahState state) {
        FightContext ctx = state.context();
        Rs2InventorySetup setup = ZulrahHelpers.desiredSetup(ctx, ctx.getPhase());
        if (ctx.getGearSwapAttempts() == 0) {
            log.info("Changing to {} setup", setup == ctx.getMagicSetup() ? "magic" : "range");
        }
        ctx.setGearSwapAttempts(ctx.getGearSwapAttempts() + 1);
        setup.wearEquipment();
        boolean ready = setup.doesEquipmentMatch();
        if (ctx.getGearSwapAttempts() >= MAX_GEAR_SWAP_ATTEMPTS && !ready) {
            log.warn("Equipment swap incomplete (missing items?) — continuing without it.");
        }
        return ready;
    }
}

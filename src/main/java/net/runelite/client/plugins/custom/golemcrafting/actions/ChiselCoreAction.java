package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Chisels one core per click out of the mined sunstone until the batch has {@code golemsTarget} cores
 * (leaving {@code golemsTarget * 4} sunstone as golem bodies). No Make-X interface — a chisel-on-
 * sunstone yields exactly one core.
 */
@Slf4j
public class ChiselCoreAction implements GolemAction {

    @Override
    public int order() {
        return 400;
    }

    @Override
    public String key() {
        return "chisel";
    }

    @Override
    public boolean needsExecution(GolemState state) {
        return state.context().getPhase() == GolemPhase.CHISELING;
    }

    @Override
    public Object execute(GolemState state) {
        GolemContext ctx = state.context();

        int target = ctx.getGolemsTarget();

        // Blocking loop: chisel exactly one core per click, waiting for it to register before the next,
        // so we never overshoot (the fast tick would otherwise queue extra chisels before the count
        // updates, e.g. making 6 cores instead of 5).
        while (GolemHelpers.coreCount() < target
                && GolemHelpers.sunstoneCount() > 0
                && Microbot.isLoggedIn()
                && ctx.getPhase() == GolemPhase.CHISELING
                && !Thread.currentThread().isInterrupted()) {
            int before = GolemHelpers.coreCount();
            ctx.setStatus("Chiselling cores (" + before + "/" + target + ")");
            Rs2Inventory.combine(GolemConstants.CHISEL, GolemConstants.SUNSTONE);
            sleepUntil(() -> GolemHelpers.coreCount() > before, () -> {
            }, 3000, 50);
        }

        int cores = GolemHelpers.coreCount();
        if (cores >= target || GolemHelpers.sunstoneCount() <= 0) {
            log.info("[golem] chiselled {} cores — moving to crafting", cores);
            ctx.setLastCoreCount(cores);
            ctx.setPhase(GolemPhase.CRAFTING);
        }
        return "chisel-loop";
    }
}

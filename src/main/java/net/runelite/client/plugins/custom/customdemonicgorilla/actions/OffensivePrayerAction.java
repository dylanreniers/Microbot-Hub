package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.ArmorEquiped;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/**
 * FIGHTING phase: activates the best offensive prayer for the current combat style, when the option
 * is enabled. Corresponds to the old {@code activateOffensivePrayer}. Runs last so it reflects the
 * gear this tick's {@link GearSwitchAction}/{@link GorillaAttacksAction} settled on.
 */
@Slf4j
public class OffensivePrayerAction implements GorillaAction {

    @Override
    public int order() {
        return 900;
    }

    @Override
    public String key() {
        return "offensive-prayer";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        GorillaContext ctx = state.context();
        return ctx.getBotStatus() == State.FIGHTING
                && ctx.getCurrentTarget() != null
                && state.config().enableOffensivePrayer();
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        Rs2PrayerEnum newOffensivePrayer = null;

        if (state.config().useMagicStyle() && ctx.getCurrentGear() == ArmorEquiped.MAGIC) {
            newOffensivePrayer = Rs2Prayer.getBestMagePrayer();
        } else if (state.config().useRangeStyle() && ctx.getCurrentGear() == ArmorEquiped.RANGED) {
            newOffensivePrayer = Rs2Prayer.getBestRangePrayer();
        } else if (state.config().useMeleeStyle() && ctx.getCurrentGear() == ArmorEquiped.MELEE) {
            newOffensivePrayer = Rs2Prayer.getBestMeleePrayer();
        }

        // Re-pray on actual state, so it comes back after prayer was disabled (points ran out + restore).
        if (newOffensivePrayer != null && !Rs2Prayer.isPrayerActive(newOffensivePrayer)) {
            GorillaHelpers.switchOffensivePrayer(ctx, newOffensivePrayer);
        }
        return newOffensivePrayer;
    }
}

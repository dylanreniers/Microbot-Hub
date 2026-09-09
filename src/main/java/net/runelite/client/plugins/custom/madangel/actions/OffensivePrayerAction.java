package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/**
 * Keeps the best melee offensive prayer (Piety/Chivalry/…) active while fighting. Re-checks the actual
 * prayer state each tick so it comes back on after a prayer-drain + potion restore. Independent of the
 * Protect-from-Magic smite flick (a defensive overhead), so the two never fight each other.
 */
@Slf4j
public class OffensivePrayerAction implements MadAngelAction {

    @Override
    public int order() {
        return 900;
    }

    @Override
    public String key() {
        return "offensive-prayer";
    }

    @Override
    public boolean needsExecution(MadAngelState state) {
        return state.config().enableOffensivePrayer()
                && !state.context().isInPostKill() // keep prayers off between kills
                && state.context().getCurrentTarget() != null;
    }

    @Override
    public Object execute(MadAngelState state) {
        Rs2PrayerEnum best = Rs2Prayer.getBestMeleePrayer();
        if (best != null && !Rs2Prayer.isPrayerActive(best)) {
            Rs2Prayer.toggle(best, true);
            state.context().setCurrentOffensivePrayer(best);
        }
        return best;
    }
}

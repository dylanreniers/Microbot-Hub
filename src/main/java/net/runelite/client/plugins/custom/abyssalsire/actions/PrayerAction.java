package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/**
 * Keeps the right prayers up for the phase:
 * <ul>
 *   <li><b>Phase 1</b> (ranging respiratory systems): best offensive range prayer, no overhead.</li>
 *   <li><b>Phase 2</b> (melee the standing Sire): Protect from Melee + best offensive melee prayer.</li>
 *   <li><b>Phase 3</b> (walk / explosion): Protect from Missiles + best offensive melee prayer.</li>
 * </ul>
 * Only the wanted overhead is enabled — overheads are mutually exclusive in-game, so enabling one
 * auto-disables the other (toggling both explicitly causes flip-flop).
 */
@Slf4j
public class PrayerAction implements SireAction {

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String key() {
        return "prayer";
    }

    @Override
    public boolean needsExecution(SireState state) {
        SirePhase phase = state.context().getPhase();
        return phase == SirePhase.PHASE1 || phase == SirePhase.PHASE2 || phase == SirePhase.PHASE3;
    }

    @Override
    public Object execute(SireState state) {
        SirePhase phase = state.context().getPhase();

        if (phase != SirePhase.PHASE1) {
            Rs2Prayer.toggle(Rs2Prayer.getBestMeleePrayer(), true);
        }

        switch (phase) {
            case PHASE2:
                enableOverhead(Rs2PrayerEnum.PROTECT_MELEE);
                break;
            case PHASE3:
                enableOverhead(Rs2PrayerEnum.PROTECT_RANGE);
                break;
            case PHASE1:
            default:
                // No overhead needed while ranging the stunned respiratory systems.
                break;
        }
        return phase;
    }

    private void enableOverhead(Rs2PrayerEnum wanted) {
        if (!Rs2Prayer.isPrayerActive(wanted)) {
            log.info("[sire] enabling {}", wanted);
            Rs2Prayer.toggle(wanted, true);
        }
    }
}

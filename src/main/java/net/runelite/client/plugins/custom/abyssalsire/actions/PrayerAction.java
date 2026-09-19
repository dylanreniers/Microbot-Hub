package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/**
 * Keeps the right prayers up for the phase:
 * <ul>
 *   <li><b>Phase 1</b> (ranging respiratory systems): best offensive range prayer, no overhead.</li>
 *   <li><b>Phase 2</b> (melee the standing Sire): best offensive melee prayer; Protect from Melee only
 *       once the Sire is fully exposed and attacking (id 5890).</li>
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

        // Offensive melee prayer (piety): always on in phase 3, but in phase 2 only once we're back on
        // the original tile — so it isn't burning prayer while we walk back / dodge miasma.
        if (phase == SirePhase.PHASE3 || (phase == SirePhase.PHASE2 && atOriginalTile())) {
            Rs2Prayer.toggle(Rs2Prayer.getBestMeleePrayer(), true);
        }

        switch (phase) {
            case PHASE2:
                // The Sire can't melee us until it's fully exposed (id 5890); hold the overhead until then
                // instead of burning prayer on the standing/transition states earlier in phase 2.
                if (state.context().getCurrentSireId() == SireConstants.SIRE_PHASE2_MELEE) {
                    enableOverhead(Rs2PrayerEnum.PROTECT_MELEE);
                }
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

    /** True if we're on the original spot (or one tile south of it, which phase 2 also accepts). */
    private boolean atOriginalTile() {
        WorldPoint pos = Rs2Player.getWorldLocation();
        WorldPoint tile = SireConstants.ORIGINAL_POSITION;
        return pos != null
                && pos.getPlane() == tile.getPlane()
                && pos.getX() == tile.getX()
                && (pos.getY() == tile.getY() || pos.getY() == tile.getY() - 1);
    }

    private void enableOverhead(Rs2PrayerEnum wanted) {
        if (!Rs2Prayer.isPrayerActive(wanted)) {
            log.info("[sire] enabling {}", wanted);
            Rs2Prayer.toggle(wanted, true);
        }
    }
}

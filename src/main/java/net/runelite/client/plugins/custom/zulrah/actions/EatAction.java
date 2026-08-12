package net.runelite.client.plugins.custom.zulrah.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.zulrah.constants.ZulrahType;
import net.runelite.client.plugins.custom.zulrah.rotationutils.ZulrahPhase;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

@Slf4j
public class EatAction implements ZulrahAction {

    private static final int DEFAULT_EAT_PERCENT = 50;
    private static final int MAGE_PHASE_EAT_PERCENT = 70;

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String key() {
        return "eat";
    }

    /**
     * The eat threshold for this tick: the higher mage-phase value when the current phase is the MAGIC
     * (tanzanite) form we pray magic against, otherwise the default. Reads the phase live so it tracks
     * the rotation as it transitions.
     */
    private static int eatPercent(ZulrahState state) {
        ZulrahPhase phase = state.context().getPhase();
        boolean magePhase = phase != null
                && !phase.getZulrahNpc().isJad()
                && phase.getZulrahNpc().getType() == ZulrahType.MAGIC;
        return magePhase ? MAGE_PHASE_EAT_PERCENT : DEFAULT_EAT_PERCENT;
    }

    @Override
    public boolean needsExecution(ZulrahState state) {
        return Rs2Player.getHealthPercentage() <= eatPercent(state);
    }

    @Override
    public Object execute(ZulrahState state) {
        boolean ate = Rs2Player.eatAt(eatPercent(state), true);
        if (ate) {
            log.info("Eating.");
        }
        return ate;
    }
}

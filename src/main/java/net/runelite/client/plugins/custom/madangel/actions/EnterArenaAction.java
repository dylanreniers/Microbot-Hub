package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelContext.PostKillPhase;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Owns the {@link PostKillPhase#ENTER} phase: enter the arena via the enter pew
 * ({@link MadAngelHelpers#ENTER_OBJECT_ID}), "Wake" the angel, turn prayers ON, then hand back to combat
 * by clearing the phase to {@link PostKillPhase#NONE}. Entering pops no dialogue. If we're somehow still
 * in a finished instance (only the leave pew is present), hand back to LEAVE first.
 *
 * <p>Split out of {@code PostKillAction}; the two coordinate through the shared {@code postKillPhase}
 * (PostKillAction skips ENTER, this action only runs on it). Handing off to NONE is what lets the combat
 * pipeline (attack + prayer maintenance) take over — without it prayers never come on and the player
 * never engages.</p>
 */
@Slf4j
public class EnterArenaAction implements MadAngelAction {

    @Override
    public int order() {
        return 110; // just after PostKillAction (100), which releases the ENTER phase to us
    }

    @Override
    public String key() {
        return "enter-arena";
    }

    @Override
    public boolean needsExecution(MadAngelState state) {
        return state.config().enablePostKill()
                && state.context().getPostKillPhase() == PostKillPhase.ENTER;
    }

    @Override
    public Object execute(MadAngelState state) {
        MadAngelContext ctx = state.context();

        GameObject enter = pew(MadAngelHelpers.ENTER_OBJECT_ID);
        log.info("[mad-angel] entering battle area via pew {}", MadAngelHelpers.ENTER_OBJECT_ID);
        Rs2GameObject.interact(enter);
        sleepUntil(() -> pew(MadAngelHelpers.ENTER_OBJECT_ID) == null, 5000);

        sleep(Rs2Random.betweenInclusive(600, 1200));
        Rs2NpcModel angel = MadAngelHelpers.getTarget(ctx);
        if (angel != null) {
            log.info("[mad-angel] waking the angel");
            angel.click(MadAngelHelpers.WAKE_ACTION);
        }

        sleep(Rs2Random.betweenInclusive(600, 1200));
        if (state.config().enableOffensivePrayer()) {
            Rs2PrayerEnum best = Rs2Prayer.getBestMeleePrayer();
            if (best != null) {
                Rs2Prayer.toggle(best, true);
            }
        }
        if (state.config().enableProtectFromMelee()) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
        }
        log.info("[mad-angel] angel awake -> prayers on, resuming combat");
        ctx.setPostKillPhase(PostKillPhase.NONE);
        return "awake";
    }

    private GameObject pew(int id) {
        return Rs2GameObject.getGameObject(o -> o.getId() == id, MadAngelHelpers.PEW_SEARCH_RANGE);
    }
}

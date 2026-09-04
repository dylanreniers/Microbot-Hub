package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.HeadIcon;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaConfig;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import static net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers.SAFE_LOCATION;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * FIGHTING phase: when supplies run out, commune the Royal seed pod back to safety, drop prayers and
 * switch back to travel gear, then return to BANKING. Runs before any attacking this tick so we bail
 * as soon as we're low; setting the status to BANKING short-circuits the rest of the fight pipeline.
 */
@Slf4j
public class RetreatAction implements GorillaAction {

    @Override
    public int order() {
        return 350;
    }

    @Override
    public String key() {
        return "retreat";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        return state.context().getBotStatus() == State.FIGHTING
                && GorillaHelpers.shouldRetreat(state.config());
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        CustomDemonicGorillaConfig config = state.config();

        ctx.setCurrentTarget(null);
        ctx.setCurrentOverheadIcon(null);
        ctx.setCurrentTripKillCount(0);
        Microbot.pauseAllScripts.compareAndSet(false, true);
        Rs2Inventory.interact("Royal seed pod", "Commune");
        sleepUntil(() -> SAFE_LOCATION.equals(
                Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation())), 5000);
        GorillaHelpers.disableAllPrayers(ctx);
        Microbot.pauseAllScripts.compareAndSet(true, false);
        ctx.setBotStatus(State.BANKING);
        Microbot.log("Changing to default gear");
        GorillaHelpers.switchGear(ctx, config, HeadIcon.RANGED);
        return State.BANKING;
    }
}

package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import static net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers.GORILLA_LOCATION;

/**
 * TRAVEL_TO_GORILLAS phase: walk to the gorilla cave and switch to fighting once we're there. Only
 * runs while the run is in {@link State#TRAVEL_TO_GORILLAS}.
 */
@Slf4j
public class TravelAction implements GorillaAction {

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String key() {
        return "travel";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        return state.context().getBotStatus() == State.TRAVEL_TO_GORILLAS;
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        if (Rs2Player.distanceTo(GORILLA_LOCATION) < 5) {
            ctx.setBotStatus(State.FIGHTING);
            return State.FIGHTING;
        }
        if (Rs2Walker.walkTo(GORILLA_LOCATION)) {
            ctx.setBotStatus(State.FIGHTING);
            return State.FIGHTING;
        }
        return State.TRAVEL_TO_GORILLAS;
    }
}

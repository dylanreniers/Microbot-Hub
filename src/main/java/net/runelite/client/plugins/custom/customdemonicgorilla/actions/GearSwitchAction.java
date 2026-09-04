package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;

/**
 * FIGHTING phase: swap combat gear when the gorilla flips its overhead prayer. Delegates to the
 * shared {@link GorillaHelpers#handleGearSwitching} routine (also invoked mid-tick by
 * {@link GorillaAttacksAction} on the gorilla's prayer-switch animation). Corresponds to the old
 * standalone {@code handleGearSwitching} call in the fight loop.
 */
@Slf4j
public class GearSwitchAction implements GorillaAction {

    @Override
    public int order() {
        return 700;
    }

    @Override
    public String key() {
        return "gear-switch";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        GorillaContext ctx = state.context();
        return ctx.getBotStatus() == State.FIGHTING && ctx.getCurrentTarget() != null;
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaHelpers.handleGearSwitching(state.context(), state.config());
        return state.context().getCurrentGear();
    }
}

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
        // Swap gear on an overhead flip...
        GorillaHelpers.handleGearSwitching(state.context(), state.config());
        // ...then verify the swap actually took. A full inventory (loot) can make wearEquipment() silently
        // fail, and handleGearSwitching won't retry until the next overhead change — so we'd fight with the
        // wrong weapon and stall. This re-equips (rate-limited) until the worn gear matches the style.
        GorillaHelpers.verifyGear(state.context(), state.config());
        return state.context().getCurrentGear();
    }
}

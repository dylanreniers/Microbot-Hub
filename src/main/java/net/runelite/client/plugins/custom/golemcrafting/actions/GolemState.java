package net.runelite.client.plugins.custom.golemcrafting.actions;

import net.runelite.client.plugins.custom.actions.ActionState;
import net.runelite.client.plugins.custom.actions.ScriptState;

import java.util.ArrayList;
import java.util.List;

/**
 * Golem crafting's {@link ScriptState}: the per-tick {@link ActionState} list (record/query inherited
 * from {@link ScriptState}) plus the durable {@link GolemContext} shared across ticks.
 */
public class GolemState implements ScriptState {

    private final GolemContext context;
    private final List<ActionState> actionStates = new ArrayList<>();

    public GolemState(GolemContext context) {
        this.context = context;
    }

    public GolemContext context() {
        return context;
    }

    @Override
    public List<ActionState> actionStates() {
        return actionStates;
    }
}

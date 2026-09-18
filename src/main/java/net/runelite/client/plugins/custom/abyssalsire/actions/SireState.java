package net.runelite.client.plugins.custom.abyssalsire.actions;

import net.runelite.client.plugins.custom.actions.ActionState;
import net.runelite.client.plugins.custom.actions.ScriptState;

import java.util.ArrayList;
import java.util.List;

/**
 * Abyssal Sire's {@link ScriptState}: the per-tick {@link ActionState} list (record/query inherited
 * from {@link ScriptState}) plus the durable {@link SireContext} that lives across ticks and is also
 * mutated by the plugin's NPC/animation/graphics events.
 */
public class SireState implements ScriptState {

    private final SireContext context;
    private final List<ActionState> actionStates = new ArrayList<>();

    public SireState(SireContext context) {
        this.context = context;
    }

    public SireContext context() {
        return context;
    }

    @Override
    public List<ActionState> actionStates() {
        return actionStates;
    }
}

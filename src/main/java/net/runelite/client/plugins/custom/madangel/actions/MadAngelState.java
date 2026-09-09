package net.runelite.client.plugins.custom.madangel.actions;

import net.runelite.client.plugins.custom.actions.ActionState;
import net.runelite.client.plugins.custom.actions.ScriptState;
import net.runelite.client.plugins.custom.madangel.MadAngelConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Mad Angel's {@link ScriptState}. Supplies the per-tick {@link ActionState} list (record/query logic
 * is inherited) plus references to the durable {@link MadAngelContext} and the live
 * {@link MadAngelConfig} every action needs.
 */
public class MadAngelState implements ScriptState {

    private final MadAngelContext context;
    private final MadAngelConfig config;
    private final List<ActionState> actionStates = new ArrayList<>();

    public MadAngelState(MadAngelContext context, MadAngelConfig config) {
        this.context = context;
        this.config = config;
    }

    /** Durable fight state (target, sweep/blast/smite reaction flags). */
    public MadAngelContext context() {
        return context;
    }

    /** Live config, read fresh each tick so setting changes take effect without a restart. */
    public MadAngelConfig config() {
        return config;
    }

    @Override
    public List<ActionState> actionStates() {
        return actionStates;
    }
}

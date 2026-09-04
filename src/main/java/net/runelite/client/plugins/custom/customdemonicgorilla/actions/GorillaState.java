package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import net.runelite.client.plugins.custom.actions.ActionState;
import net.runelite.client.plugins.custom.actions.ScriptState;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Demonic Gorilla's {@link ScriptState}. Supplies the per-tick {@link ActionState} list (record and
 * query logic is inherited from {@link ScriptState}) plus references to the durable
 * {@link GorillaContext} and the live {@link CustomDemonicGorillaConfig} that every action needs.
 */
public class GorillaState implements ScriptState {

    private final GorillaContext context;
    private final CustomDemonicGorillaConfig config;
    private final List<ActionState> actionStates = new ArrayList<>();

    public GorillaState(GorillaContext context, CustomDemonicGorillaConfig config) {
        this.context = context;
        this.config = config;
    }

    /** Durable fight/trip state (status, target, prayers, gear, kill counts, boulder, ...). */
    public GorillaContext context() {
        return context;
    }

    /** Live config, read fresh each tick so setting changes take effect without a restart. */
    public CustomDemonicGorillaConfig config() {
        return config;
    }

    @Override
    public List<ActionState> actionStates() {
        return actionStates;
    }
}

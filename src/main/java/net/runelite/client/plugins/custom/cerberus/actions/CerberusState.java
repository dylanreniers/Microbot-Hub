package net.runelite.client.plugins.custom.cerberus.actions;

import net.runelite.client.plugins.custom.actions.ActionState;
import net.runelite.client.plugins.custom.actions.ScriptState;
import net.runelite.client.plugins.custom.cerberus.CerberusConfig;
import net.runelite.client.plugins.microbot.Microbot;

import java.util.ArrayList;
import java.util.List;

/**
 * Cerberus' {@link ScriptState}: the per-tick {@link ActionState} list plus the durable
 * {@link CerberusContext} and live {@link CerberusConfig} every action needs.
 */
public class CerberusState implements ScriptState {

    private final CerberusContext context;
    private final CerberusConfig config;
    private final List<ActionState> actionStates = new ArrayList<>();

    public CerberusState(CerberusContext context, CerberusConfig config) {
        this.context = context;
        this.config = config;
    }

    public CerberusContext context() {
        return context;
    }

    public CerberusConfig config() {
        return config;
    }

    /** Claims this game tick's single click slot (see {@link CerberusContext#claimClick}). */
    public boolean claimClick() {
        return context.claimClick(Microbot.getClient().getTickCount());
    }

    @Override
    public List<ActionState> actionStates() {
        return actionStates;
    }
}

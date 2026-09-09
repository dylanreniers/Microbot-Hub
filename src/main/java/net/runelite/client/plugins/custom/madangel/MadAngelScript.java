package net.runelite.client.plugins.custom.madangel;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.actions.Action;
import net.runelite.client.plugins.custom.actions.ActionScript;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelAction;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelContext;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelState;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

import javax.inject.Inject;

/**
 * Mad Angel's action-driven script. The generic pipeline wiring (action discovery, ordering, the
 * per-tick run loop) lives in {@link ActionScript}; here we only expose the durable
 * {@link MadAngelContext} to the plugin's overlay and event handlers. Actions are auto-discovered from
 * the {@code actions} package and run each tick in ascending {@link Action#order()}. Driven at ~50 ms
 * so the sweep dodge and blast repositioning react within a tick (the tick-precise smite flicks are
 * handled by the plugin's game-tick handler, not this loop).
 */
@Slf4j
public class MadAngelScript extends ActionScript<MadAngelState> {

    @Inject
    private MadAngelConfig config;

    @Getter
    private final MadAngelContext context = new MadAngelContext();

    @Override
    protected Class<? extends Action<MadAngelState>> actionType() {
        return MadAngelAction.class;
    }

    @Override
    protected MadAngelState createState() {
        return new MadAngelState(context, config);
    }

    @Override
    protected void onInitialize() {
        log.info("Initializing Mad Angel (Custom)");
        context.reset();
        // Start every cycle by entering the arena + waking the angel (skips straight to WAKE/combat if
        // we're already in the battle area). Combat actions stay suppressed until the angel is awake.
        if (config.enablePostKill()) {
            context.setPostKillPhase(MadAngelContext.PostKillPhase.ENTER);
        }
    }

    @Override
    public void onShutdown() {
        // NOTE: onShutdown() is invoked by AbstractScript.shutdown(); do not call shutdown() here.
        Rs2Prayer.disableAllPrayers();
        context.reset();
    }

    /** Faster than the 100 ms default: dodge/reposition reactions need to act within a game tick. */
    @Override
    public int getTickDelay() {
        return 50;
    }
}

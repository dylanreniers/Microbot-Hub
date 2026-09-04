package net.runelite.client.plugins.custom.customdemonicgorilla;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.custom.actions.Action;
import net.runelite.client.plugins.custom.actions.ActionScript;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaAction;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaState;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;

import javax.inject.Inject;

import static net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity.EXTREME;

/**
 * Demonic Gorilla's action-driven script. The generic pipeline wiring (action discovery, ordering,
 * the per-tick run loop) lives in {@link ActionScript}; here we only build the durable
 * {@link GorillaContext} (inventory setups, antiban) and expose it to the plugin's overlay and
 * event handlers. Actions are auto-discovered from the {@code actions} package and run each tick in
 * ascending {@link Action#order()}. Driven by the script's own scheduler (see {@link #getTickDelay()})
 * rather than the game clock, to keep the ~50 ms cadence the prayer/gear reactions rely on.
 */
@Slf4j
public class CustomDemonicGorillaScript extends ActionScript<GorillaState> {

    @Inject
    private CustomDemonicGorillaConfig config;

    @Getter
    private final GorillaContext context = new GorillaContext();

    @Override
    protected Class<? extends Action<GorillaState>> actionType() {
        return GorillaAction.class;
    }

    @Override
    protected GorillaState createState() {
        // Skip ticks until the setups are built (onInitialize), so no action runs half-configured.
        return context.isInitialized() ? new GorillaState(context, config) : null;
    }

    @Override
    protected void onInitialize() {
        log.info("Initializing Demonic Gorilla (Custom)");
        context.reset();
        enableAntibanSettings();

        // Resolve setups LIVE by name, not from the InventorySetup object the config returns: that
        // object is a snapshot serialized when the setup was picked in the dropdown, so editing it
        // afterwards (gear/potions/quantities/slots) leaves the snapshot stale and the loader chases the
        // old layout and never matches. The String constructor looks the CURRENT setup up by name. (Same
        // rationale as the Zulrah script. Editing a setup while running still needs a restart to re-resolve.)
        context.setRangeGear(resolveSetup(config.rangeGear()));
        context.setMagicGear(resolveSetup(config.magicGear()));
        context.setMeleeGear(resolveSetup(config.meleeGear()));
        context.setBankingGear(resolveSetup(config.gearSetup()));

        // The QoL plugin fights us for control (auto-eat, prayer flicking, ...); stop it while we run.
        Microbot.getActiveMicrobotPlugins().stream()
                .filter(p -> p.getClass().getSimpleName().contains("QoLPlugin"))
                .findFirst().ifPresent(qolPlugin -> Microbot.stopPlugin(qolPlugin.getClass()));

        context.setInitialized(true);
    }

    /** Build an {@link Rs2InventorySetup} resolved live by the setup's name (never the stale config
     *  snapshot). Returns null for an unset config setup (e.g. a combat style that isn't enabled). */
    private Rs2InventorySetup resolveSetup(net.runelite.client.plugins.microbot.inventorysetups.InventorySetup setup) {
        return setup == null ? null : new Rs2InventorySetup(setup.getName(), mainScheduledFuture);
    }

    @Override
    public void onShutdown() {
        // NOTE: onShutdown() is invoked by AbstractScript.shutdown(); do not call shutdown() here.
        GorillaHelpers.disableAllPrayers(context);
        context.reset();
        context.setInitialized(false);
        context.setKillCount(0);
        context.setRangeGear(null);
        context.setMagicGear(null);
        context.setMeleeGear(null);
        context.setBankingGear(null);
        GorillaHelpers.logOnce(context, "Shutting down Demonic Gorilla script");
    }

    /** Faster than the 100 ms default: prayer flicking and gear swaps need to react within a tick. */
    @Override
    public int getTickDelay() {
        return 50;
    }

    private void enableAntibanSettings() {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.simulateFatigue = false;
        Rs2AntibanSettings.simulateAttentionSpan = true;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.nonLinearIntervals = true;
        Rs2AntibanSettings.dynamicActivity = true;
        Rs2AntibanSettings.profileSwitching = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.moveMouseOffScreen = false;
        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.04;
        Rs2Antiban.setActivityIntensity(EXTREME);
    }
}

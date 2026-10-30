package net.runelite.client.plugins.custom.cerberus;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.actions.Action;
import net.runelite.client.plugins.custom.actions.ActionScript;
import net.runelite.client.plugins.custom.cerberus.actions.CerberusAction;
import net.runelite.client.plugins.custom.cerberus.actions.CerberusContext;
import net.runelite.client.plugins.custom.cerberus.actions.CerberusState;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

import javax.inject.Inject;
import java.util.function.Supplier;

/**
 * Cerberus' action-driven script: attacking, lava dodging, eating/drinking, the offensive prayer, and the
 * between-kills loop (loot -> supply check -> Ferox restock -> back into a free lair).
 * The tick-precise protection prayers (camp / triple / souls) are handled by the plugin's client-thread
 * event handlers, not this loop.
 */
@Slf4j
public class CerberusScript extends ActionScript<CerberusState> {

    @Inject
    private CerberusConfig config;

    @Getter
    private final CerberusContext context = new CerberusContext();

    /** Set at the start of shutdown so in-flight/queued ticks do nothing. */
    private volatile boolean stopping;

    @Override
    protected Class<? extends Action<CerberusState>> actionType() {
        return CerberusAction.class;
    }

    @Override
    protected CerberusState createState() {
        return new CerberusState(context, config);
    }

    @Override
    protected void onInitialize() {
        log.info("Initializing Cerberus (Custom)");
        stopping = false;
        context.reset();
        context.setResupplySetup(null); // built on the first tick, see resolveResupplySetup()
    }

    @Override
    public void tick() {
        if (stopping) {
            return; // shutdown in progress: the loop is cancelled after onShutdown() returns
        }
        if (context.getPhase() == CerberusContext.Phase.STARTING) {
            resolveResupplySetup();
            decideStartingPhase();
        }
        super.tick();
    }

    /**
     * Resolve the resupply setup live by name (the config's snapshot goes stale). Done on the first tick,
     * NOT in onInitialize: AbstractScript.run() calls initialize() before assigning the new
     * mainScheduledFuture, so there it's still the PREVIOUS run's cancelled future — and
     * Rs2InventorySetup.loadInventory() stops at its first withdrawal when its scheduler is cancelled
     * (every resupply after a plugin restart withdrew nothing).
     */
    private void resolveResupplySetup() {
        InventorySetup setup = config.resupplySetup();
        context.setResupplySetup(setup == null ? null : new Rs2InventorySetup(setup.getName(), mainScheduledFuture));
        log.info("[cerberus] resupply setup: {}", setup == null ? "none (restocking disabled)" : "'" + setup.getName() + "'");
    }

    /** First tick: fight if we're already in a lair room (and stocked), otherwise run the trip there. */
    private void decideStartingPhase() {
        Boolean inLair = Microbot.getClientThread().invoke((Supplier<Boolean>) () -> Microbot.getRs2NpcCache().query()
                .withIds(CerberusConstants.CERBERUS_IDS).nearest(CerberusConstants.IN_LAIR_RADIUS) != null);
        CerberusContext.Phase phase = Boolean.TRUE.equals(inLair) && !CerberusSupplies.needsResupply(config)
                ? CerberusContext.Phase.FIGHT
                : CerberusContext.Phase.RESTOCK;
        log.info("[cerberus] start: in lair={}, prayer total={}, hp total={} -> {}", inLair,
                CerberusSupplies.totalPrayerPoints(), CerberusSupplies.totalHitpoints(), phase);
        context.setPhase(phase);
    }

    @Override
    public void onShutdown() {
        // NOTE: onShutdown() is invoked by AbstractScript.shutdown(); do not call shutdown() here.
        // It runs BEFORE the tick loop is cancelled, so first make every further tick a no-op (otherwise each
        // one re-decided the starting phase and spammed "Interrupted waiting for client thread").
        stopping = true;
        Rs2Prayer.disableAllPrayers();
        if (context.isRetaliateDisabledByUs()) {
            // The no-ghosts flow switched it off. Restoring it is a tab switch + click + waits — off the
            // caller's (Swing) thread, so stopping the plugin isn't blocked on it.
            Thread restore = new Thread(() -> Rs2Combat.setAutoRetaliate(true), "cerberus-retaliate-restore");
            restore.setDaemon(true);
            restore.start();
        }
        context.reset();
    }

    /** Lava dodges need to react within a game tick. */
    @Override
    public int getTickDelay() {
        return 50;
    }
}

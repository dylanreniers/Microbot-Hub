package net.runelite.client.plugins.custom.zulrah;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.custom.actions.Action;
import net.runelite.client.plugins.custom.actions.ActionScript;
import net.runelite.client.plugins.custom.zulrah.actions.FightContext;
import net.runelite.client.plugins.custom.zulrah.actions.ZulrahAction;
import net.runelite.client.plugins.custom.zulrah.actions.ZulrahState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.zulrah.constants.VenomTiming;
import net.runelite.client.plugins.custom.zulrah.rotationutils.ZulrahPhase;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import javax.inject.Inject;

/**
 * Zulrah's action-driven script. The generic pipeline wiring (discovery, runner, per-tick loop)
 * lives in {@link ActionScript}; here we only build the Zulrah {@link FightContext} and feed it the
 * plugin's animation events. Actions are auto-discovered from the {@code actions} package.
 */
@Slf4j
public class ZulrahScript extends ActionScript<ZulrahState> {

    @Inject
    private ZulrahConfig zulrahConfig;
    @Inject
    private ItemManager itemManager;

    private FightContext context;

    @Override
    protected Class<? extends Action<ZulrahState>> actionType() {
        return ZulrahAction.class;
    }

    @Override
    protected ZulrahState createState() {
        return context == null ? null : new ZulrahState(context);
    }

    @Override
    protected void onInitialize() {
        log.info("Initializing");
        context = new FightContext(itemManager);
        context.setMagicSetup(new Rs2InventorySetup(zulrahConfig.mageInventorySetup(), mainScheduledFuture));
        context.setRangeSetup(new Rs2InventorySetup(zulrahConfig.rangeInventorySetup(), mainScheduledFuture));
        context.reset();
    }

    @Override
    public void onShutdown() {
        // NOTE: onShutdown() is invoked *by* AbstractScript.shutdown(); do not call shutdown() here.
        reset();
    }

    // ---- Event delegates (called by ZulrahPlugin's @Subscribe handlers) ----

    /** Clears per-fight state on (re)start and on every plugin/fight reset. */
    public void reset() {
        if (context != null) {
            context.reset();
        }
    }

    public void setZulrahPhase(ZulrahPhase phase) {
        // null = rotation not yet identified / end of rotation: keep the current phase.
        if (context != null && phase != null) {
            context.setPhase(phase);
            context.setPhaseChanged(true);
        }
    }

    /** The next phase's stand tile (or null if unknown); used to pre-move out of end-of-phase venom. */
    public void setNextStandLocation(net.runelite.api.coords.WorldPoint next) {
        if (context != null) {
            context.setNextStandLocation(next);
        }
    }

    /**
     * A venom cloud spawned (GameObjectSpawned, client thread). We pre-move toward the next phase's
     * tile so we don't linger in the cloud that drops on our spot — but only once we're already
     * settled on the CURRENT phase's tile, so this never fights the positioning logic that gets us
     * to the current phase (otherwise an early cloud would drag us to the next phase before we've
     * even fought this one). Guarded to fire at most once per phase; the guard is re-armed on
     * resurface. We only set the target; the pipeline's positioning action does the walking on its
     * own thread, so we never fire mouse input concurrently with it.
     */
    public void onVenomCloudSpawned() {
        if (context == null || context.isVenomPreMoved() || context.isMeleeDodgePhase()) {
            return;
        }
        ZulrahPhase phase = context.getPhase();
        // Only pre-move for phases whose venom lands at the END. START-venom phases (e.g. a green form
        // spewing barrages the instant it surfaces) must stay put — our tile is the safespot, and
        // leaving early walks us into the clouds while the phase's attacks keep coming.
        if (phase == null || phase.getAttributes().getVenomTiming() != VenomTiming.END) {
            return;
        }
        WorldPoint next = context.getNextStandLocation();
        if (next == null) {
            return; // next phase's tile not known yet
        }
        WorldPoint current = context.getStandLocation();
        WorldPoint pos = Rs2Player.getWorldLocation();
        // Only pre-move once we're standing in the right place for the CURRENT phase. Until then the
        // reposition logic owns standLocation and we must not override it.
        boolean settledOnCurrent = current != null && pos != null
                && (pos.equals(current) || (!Rs2Player.isMoving() && pos.distanceTo(current) <= 1));
        if (!settledOnCurrent) {
            return;
        }
        if (pos.equals(next) || next.equals(current)) {
            return; // already there / already targeting it
        }
        log.info("[venom] cloud spawned while settled; pre-moving to next stand tile {}", next);
        context.setVenomPreMoved(true);
        context.setStandLocation(next);
    }

    /** Zulrah resurfaced: a new phase is now active — re-arm the one-shot venom pre-move. */
    public void onZulrahResurface() {
        if (context != null) {
            context.setVenomPreMoved(false);
        }
    }

    /** Melee tail swing (SNAKEBOSS_ATTACK_TAIL_LEFT/RIGHT): flip the dodge target tile. */
    public void handleMeleeSwing() {
        if (context != null && context.isMeleeDodgePhase()) {
            context.setMeleeDodgeAtNorth(!context.isMeleeDodgeAtNorth());
        }
    }

    /**
     * Jad-phase prayer flick, driven directly by the attack animation event (outside the tick
     * pipeline for latency). Blind toggle: fragile if it ever misses a swing, but the pipeline's
     * EnforcePrayersAction deliberately leaves the jad overhead alone so it doesn't fight this.
     */
    public void handleZulrahAttack() {
        if (context == null) {
            log.info("Context is null, returning");
            return;
        }
        ZulrahPhase phase = context.getPhase();
        if (phase == null || !phase.getZulrahNpc().isJad()) {
            log.info("Phase is null or not jad");
            return;
        }
        log.info("Jad phase. Toggling prayer.");
        if (Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE)) {
            log.info("Switching to protect magic");
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
        } else {
            log.info("Switching to protect range");
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, true);
        }
    }
}

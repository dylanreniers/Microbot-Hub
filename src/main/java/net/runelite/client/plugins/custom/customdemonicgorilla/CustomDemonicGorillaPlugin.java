package net.runelite.client.plugins.custom.customdemonicgorilla;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.AttackStyle;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.misc.TimeUtils;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Instant;

@PluginDescriptor(
        name = PluginDescriptor.TaFCat + "Demonic Gorillas (Custom)",
        description = "Custom build: automates restocking, prayer flicking, and gear switching during Demonic Gorillas",
        tags = {"demonic", "Gorilla", "flicker", "weapon", "switch", "microbot", "custom"},
        version = CustomDemonicGorillaPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class CustomDemonicGorillaPlugin extends Plugin {

    public final static String version = "1.6.2";

    private static final int DEMONIC_GORILLA_ROCK = 856;

    @Inject
    private CustomDemonicGorillaConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private CustomDemonicGorillaOverlay demonicGorillaOverlay;
    @Inject
    private CustomDemonicGorillaScript demonicGorillaScript;
    private Instant scriptStartTime;

    private final CustomDemonicGorillaLooterScript lootScript = new CustomDemonicGorillaLooterScript();

    @Provides
    CustomDemonicGorillaConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CustomDemonicGorillaConfig.class);
    }

    /** Exposes the durable run state for the overlay and this plugin's event handlers. */
    public GorillaContext getContext() {
        return demonicGorillaScript.getContext();
    }

    @Override
    protected void startUp() {
        if (!validateConfig()) {
            Microbot.stopPlugin(this);
            return;
        }
        scriptStartTime = Instant.now();
        if (overlayManager != null) {
            overlayManager.add(demonicGorillaOverlay);
        }
        demonicGorillaScript.run();
        lootScript.run(config, getContext());
    }

    /** Fail fast if a combat style is enabled without its gear setup, or the banking setup is missing. */
    private boolean validateConfig() {
        if (config.useMagicStyle() && config.magicGear() == null) {
            Microbot.showMessage("You've selected magic combatstyle, but your magic inventory setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your magic setup again.");
            return false;
        }
        if (config.useRangeStyle() && config.rangeGear() == null) {
            Microbot.showMessage("You've selected ranged combatstyle, but your range inventory setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your range setup again.");
            return false;
        }
        if (config.useMeleeStyle() && config.meleeGear() == null) {
            Microbot.showMessage("You've selected melee combatstyle, but your melee inventory setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your melee setup again.");
            return false;
        }
        if (config.gearSetup() == null) {
            Microbot.showMessage("Your banking gear setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your gear setup again.");
            return false;
        }
        return true;
    }

    @Override
    protected void shutDown() {
        demonicGorillaScript.shutdown();
        lootScript.shutdown();
        overlayManager.remove(demonicGorillaOverlay);
    }

    protected String getTimeRunning() {
        return scriptStartTime != null ? TimeUtils.getFormattedDurationBetween(scriptStartTime, Instant.now()) : "";
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        final Projectile projectile = event.getProjectile();
        GorillaContext ctx = getContext();
        if (projectile.getId() != DEMONIC_GORILLA_ROCK) {
            // Any NON-boulder projectile that originates from our target gorilla's tile is a magic/ranged
            // attack — which means this attack CANNOT be melee. That's ground truth (no projectile-ID guess
            // needed) used to veto a false melee pre-pray during the switch read (see onGameTick, Phase 2).
            var target = ctx.getCurrentTarget();
            WorldPoint src = projectile.getSourcePoint();
            if (target != null && src != null) {
                WorldPoint gorilla = target.getWorldLocation();
                if (gorilla != null && src.distanceTo(gorilla) == 0) {
                    if (!ctx.isGorillaProjectileFiredSinceCry()) {
                        ctx.setDiagGorillaProjectiles(ctx.getDiagGorillaProjectiles() + 1);
                    }
                    ctx.setGorillaProjectileFiredSinceCry(true);
                }
            }
            return;
        }
        // The boulder lands on its TARGET tile, which is known the moment the projectile spawns — so we
        // arm the dodge early. (The old code waited for the projectile's current position to reach the
        // player, i.e. until it had basically landed, which is why boulders were connecting.)
        WorldPoint target = projectile.getTargetPoint();
        if (target == null && projectile.getTarget() != null) {
            // Fallback if the world target isn't populated: derive it from the target LocalPoint.
            target = WorldPoint.fromLocal(Microbot.getClient(), projectile.getTarget());
        }
        WorldPoint me = Rs2Player.getWorldLocation();
        // Record EVERY inbound boulder landing near us (not just the one on our exact tile) so the dodge can
        // avoid all of them at once during a barrage. This is what stops the "dodge onto the next boulder"
        // loop that the old single-tile + blocking-sleep design fell into.
        if (target != null && me != null && target.getPlane() == me.getPlane() && target.distanceTo(me) <= 5) {
            long landsAtMs = System.currentTimeMillis() + projectile.getRemainingCycles() * 20L;
            ctx.addInboundBoulder(target, landsAtMs);
        }
        // Arm if the boulder is aimed at our tile OR an adjacent one: it targets where we stood at cast,
        // but while meleeing we've usually already drifted a tile off by the time we see the projectile.
        boolean nearUs = target != null && me != null
                && target.getPlane() == me.getPlane() && target.distanceTo(me) <= 1;
        // NB: do NOT log here. onProjectileMoved runs on the client thread and fires on every boulder
        // projectile move. Microbot attaches GameChatAppender to the root logger, and its synchronized
        // doAppend round-trips to the client thread via ClientThread.invoke().get(). If a script thread
        // is mid-log (holding the appender lock, waiting on the client thread) while the client thread
        // logs here, they deadlock until the invoke times out — a multi-second game freeze. Keep this
        // handler log-free.
        if (nearUs && !target.equals(ctx.getBoulderTargetTile())) {
            ctx.setBoulderTargetTile(target);
            ctx.setBoulderLandsAtMs(System.currentTimeMillis() + projectile.getRemainingCycles() * 20L);
            ctx.setBoulderDodgePending(true);
        }
    }

    /**
     * The demonic gorilla shouts "Rhaaaa" (overhead text) the moment it switches attack style — the
     * reliable cry, distinct from the ambiguous 7224 defensive-emote animation. When it comes from the
     * gorilla we're fighting, arm the style-switch prediction (consumed by GorillaAttacksAction).
     */
    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event) {
        Actor actor = event.getActor();
        String text = event.getOverheadText();
        if (text == null || !text.toLowerCase().startsWith("rhaa")) {
            return;
        }
        if (!isOurTargetGorilla(actor)) {
            return;
        }
        GorillaContext ctx = getContext();
        ctx.setDiagCries(ctx.getDiagCries() + 1);
        AttackStyle prev = ctx.getCurrentAttackStyle();
        ctx.setPreviousAttackStyle(prev);
        ctx.setAwaitingStyleSwitch(true);
        ctx.setStyleSwitchArmedMs(System.currentTimeMillis());
        ctx.setAwaitingGapOpened(false);
        ctx.setStyleSwitchCryPending(true); // pipeline handles the step-away movement / melee watch
        ctx.setGorillaProjectileFiredSinceCry(false); // reset the "is it magic/ranged?" projectile proof
        ctx.setLastGorillaDistToPlayer(-1);           // fresh baseline for the closing-in melee tell

        // Pre-pray the never-same prediction IMMEDIATELY here (client thread), not via the pipeline —
        // the gorilla's first new-style attack fires almost at once, so a tick of pipeline latency would
        // leave us on the OLD overhead for that first hit. magic -> range; ranged/melee -> magic.
        Rs2PrayerEnum predicted = prev == AttackStyle.MAGIC ? Rs2PrayerEnum.PROTECT_RANGE : Rs2PrayerEnum.PROTECT_MAGIC;
        if (!Rs2Prayer.isPrayerActive(predicted)) {
            GorillaHelpers.switchDefensivePrayer(ctx, predicted);
        }
        // Anchor the melee-read baseline HERE, at the pre-pray moment: a melee gorilla will walk OFF this
        // tile to reach us; a magic/ranged one attacks from it. (Captured after the pre-pray so it's
        // unambiguously "the tile it switched prayer on", per how we reason about the read.)
        ctx.setGorillaTileAtPrePray(actor.getWorldLocation());
        // No logging here: this handler runs on the client thread; logging routes through Microbot's
        // GameChatAppender which blocks on a client-thread invoke, deadlocking the game loop (see
        // onProjectileMoved). Keep all client-thread @Subscribe handlers log-free.
    }

    /**
     * Authoritative fail-check: the gorilla's actual attack animation always wins over the prediction.
     * The instant our target gorilla plays a magic/ranged/melee attack, flick the matching overhead —
     * event-driven (client thread) so it beats the 50 ms pipeline poll and never lags a wrong guess.
     */
    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        Actor actor = event.getActor();
        if (!isOurTargetGorilla(actor)) {
            return;
        }
        Rs2PrayerEnum protect;
        AttackStyle style;
        switch (actor.getAnimation()) {
            case GorillaHelpers.DEMONIC_GORILLA_MAGIC_ATTACK:
                protect = Rs2PrayerEnum.PROTECT_MAGIC;
                style = AttackStyle.MAGIC;
                break;
            case GorillaHelpers.DEMONIC_GORILLA_RANGED_ATTACK:
                protect = Rs2PrayerEnum.PROTECT_RANGE;
                style = AttackStyle.RANGED;
                break;
            case GorillaHelpers.DEMONIC_GORILLA_MELEE_ATTACK:
                protect = Rs2PrayerEnum.PROTECT_MELEE;
                style = AttackStyle.MELEE;
                break;
            default:
                return; // not an attack animation (cry/emote/AOE handled elsewhere)
        }
        GorillaContext ctx = getContext();
        // (Removed TEMP client-thread diagnostic log — it deadlocked the game loop via GameChatAppender.)

        // --- Rotation/timing model (Phase 1) -------------------------------------------------------
        // Was the right overhead already up as this attack fired? (Checked BEFORE we re-pray below.) The
        // gorilla only counts an attack toward its style-switch quota if the player prayed it correctly.
        boolean correctPrayer = Rs2Prayer.isPrayerActive(protect);
        if (correctPrayer) {
            ctx.setDiagCorrectPrayerAttacks(ctx.getDiagCorrectPrayerAttacks() + 1);
        } else {
            ctx.setDiagWrongPrayerAttacks(ctx.getDiagWrongPrayerAttacks() + 1);
        }
        AttackStyle prevStyle = ctx.getCurrentAttackStyle();
        boolean styleChanged = prevStyle != AttackStyle.UNKNOWN && prevStyle != style;
        int tick = Microbot.getClient().getTickCount();
        if (styleChanged) {
            // Confirmed switch: quota resets (minus this attack if we prayed it right).
            ctx.setAttacksUntilSwitch(GorillaContext.ATTACKS_PER_SWITCH - (correctPrayer ? 1 : 0));
        } else if (correctPrayer) {
            // Floor at 0: live data showed a gorilla attacking the same style well past 3 times, so the
            // Woox "3 per switch" cadence doesn't hold cleanly here. The counter is diagnostic-only (not
            // wired into any decision); clamping just keeps it from drifting into confusing negatives.
            ctx.setAttacksUntilSwitch(Math.max(0, ctx.getAttacksUntilSwitch() - 1));
        }
        ctx.setNextAttackTick(tick + GorillaContext.ATTACK_RATE);

        // The animation is ground truth: end any pending prediction and pray the matching overhead.
        ctx.setCurrentAttackStyle(style);
        ctx.setAwaitingStyleSwitch(false);
        ctx.setStyleSwitchCryPending(false);
        ctx.setLastGorillaDistToPlayer(-1);
        // Re-pray whenever the overhead isn't ACTUALLY active (not just when it differs from the tracked
        // value) so prayer that got disabled — points ran out then a restore was drunk — comes back on.
        if (!Rs2Prayer.isPrayerActive(protect)) {
            GorillaHelpers.switchDefensivePrayer(ctx, protect);
        }
    }

    /** True if {@code actor} is the Demonic gorilla we're fighting (our tracked target, or the one we
     *  are mutually interacting with) — so another player's gorilla crying never triggers us. */
    private boolean isOurTargetGorilla(Actor actor) {
        if (!(actor instanceof NPC) || actor.getName() == null || !actor.getName().equalsIgnoreCase("Demonic gorilla")) {
            return false;
        }
        var target = getContext().getCurrentTarget();
        if (target != null && target.getNpc() == actor) {
            return true;
        }
        var localPlayer = Microbot.getClient().getLocalPlayer();
        return localPlayer != null && (actor.getInteracting() == localPlayer || localPlayer.getInteracting() == actor);
    }

    /**
     * TEMP measurement: log every point of damage WE take, tagged with which protection prayer was
     * actually active, the gorilla's current style, and whether a boulder just landed — so we can
     * classify real hits as prayer-failure (wrong overhead), boulder (AOE, unprotectable) or
     * protected-but-hit, instead of trusting the overcounting "prediction was wrong" metric.
     */
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (event.getActor() != Microbot.getClient().getLocalPlayer()) {
            return;
        }
        // Count damage taken for the off-thread diagnostics. A hit landing inside the boulder danger window
        // is attributed to the AOE (unprotectable) rather than a prayer miss. Field increments only — NO
        // logging here (client thread; GameChatAppender would deadlock, see onProjectileMoved).
        Hitsplat splat = event.getHitsplat();
        if (splat != null && splat.getAmount() > 0) {
            GorillaContext ctx = getContext();
            ctx.setDiagHitsTaken(ctx.getDiagHitsTaken() + 1);
            if (System.currentTimeMillis() < ctx.getBoulderDangerUntilMs()) {
                ctx.setDiagBoulderWindowHits(ctx.getDiagBoulderWindowHits() + 1);
            }
        }
        // (The rest of this handler was a TEMP damage-measurement log.) Logging from a client-thread
        // @Subscribe handler routes through Microbot's GameChatAppender, which blocks on a client-thread
        // invoke and deadlocks the game loop (see onProjectileMoved). The diagnostic has been removed;
        // if you need it back, write to a logger that is NOT attached to GameChatAppender, or record the
        // data into GorillaContext and log it off the client thread.
    }

    @Subscribe
    public void onGameTick(GameTick gameTick) {
        GorillaContext ctx = getContext();
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        ctx.setPlayerMoved(!ctx.getLastLocation().contains(currentLocation));
        ctx.getLastLocation().add(currentLocation);
        ctx.setGameTickCount(ctx.getGameTickCount() + 1);
        updateMeleeApproachTell(ctx, currentLocation);
    }

    /**
     * Phase 2 melee tell. After a magic/ranged "Rhaaaa" cry the new style is one of {the other ranged
     * style, melee} (never the same one), and the pipeline steps us away to create separation. Watched once
     * per game tick, on the client thread:
     *   - a MELEE gorilla must WALK to reach us, so it leaves the tile it cried from → pray melee;
     *   - a magic/ranged gorilla attacks from where it stands, so it stays on that tile → the never-same
     *     pre-pray already set on the cry stands.
     * (The earlier "distance must first open to >=4" gate was backwards: a gorilla that chases you keeps the
     * gap small, so it never opened and melee was never detected until the hit landed.)
     * A gorilla projectile fired since the cry is ground-truth proof it's NOT melee (Phase 3), so it vetoes
     * a melee flip and restores the ranged pre-pray. The actual attack animation (onAnimationChanged) always
     * wins as the final correction.
     */
    private void updateMeleeApproachTell(GorillaContext ctx, WorldPoint playerLoc) {
        if (ctx.getBotStatus() != GorillaContext.State.FIGHTING
                || !ctx.isAwaitingStyleSwitch()
                || ctx.getCurrentTarget() == null
                || ctx.getCurrentTarget().getNpc().isDead()) {
            return;
        }
        AttackStyle prev = ctx.getPreviousAttackStyle();
        if (prev != AttackStyle.MAGIC && prev != AttackStyle.RANGED) {
            return; // melee->magic/range is disambiguated by animation/projectile, not by movement
        }
        WorldPoint gorillaLoc = ctx.getCurrentTarget().getWorldLocation();
        WorldPoint baseline = ctx.getGorillaTileAtPrePray();
        if (gorillaLoc == null || baseline == null || gorillaLoc.getPlane() != baseline.getPlane()) {
            return;
        }

        // Ground truth: the gorilla threw a projectile → it's magic/ranged, so it CANNOT be melee. Undo any
        // premature melee flip and fall back to the deterministic never-same ranged pre-pray.
        if (ctx.isGorillaProjectileFiredSinceCry()) {
            if (ctx.getCurrentDefensivePrayer() == Rs2PrayerEnum.PROTECT_MELEE) {
                Rs2PrayerEnum ranged = prev == AttackStyle.MAGIC ? Rs2PrayerEnum.PROTECT_RANGE : Rs2PrayerEnum.PROTECT_MAGIC;
                GorillaHelpers.switchDefensivePrayer(ctx, ranged);
                ctx.setDiagProjectileVetoes(ctx.getDiagProjectileVetoes() + 1);
            }
            return;
        }

        // The gorilla walked off the tile it switched prayer on → it's coming to melee us.
        if (gorillaLoc.distanceTo(baseline) >= 1) {
            if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MELEE)) {
                GorillaHelpers.switchDefensivePrayer(ctx, Rs2PrayerEnum.PROTECT_MELEE);
                ctx.setDiagMeleeTells(ctx.getDiagMeleeTells() + 1);
            }
        }
    }
}

package net.runelite.client.plugins.custom.madangel;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelContext;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelContext.Side;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelHelpers;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

/**
 * Custom Mad Angel killer — combat-reactions scope. Assumes you're already at the boss, geared for
 * melee (crush) and praying offensively; it handles the three animation-triggered specials:
 * <ul>
 *   <li><b>Sweep</b> ({@code onAnimationChanged}) — arm a strafe to the safe side of the 2x2 boss.</li>
 *   <li><b>Blast</b> ({@code onAnimationChanged}/{@code onProjectileMoved}) — stand on the marked tile
 *       (graphics object 1448 / projectile 4015 target) to bounce the energy ball back.</li>
 *   <li><b>Smite</b> ({@code onAnimationChanged} + {@code onGameTick}) — tick-precise Protect-from-Magic
 *       flicks (once normally; +4/+4/+2 in the enrage phase).</li>
 * </ul>
 * All {@code @Subscribe} handlers run on the client thread and are kept LOG-FREE: logging there routes
 * through Microbot's GameChatAppender, which blocks on a client-thread invoke and can deadlock the game
 * loop (see the demonic-gorilla plugin's onProjectileMoved for the full explanation).
 */
@PluginDescriptor(
        name = "Mad Angel (Custom)",
        description = "Custom build: dodges the Mad Angel's sweep and blast, and flicks the smite prayer",
        tags = {"mad", "angel", "boss", "flicker", "prayer", "dodge", "microbot", "custom"},
        version = MadAngelPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class MadAngelPlugin extends Plugin {

    public static final String version = "1.0.0";

    @Inject
    private MadAngelConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private MadAngelOverlay overlay;
    @Inject
    private MadAngelSceneOverlay sceneOverlay;
    @Inject
    private MadAngelScript script;

    @Provides
    MadAngelConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MadAngelConfig.class);
    }

    /** Exposes the durable fight state for the overlay and this plugin's event handlers. */
    public MadAngelContext getContext() {
        return script.getContext();
    }

    @Override
    protected void startUp() {
        if (overlayManager != null) {
            overlayManager.add(overlay);
            overlayManager.add(sceneOverlay);
        }
        script.run();
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
        overlayManager.remove(sceneOverlay);
    }

    /** Compact "x,y" rendering of a WorldPoint for the diagnostic logs (null-safe). */
    private static String pt(WorldPoint p) {
        return p == null ? "?" : p.getX() + "," + p.getY();
    }

    /** True if {@code actor} is a Mad Angel. It's a solo/instanced boss, so any Mad Angel is ours. */
    private boolean isAngel(Actor actor) {
        return actor instanceof NPC && actor.getName() != null
                && actor.getName().equalsIgnoreCase(MadAngelHelpers.ANGEL_NAME);
    }

    /**
     * Arms the reaction for the boss's current animation. Sweep/blast set flags consumed by the action
     * pipeline; smite schedules its tick-precise Protect-from-Magic flicks (executed in onGameTick).
     */
    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        Actor actor = event.getActor();
        if (!isAngel(actor)) {
            return;
        }
        MadAngelContext ctx = getContext();
        int anim = actor.getAnimation();
        ctx.setLastAnimation(anim);
        int tick = Microbot.getClient().getTickCount();

        boolean sweepLeft = MadAngelHelpers.SWEEP_LEFT_ANIMS.contains(anim);
        boolean sweepRight = MadAngelHelpers.SWEEP_RIGHT_ANIMS.contains(anim);

        // Diagnostic sample (log-free here; SweepTimingAction drains & logs it off the client thread).
        if (config.logAnimTiming()) {
            String kind;
            if (sweepLeft) kind = "sweep-L";
            else if (sweepRight) kind = "sweep-R";
            else if (anim == MadAngelHelpers.BLAST_ANIM) kind = "blast";
            else if (anim == MadAngelHelpers.SMITE_NORMAL_ANIM) kind = "smite";
            else if (anim == MadAngelHelpers.SMITE_ENRAGE_ANIM) kind = "smite-enrage";
            else if (anim == -1) kind = "idle";
            else kind = "other";
            ctx.getAnimEventLog().offer(new MadAngelContext.AnimEvent(
                    tick, anim, actor.getHealthRatio(), actor.getHealthScale(), kind));
        }

        if (config.enableSweepDodge() && (sweepLeft || sweepRight)) {
            // First cleave of the sweep: fix the two dodge tiles — where we stand now, and the tile
            // straight THROUGH her (scene-coords, so it's valid in this rotated instance). Each cleave
            // then just ping-pongs between them (no per-cleave recompute → no drift, no orientation
            // timing). sweepActive gates AttackAction so we hold between cleaves instead of re-approaching.
            ctx.setSweepLastAnimTick(tick);
            if (!ctx.isSweepActive()) {
                ctx.setSweepActive(true);
                ctx.setSweepOriginTile(Rs2Player.getWorldLocation());
                ctx.setSweepThroughTile(MadAngelHelpers.computeSweepThroughTile((NPC) actor));
                ctx.setSweepNextIsThrough(true);
                armSweepPlan(ctx, actor, tick, sweepLeft);
            }
            ctx.setSweepPending(true);
            ctx.setSweepSide(sweepLeft ? Side.LEFT : Side.RIGHT);
            ctx.setLastReaction(sweepLeft ? "sweep-left" : "sweep-right");
        } else if (config.enableBlastDodge() && anim == MadAngelHelpers.BLAST_ANIM) {
            armBlast(ctx, tick);
            ctx.setLastReaction("blast");
        } else if (config.enableSmitePrayer() && anim == MadAngelHelpers.SMITE_NORMAL_ANIM) {
            armSmite(ctx, tick, MadAngelHelpers.SMITE_NORMAL_ON_TICKS);
            ctx.setLastReaction("smite");
        } else if (config.enableSmitePrayer() && anim == MadAngelHelpers.SMITE_ENRAGE_ANIM) {
            armSmite(ctx, tick, MadAngelHelpers.SMITE_ENRAGE_ON_TICKS);
            ctx.setLastReaction("smite-enrage");
        }
    }

    /**
     * Arms the tick-scheduled sweep-plan simulator at the first cleave (diagnostics only — no movement).
     * Phase is read from the boss's health bar (enrage ≤ {@code ENRAGE_HP_PERCENT}); that sets the cleave
     * count and interval. The per-cleave prints then fire from onGameTick counting ticks off {@code tick}.
     */
    private void armSweepPlan(MadAngelContext ctx, Actor actor, int tick, boolean sweepLeft) {
        if (ctx.isSweepPlanActive()) {
            return; // one plan per sweep; drives both the (real) tick-scheduled dodge and the diag log
        }
        int ratio = actor.getHealthRatio();
        int scale = actor.getHealthScale();
        boolean enrage = scale > 0 && ratio >= 0 && ratio * 100 <= scale * MadAngelHelpers.ENRAGE_HP_PERCENT;
        ctx.setSweepPlanActive(true);
        ctx.setSweepPlanEnrage(enrage);
        ctx.setSweepPlanInterval(enrage
                ? MadAngelHelpers.SWEEP_ENRAGE_INTERVAL_TICKS : MadAngelHelpers.SWEEP_NORMAL_INTERVAL_TICKS);
        ctx.setSweepPlanTotal(enrage
                ? MadAngelHelpers.SWEEP_ENRAGE_CLEAVES : MadAngelHelpers.SWEEP_NORMAL_CLEAVES);
        ctx.setSweepPlanIndex(0);
        ctx.setSweepPlanNextTick(tick);
        ctx.setSweepPlanFirstSide(sweepLeft ? Side.LEFT : Side.RIGHT);
    }

    private void armBlast(MadAngelContext ctx, int tick) {
        ctx.setBlastActive(true);
        ctx.setBlastArmedTick(tick);
        ctx.setBlastProjectileSeen(false);
        ctx.setBlastTile(null);
    }

    private void armSmite(MadAngelContext ctx, int startTick, int[] offsets) {
        int[] onTicks = new int[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            onTicks[i] = startTick + offsets[i];
        }
        ctx.setSmiteOnTicks(onTicks);
        ctx.setSmitePrayerOn(false);
        ctx.setSmiteActive(true);
    }

    /**
     * Notes that the blast energy ball (projectile 4015) is in flight. We do NOT take the stand-tile
     * from the projectile target: on the return bounce that target flips toward the boss, which would
     * walk us into her. The tile comes from the fixed id-1448 graphics object instead (see onGameTick).
     */
    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        if (!config.enableBlastDodge()) {
            return;
        }
        Projectile projectile = event.getProjectile();
        if (projectile.getId() != MadAngelHelpers.BLAST_PROJECTILE) {
            return;
        }
        MadAngelContext ctx = getContext();
        int tick = Microbot.getClient().getTickCount();
        ctx.setBlastProjectileSeen(true);
        ctx.setBlastLastProjectileTick(tick);
        ctx.setBlastActive(true);
        if (ctx.getBlastArmedTick() < 0) {
            ctx.setBlastArmedTick(tick);
        }
    }

    /**
     * Diagnostic: records the tick and amount of every hitsplat landing on US. This is the ground-truth
     * timing of when the sweep/blast/smite damage actually lands, so the dodge schedule can be tuned to
     * be off-line on exactly those ticks. Enqueued log-free; drained by SweepTimingAction.
     */
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (!config.logAnimTiming()) {
            return;
        }
        if (event.getActor() != Microbot.getClient().getLocalPlayer()) {
            return; // only damage taken by the player — not what we deal to the boss
        }
        MadAngelContext ctx = getContext();
        int tick = Microbot.getClient().getTickCount();
        int amount = event.getHitsplat().getAmount();
        ctx.getAnimEventLog().offer(new MadAngelContext.AnimEvent(tick, amount, -1, -1, "player-hit"));
    }

    /**
     * Client-thread, tick-aligned work: the tick-precise smite prayer flicks and the blast resolution
     * check. Kept here (not in the 50 ms action pipeline) so flicks land on exact game ticks.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        MadAngelContext ctx = getContext();
        Client client = Microbot.getClient();
        int tick = client.getTickCount();

        // Diagnostic: sample the angel's RAW animation every tick (log-free enqueue; SweepTimingAction
        // drains it off the client thread). AnimationChanged only fires on id *changes*, so repeated
        // cleaves that reuse an id are invisible to it — this per-tick view shows the true cadence.
        if (config.logAnimTiming()) {
            var t = ctx.getCurrentTarget();
            NPC angel = t != null ? t.getNpc() : null;
            if (angel != null && angel.getAnimation() != -1) {
                ctx.getAnimEventLog().offer(new MadAngelContext.AnimEvent(
                        tick, angel.getAnimation(), angel.getHealthRatio(), angel.getHealthScale(), "raw"));
            }
        }

        // Client-thread read: are WE auto-attacking the angel? (getInteracting() throws off the client
        // thread, so the pipeline reads this flag instead.) Only OUR interaction with her counts — NOT
        // her targeting us, which is true the entire fight and would stop us ever re-attacking after a
        // dodge. Our interaction clears when a dodge/blast moves us, which is exactly when we re-engage.
        var tgt = ctx.getCurrentTarget();
        var localPlayer = client.getLocalPlayer();
        boolean engaged = localPlayer != null && tgt != null && tgt.getNpc() != null
                && localPlayer.getInteracting() == tgt.getNpc();
        ctx.setEngagedWithAngel(engaged);

        // --- Smite: flick Protect from Magic ON on each scheduled tick, OFF the tick after. Suppressed
        //     during the post-kill sequence so a smite whose flick schedule outlived the kill can't
        //     re-enable Protect-from-Magic while we're looting. ---
        if (!ctx.isInPostKill() && ctx.isSmiteActive() && ctx.getSmiteOnTicks() != null) {
            int[] onTicks = ctx.getSmiteOnTicks();
            boolean shouldTurnOn = false;
            boolean shouldTurnOff = false;
            for (int ot : onTicks) {
                if (tick == ot) shouldTurnOn = true;
                if (tick == ot + 1) shouldTurnOff = true;
            }
            if (shouldTurnOn && !ctx.isSmitePrayerOn()) {
                MadAngelHelpers.setProtectMagic(ctx, true);
            } else if (shouldTurnOff && ctx.isSmitePrayerOn()) {
                MadAngelHelpers.setProtectMagic(ctx, false);
            }
            int last = onTicks[onTicks.length - 1];
            if (tick > last + 1) {
                if (ctx.isSmitePrayerOn()) {
                    MadAngelHelpers.setProtectMagic(ctx, false);
                }
                ctx.setSmiteActive(false);
            }
        }

        // --- Blast: follow the CURRENT id-1448 graphics marker (it moves to a new tile each bounce, and
        //     is always the safe tile — unlike the bouncing projectile target, which aims at the boss).
        //     Resolve once both the marker and the ball have been gone a grace period, or a timeout. ---
        if (ctx.isBlastActive()) {
            WorldPoint marked = MadAngelHelpers.findBlastTile();
            if (marked != null) {
                ctx.setBlastTile(marked);
                ctx.setBlastProjectileSeen(true);
                ctx.setBlastLastProjectileTick(tick); // marker present = blast still going
            }
            if (MadAngelHelpers.blastProjectilePresent()) {
                ctx.setBlastProjectileSeen(true);
                ctx.setBlastLastProjectileTick(tick);
            }
            boolean resolved = ctx.isBlastProjectileSeen()
                    && tick - ctx.getBlastLastProjectileTick() >= MadAngelHelpers.BLAST_RESOLVE_GRACE_TICKS;
            boolean timedOut = tick - ctx.getBlastArmedTick() > MadAngelHelpers.BLAST_TIMEOUT_TICKS;
            if (resolved || timedOut) {
                ctx.setBlastActive(false);
                ctx.setBlastTile(null);
                ctx.setBlastProjectileSeen(false);
            }
        }

        // --- Sweep ends once no cleave animation has fired for a couple ticks; then AttackAction resumes. ---
        if (ctx.isSweepActive() && tick - ctx.getSweepLastAnimTick() > MadAngelHelpers.SWEEP_END_TICKS) {
            ctx.setSweepActive(false);
        }

        // --- Sweep-plan simulator: emit one scheduled line per cleave (interval + count by phase), then
        //     disarm. Side is read from the CURRENT animation at each tick so we can see if the safe side
        //     flips per cleave; falls back to the first cleave's side when the tick lands between cleaves.
        //     Logs only — no movement — so we can validate the tick schedule before changing the dodge. ---
        if (ctx.isSweepPlanActive()) {
            var t = ctx.getCurrentTarget();
            NPC angel = t != null ? t.getNpc() : null;
            if (angel == null || angel.isDead()) {
                ctx.setSweepPlanActive(false);
            } else {
                while (ctx.isSweepPlanActive() && tick >= ctx.getSweepPlanNextTick()
                        && ctx.getSweepPlanIndex() < ctx.getSweepPlanTotal()) {
                    int anim = angel.getAnimation();
                    // Safe side alternates per cleave, tracked by the current cleave animation. Sword side
                    // is the danger side → dodge to the OPPOSITE side of the boss (boss-frame tiles).
                    Side sword = MadAngelHelpers.sweepSideFor(anim);
                    MadAngelHelpers.DodgeSpots spots = MadAngelHelpers.computeDodgeSpots(client, angel);
                    WorldPoint safeTile = null;
                    if (spots != null) {
                        if (sword == Side.LEFT) {
                            safeTile = spots.dodgeRight;      // sword left -> go boss-right
                        } else if (sword == Side.RIGHT) {
                            safeTile = spots.dodgeLeft;       // sword right -> go boss-left
                        }
                    }

                    // Hand the tile to SweepDodgeAction (script thread) to walk. Reuse the previous safe
                    // tile if this scheduled tick landed between cleaves (non-sweep anim). Skipped in
                    // dry-run so the overlay can still be validated without moving.
                    if (config.enableSweepDodge() && !config.sweepDodgeDryRun()) {
                        WorldPoint go = safeTile != null ? safeTile : ctx.getSweepDodgeTile();
                        if (go != null) {
                            ctx.setSweepDodgeTile(go);
                            ctx.setSweepDodgeWalkPending(true);
                        }
                    }

                    if (config.logAnimTiming()) {
                        String side = sword == Side.LEFT ? "RIGHT"
                                : sword == Side.RIGHT ? "LEFT"
                                : "(" + ctx.getSweepPlanFirstSide() + "?)";
                        String geom = spots == null ? "spots=?"
                                : String.format("safe=%s (ori=%d facing=%s player=%s)",
                                pt(safeTile), angel.getOrientation(), spots.playerSide,
                                pt(Rs2Player.getWorldLocation()));
                        String note = String.format("SWEEP %s cleave %d/%d -> go %s (anim=%d) | %s",
                                ctx.isSweepPlanEnrage() ? "ENRAGE" : "normal",
                                ctx.getSweepPlanIndex() + 1, ctx.getSweepPlanTotal(), side, anim, geom);
                        ctx.getAnimEventLog().offer(new MadAngelContext.AnimEvent(
                                tick, anim, angel.getHealthRatio(), angel.getHealthScale(), "sweep-plan", note));
                    }

                    ctx.setSweepPlanIndex(ctx.getSweepPlanIndex() + 1);
                    ctx.setSweepPlanNextTick(ctx.getSweepPlanNextTick() + ctx.getSweepPlanInterval());
                }
                if (ctx.getSweepPlanIndex() >= ctx.getSweepPlanTotal()) {
                    ctx.setSweepPlanActive(false);
                }
            }
        }

        // --- Default defensive overhead = Protect from Melee (the angel's standard hit). The smite flick
        //     to Protect from Magic disables it, so restore/keep Protect from Melee whenever not smiting.
        //     Suppressed during the post-kill sequence so all prayers stay off. ---
        if (config.enableProtectFromMelee() && !ctx.isSmiteActive() && !ctx.isInPostKill()) {
            MadAngelHelpers.ensureProtectFromMelee();
        }
    }
}

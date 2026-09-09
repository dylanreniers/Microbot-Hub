package net.runelite.client.plugins.custom.madangel.actions;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Durable state of a Mad Angel fight, shared across ticks. The action pipeline (script thread) reads
 * it; the plugin's {@code onAnimationChanged}/{@code onProjectileMoved}/{@code onGameTick} handlers
 * (client thread) write to it. Fields touched from both threads are {@code volatile}. Mirrors the
 * {@code GorillaContext} pattern so state lives in one place and never leaks across plugin restarts.
 */
@Getter
@Setter
public class MadAngelContext {

    /** Which side of the 2x2 angel to strafe to for a sweep, from the player's facing perspective. */
    public enum Side {LEFT, RIGHT}

    // --- Target ---
    private volatile Rs2NpcModel currentTarget;
    private volatile int failedAttacks;
    /** Whether we're currently auto-attacking the angel. Computed on the client thread in the plugin's
     *  onGameTick (getInteracting() must run there); AttackAction reads it to avoid spam-clicking. */
    private volatile boolean engagedWithAngel;

    // --- Sweep dodge (armed by the sweep attack animations) ---
    /** True for the whole duration of a sweep (first cleave until it ends) — we dodge ONCE and hold. */
    private volatile boolean sweepActive;
    /** One-shot: set on the first cleave to trigger the single dodge walk, then consumed. */
    private volatile boolean sweepPending;
    private volatile Side sweepSide;
    /** The two dodge tiles, fixed at the first cleave of a sweep: where we were, and the tile straight
     *  through her. We ping-pong between them on each cleave (no per-cleave recompute, so no drift). */
    private volatile WorldPoint sweepOriginTile;
    private volatile WorldPoint sweepThroughTile;
    /** Which of the two tiles the next cleave dodges to (true = the through-her tile). */
    private volatile boolean sweepNextIsThrough = true;
    /** Client tick of the most recent cleave animation; the sweep is "over" once these stop arriving. */
    private volatile int sweepLastAnimTick = -100;

    // --- Blast / "burst" (energy ball to a marked tile: stand on it to bounce it back) ---
    private volatile boolean blastActive;
    /** The marked tile to stand on — from the projectile's target, or the id-1448 graphics object. */
    private volatile WorldPoint blastTile;
    private volatile int blastArmedTick = -100;
    /** True once we've actually seen projectile 4015 in flight; the blast resolves when it despawns. */
    private volatile boolean blastProjectileSeen;
    /** Client tick of the most recent 4015 sighting; the blast resolves once the ball has been gone a
     *  grace period (it bounces up to 3 times, so brief gaps must not end the reaction early). */
    private volatile int blastLastProjectileTick = -100;
    /** Wall-clock of the last walk issued to the marked tile; debounces so we click once, not every tick. */
    private volatile long lastBlastWalkMs;

    // --- Smite (magic charge: flick Protect from Magic on precise ticks). All client-thread. ---
    private volatile boolean smiteActive;
    /** Absolute client-tick counts on which Protect from Magic must be toggled ON. */
    private volatile int[] smiteOnTicks;
    /** Whether we currently have Protect from Magic toggled on for the flick (so we know when to flick off). */
    private volatile boolean smitePrayerOn;

    // --- Prayer tracking ---
    private volatile Rs2PrayerEnum currentOffensivePrayer;

    // --- Diagnostics / overlay ---
    private volatile int lastAnimation = -1;
    private volatile String lastReaction = "none";
    /** One angel animation-change sample, captured log-free on the client thread. */
    public static final class AnimEvent {
        public final int tick;
        public final int anim;
        public final int healthRatio;
        public final int healthScale;
        public final String kind;

        public AnimEvent(int tick, int anim, int healthRatio, int healthScale, String kind) {
            this.tick = tick;
            this.anim = anim;
            this.healthRatio = healthRatio;
            this.healthScale = healthScale;
            this.kind = kind;
        }
    }

    /** Animation-change samples enqueued (log-free) by the plugin's client-thread onAnimationChanged and
     *  drained/logged on the script thread by {@code SweepTimingAction}. Used to measure cleave intervals
     *  so the sweep dodge can move to a tick schedule (normal vs enrage) instead of reacting per-cleave. */
    private final Queue<AnimEvent> animEventLog = new ConcurrentLinkedQueue<>();

    /** Clears all fight state — called on start and shutdown so nothing leaks across restarts. */
    public void reset() {
        currentTarget = null;
        failedAttacks = 0;
        engagedWithAngel = false;
        sweepActive = false;
        sweepPending = false;
        sweepSide = null;
        sweepOriginTile = null;
        sweepThroughTile = null;
        sweepNextIsThrough = true;
        sweepLastAnimTick = -100;
        blastActive = false;
        blastTile = null;
        blastArmedTick = -100;
        blastProjectileSeen = false;
        blastLastProjectileTick = -100;
        lastBlastWalkMs = 0;
        smiteActive = false;
        smiteOnTicks = null;
        smitePrayerOn = false;
        currentOffensivePrayer = null;
        lastAnimation = -1;
        lastReaction = "none";
        animEventLog.clear();
    }
}

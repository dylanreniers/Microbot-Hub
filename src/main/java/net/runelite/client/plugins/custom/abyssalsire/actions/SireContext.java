package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.GraphicsObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable state of the current Abyssal Sire fight, shared across ticks. Actions read/write it via
 * {@link SireState#context()}; the plugin's NPC/animation events also write to it. Fields touched
 * from both the client (event) thread and the script tick thread are volatile / concurrent.
 *
 * <p>The phase is driven entirely by events (NpcSpawned/NpcChanged ids, death/explosion animations)
 * via {@link net.runelite.client.plugins.custom.abyssalsire.AbyssalSireScript}. {@link #reset()}
 * clears everything for the next kill and re-arms the startup bootstrap.
 */
@Getter
@Setter
public class SireContext {

    // --- Environment (set once on initialise) ---
    private Rs2InventorySetup meleeSetup;
    private Rs2InventorySetup rangeSetup;
    /** Configurable combat thresholds, snapshotted from the config on init. */
    private int eatPercent;
    private int prayerPercent;
    private int lootMinValue;
    private boolean hopEnabled;
    private boolean restockEnabled;
    /** Minimum HP% to top up to before starting a kill. */
    private int startKillMinHpPercent;
    /** Whether to dump special attacks at the start of phase 2, and the per-spec energy cost (%). */
    private boolean specEnabled;
    private int specCostPercent;

    // --- Between-kills restock. Armed by LootAction once looting finishes (when restock is enabled),
    //     consumed by ReturnToSireAction, which runs the whole house/resupply/travel trip and clears it.
    //     Deliberately NOT cleared by reset(): the trip's teleports fire LOADING -> onGameStateChanged
    //     resets the fight state mid-trip, and this flag must outlive that so the trip keeps running. ---
    private volatile boolean prepPending;

    // --- Phase ---
    private volatile SirePhase phase = SirePhase.IDLE;
    /** True once the Sire has woken/engaged this fight (observed id 5887/5888). Distinguishes the
     *  phase-2 tell (a CHANGE to 5886 once fighting) from the initial 5886 spawn. */
    private volatile boolean fightStarted;
    /** The Sire's current NPC id, updated on every spawn/transform. Phase 2 only enables Protect from
     *  Melee once this is 5890 (the exposed, melee-attacking state) — before that the Sire can't hit us. */
    private volatile int currentSireId = -1;
    /** Set on the Sire's death animation; consumed by LootAction, which resets for the next kill. */
    private volatile boolean dead;
    /** Re-armed on start and after each kill: the script does a one-time live query to catch a Sire
     *  that was already present (no spawn event fired), then clears it once seeded. */
    private volatile boolean needsBootstrap = true;

    // --- Phase 1 (barrage + range respiratory) ---
    /** Wall-clock of the last Shadow Barrage cast, 0 = not cast yet this cycle. The 3s post-barrage
     *  settle and the "tentacles asleep" check both gate off this. */
    private volatile long barrageCastAtMs;
    /** Canonical tiles of respiratory systems we've already killed this fight, so a re-barrage resumes
     *  on the ones still up. Written from the NpcDespawned event thread, read from the tick thread. */
    private final Set<WorldPoint> killedRespiratory = ConcurrentHashMap.newKeySet();
    /** Highest live respiratory-system count seen this fight. Phase 2 only starts once we've seen all
     *  four up AND they're all down again, so a transient/early 0 can't skip a vent. */
    private volatile int maxRespiratorySeen;
    /** Latched when the tentacles wake so we COMMIT to walking back and re-barraging — even if the
     *  tentacles go dormant during the walk. Cleared only once the barrage is actually cast. */
    private volatile boolean rebarragePending;
    /** Wall-clock when the current barrage disorient expires (set from the "disorientated" chat message
     *  = now + 46 ticks). 0 = not stunned. Drives when to range vs re-barrage in phase 1. */
    private volatile long sireStunnedUntilMs;

    /** Active miasma pools, tracked from their GraphicsObjectCreated event (spot-anim 1275) until the
     *  object despawns. The tile is captured ONCE at spawn (stable) rather than re-derived each tick,
     *  and the pool is held until the object finishes — so "pool on the original spot" doesn't flicker
     *  false while it's still down. Written from the event thread, read/pruned on the tick thread. */
    private final Map<GraphicsObject, WorldPoint> miasmaPools = new ConcurrentHashMap<>();

    /** One-shot latch: drink the melee stat boosts (super combat / super att-str-def) once at the start
     *  of phase 2, then leave them alone. Reset each fight. */
    private volatile boolean boostsDrunk;

    /** Phase-2 special-attack dump: latched once spec energy is spent (or the window elapses / no spec
     *  weapon), so we spec at the start of phase 2 and then attack normally. {@code specStartMs} bounds
     *  the dump so a non-spec weapon (energy never drops) can't hold us in spec mode forever. */
    private volatile boolean specsDone;
    private volatile long specStartMs;

    /** Set whenever we reposition (miasma/explosion dodge). Walking breaks the melee interaction, so on
     *  arrival the phase actions force a fresh attack click instead of assuming we're still attacking
     *  (getInteracting() lingers stale on the Sire after a move). */
    private volatile boolean attackAfterMove;

    // --- Phase 3 (explosion dodge) ---
    /** Set when the player is knocked back by the explosion (animation 1816); cleared once the Sire
     *  recovers (id 5908). While set we run to the escape tile and hold fire. */
    private volatile boolean exploding;

    /** Cooldown gate so we don't re-drink antipoison every tick while the varbit is still updating. */
    private volatile long nextAntipoisonMs;

    // --- Loot ---
    /** Give-up time for looting, extended on each successful pickup. */
    private volatile long lootDeadlineMs;

    /** Clears per-fight state so a fresh encounter starts at phase 1 (via the re-armed bootstrap). */
    public void reset() {
        phase = SirePhase.IDLE;
        fightStarted = false;
        currentSireId = -1;
        dead = false;
        needsBootstrap = true;
        barrageCastAtMs = 0;
        killedRespiratory.clear();
        maxRespiratorySeen = 0;
        miasmaPools.clear();
        rebarragePending = false;
        sireStunnedUntilMs = 0;
        attackAfterMove = false;
        boostsDrunk = false;
        specsDone = false;
        specStartMs = 0;
        exploding = false;
        nextAntipoisonMs = 0;
        lootDeadlineMs = 0;
    }
}

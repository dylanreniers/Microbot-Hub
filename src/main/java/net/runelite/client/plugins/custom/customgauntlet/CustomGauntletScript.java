package net.runelite.client.plugins.custom.customgauntlet;

import com.google.inject.Inject;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.walker.Rs2MiniMap;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class CustomGauntletScript extends Script {

    private static final int CG_TORNADO = 9039;
    private static final int REGULAR_HUNLEFF_TORNADO = 9025;
    private static final int PADDLEFISH_HEAL_VALUE = 20;

    private static final int CD_NPC = 450;
    private static final int CD_PRAYOFFENCE = 100;
    private static final int CD_EAT = 600;
    private static final int CD_DRINK = 600;
    private static final int CD_WEAPON = 600;
    private static final int CD_REATTACK = 600;         // re-engage the boss within ~1 tick of stopping (anti-spam only; the game caps real DPS)
    private static final int CD_RECENTLY = 25;
    private static final int CD_STEEL = 300;
    private static final int CD_HUNLLEF_LOG = 2000;
    private static final int CD_SAFESPOT = 600;
    private static final int BUFFER_MAGICBLAST = 400;
    private static final int LOOPS_PER_TICK = 5;

    // --- Tornado dodging ---
    private static final int CD_CAPTURE = 150;          // capture-mode sampling cadence (ms); de-duped to one line per decision
    private static final int SCENE_SIZE = 104;          // RuneLite scene is 104x104 tiles
    private static final int HUNLLEF_RADIUS = 2; // 5x5 footprint -> 2 tiles from its centre to each edge
    private static final int MIN_NEW_LANE_TILES = 6; // a perpendicular lane must be at least this long to switch onto
    private static final int MIN_NEW_LANE_RUN = 3;   // ...and offer at least this many tiles of run from where we stand
    private static final int EXIT_END_BONUS = 300;   // lane score per end that has a perpendicular way out
    private static final int BOSS_HUG_PENALTY = 80;  // lane score per tile touching the Hunllef's footprint
    private static final int BOSS_NEAR_PENALTY = 30; // ...and per tile one further out
    // Escape (no safe route): per-tile step costs. Warning tiles don't hurt yet, so they're crossed before live ones.
    private static final int ESCAPE_COST_WARNING = 4;
    private static final int ESCAPE_COST_DAMAGE = 12;
    private static final int ESCAPE_COST_BOSS = 25;
    private static final int ESCAPE_COST_TORNADO = 40;
    private static final int ESCAPE_MIN_LANE_TILES = 4;
    private static final long FLEE_RECLICK_MS = 600; // re-click a committed flee target only if the walk dropped
    // Mouse clicks take a few hundred ms to land (natural mouse movement); a second click started before the first
    // lands replaces it. Keep eat clicks and walk clicks at least this far apart in either direction.
    private static final int SIM_TICKS_AFTER_ARRIVAL = 2; // keep simulating the tornado chase this long after a run ends
    private static final int CRITICAL_HP = 45;            // below this, eat even while a dodge move is in progress
    private static final int FLEE_MAX_TILES = 4;
    // Ticks before a click takes effect. Measured with the human-speed click hand (v1.10.2): 60-80% of tornado-phase
    // clicks landed a tick after they were decided, walks taking ~340ms - planning with 1 made "pass 1" runs end at 0.
    private static final int CLICK_LATENCY_TICKS = 2;
    private static final int LOOP_MIN_RING = HUNLLEF_RADIUS + 1; // smallest ring: the tiles touching the footprint
    private static final int LOOP_MAX_RING = HUNLLEF_RADIUS + 3;
    private static final double LOOP_MAX_SAFE_FRACTION = 0.4;   // enter loop mode once hazards cover 60%+ of the arena
    private static final int LOOP_LOOKAHEAD = 5;      // click this many ring tiles ahead (fewer if a straight leg isn't safe)
    private static final int LOOP_SIM_TICKS = 6;      // ticks of running simulated when choosing the direction
    private static final int EAT_CONFIRM_TICKS = 2;       // ticks to wait for an eat's heal before eating again
    private static final int EAT_BIG_HIT = 15;            // ...unless HP dropped this much since the eat     // tornado steps before a fresh walk click moves us
    private static final long HIT_EXPECTED_MS = 1800;     // ~3 ticks: how long a predicted unavoidable hit unlocks normal eating          // farther than this, cross the hazards via escapeAcrossDanger instead // prefer escaping to a lane at least this long (room to dodge)
    private static final int MID_RUN_ATTACK_MIN_LANE = 8;  // only attack mid-run on lanes of 8+ tiles
    private static final int MID_RUN_ATTACK_TILES = 4;     // tiles run since the last attack / run start before attacking again
    private static final int MID_RUN_ATTACK_PAUSE_TICKS = 2; // ticks an attack click stands us still mid-run
    private static final int MID_RUN_ATTACK_MIN_TICKS = 4; // bow/staff attack speed: don't pause for an attack that can't fire yet
    private static final int BOSS_CORRIDOR_TILES = 2;   // a run/lane with this many tiles beside the Hunllef (no side exit) is a corridor
    private static final int BOSS_CORRIDOR_PENALTY = 3000;
    private static final int BREAKOUT_MIN_HITS = 3;     // an evade expecting this many tornado hits tries a break-out across hazards
    private static final int BREAKOUT_MIN_HITS_HEALTHY = 2; // ...or this many with HP >= BREAKOUT_HEALTHY_HP and a protection prayer up
    private static final int BREAKOUT_HEALTHY_HP = 80;
    private static final int BREAKOUT_HIT_COST = 2;     // weight of one tornado hit in a break-out's cost
    private static final int BREAKOUT_HAZARD_COST = 2;  // weight of one hazard landing: as bad as a tornado hit (2 landings cost 46 HP)
    private static final int EVADE_STICK_TICKS = 2;
    private static final int EAT_PER_EXPECTED_HIT = 15;     // eat this much earlier on the run per hit the evade expects
    private static final int TORNADO_HIT_DMG = 17;          // a tornado hit, roughly (a 3-stack landed 19/15/12)
    private static final int LETHAL_MARGIN = 10;            // expected damage within this of our HP counts as lethal
    private static final int PLAN_FIRST_CANDIDATES = 30;   // first runs that get a full second-move search
    private static final int PLAN_SECOND_LATENCY_TICKS = 1; // the second click goes out as the first run ends
    private static final int PLAN_DEAD_END_COST = 250;     // no safe run on from the first tile: about 2.5 hits
    // Plan costs are in expected damage, scaled so a tornado hit (~17 HP, each tornado of a stack hits) is 100: a tick
    // ending on a hazard (~10-20 HP) is 80. A route across a hazard block beats running into a 4-tornado stack.
    private static final int PLAN_HAZARD_LANDING_COST = 80;
    // With a tornado this close, a run that still gets hit must cover at least this many tiles (1-tile hops beside a
    // 4-tornado stack walked us into 62 damage in one tick).
    private static final int CLOSE_TORNADO_TILES = 3;
    private static final int MIN_RUN_NEAR_TORNADO = 4;
    private static final int PLAN_SWITCH_MARGIN = 20;      // a new plan replaces a recent evade only if this much cheaper        // a new evade target replaces one this recent only if it's better
    private static final int EVADE_FOLLOW_UP_CANDIDATES = 20; // best clean evade targets checked for a clean run onward
    private static final int EQUIP_CONFIRM_TICKS = 3;      // ticks between weapon equip clicks
    private static final int TORNADO_ATTACK_INTERVAL_TICKS = 5; // during tornadoes: one shot per ~3s, like clicking the boss by hand
    private static final int ATTACK_RECLICK_TICKS = 2;     // anti-spam between attack clicks while the weapon is ready
    private static final int TOPUP_HP_MARGIN = 20;         // between tornado phases, eat once HP is a full paddlefish below max (no overheal)
    private static final int TOPUP_MIN_FISH = 5;           // ...but only while more fish than this are left
    private static final int PRAYER_EMERGENCY = 10;        // "eat/drink only during tornadoes": drink below this anyway
    // Player attack animations (bow, staff, halberd jab/swipe): mark the tick the weapon actually fired.
    private static final Set<Integer> PLAYER_ATTACK_ANIMS = Set.of(426, 1167, 428, 440);
    // Barrier objects enclosing the Hunllef arena (4 of them). The id differs between normal and
    // corrupted, and the same id is scattered across the maze — so we also filter by distance to the boss.
    private static final Set<Integer> BARRIER_IDS = Set.of(
            37339, // normal Gauntlet
            37337 // Corrupted Gauntlet
    );
    // Only barriers within this many tiles of the Hunllef belong to its arena. 37339 is a generic maze
    // barrier, so after roaming to gather resources the loaded scene contains other 37339s; without this
    // proximity filter they pollute (or block) the derived arena bounds and inArena() reads false.
    private static final int ARENA_BARRIER_RADIUS = 11;
    // A real in-fight Hunllef is always within the 12x12 arena (<~12 tiles). After we leave the room the
    // NPC cache can keep a stale reference hundreds of tiles away; treat anything beyond this range as
    // absent so the fight actually ends and the arena bounds get re-derived for the next fight.
    private static final int HUNLLEF_PRESENCE_RANGE = 20;

    private static final Set<Integer> TORNADO_IDS = Set.of(
            CG_TORNADO,
            REGULAR_HUNLEFF_TORNADO
    );

    private static final Set<Integer> HUNLLEF_IDS = Set.of(
            9035, 9036, 9037, 9038, // Corrupted Hunllef variants
            9021, 9022, 9023, 9024  // Crystalline Hunllef variants
    );

    private static final Set<Integer> DANGEROUS_TILES = Set.of(
            36047, 36048, 36049, 36050, 36051, // Corrupted tiles
            36150, 36151, 36152, 36153, 36154  // Gauntlet tiles
    );

    private static final Set<Integer> WARNING_TILES = Set.of(
            36047, 36150 // Corrupted & Gauntlet warning floor tiles (3 ticks before damage)
    );

    private static final Set<Integer> DAMAGING_TILES = Set.of(
            36048, 36049, 36050, 36051,
            36151, 36152, 36153, 36154 // Active damage floor tiles
    );

    private static final Set<Integer> FLOOR_TILES = Set.of(
            36149, 36046 // CG, G Safe Floor Tiles
    );

    // --- 12-Tile Safe Lane Shuttle System & Waypoint Navigation ---
    private static volatile int[] safeLaneTileA = null;
    private static volatile int[] safeLaneTileB = null;
    private static volatile int[] currentShuttleTarget = null;
    private static volatile List<int[]> currentLaneTiles = new ArrayList<>();
    private static volatile List<int[]> transitionWaypoints = new ArrayList<>();
    private static volatile int transitionWaypointIndex = 0;
    private static volatile boolean inTransition = false;
    private static volatile int[] transitionEntryTile = null; // lane tile the transition route ends on
    private volatile long lastDangerFloorFingerprint = -1L;
    private volatile int floorWarningStartTick = -1;

    public static int[] getSafeLaneTileA() {
        return safeLaneTileA;
    }

    public static int[] getSafeLaneTileB() {
        return safeLaneTileB;
    }

    public static int[] getCurrentShuttleTarget() {
        return currentShuttleTarget;
    }

    public static List<int[]> getActiveLaneTiles() {
        return currentLaneTiles;
    }

    public static List<int[]> getTransitionWaypoints() {
        return transitionWaypoints;
    }

    public static boolean isInTransition() {
        return inTransition;
    }

    private static final int[] BOW_IDS = {
            ItemID.GAUNTLET_RANGED_T3_HM, ItemID.GAUNTLET_RANGED_T3,
            ItemID.GAUNTLET_RANGED_T2_HM, ItemID.GAUNTLET_RANGED_T2,
            ItemID.GAUNTLET_RANGED_T1_HM, ItemID.GAUNTLET_RANGED_T1
    };

    private static final int[] STAFF_IDS = {
            ItemID.GAUNTLET_MAGIC_T3_HM, ItemID.GAUNTLET_MAGIC_T3,
            ItemID.GAUNTLET_MAGIC_T2_HM, ItemID.GAUNTLET_MAGIC_T2,
            ItemID.GAUNTLET_MAGIC_T1_HM, ItemID.GAUNTLET_MAGIC_T1
    };

    private static final int[] HALBERD_IDS = {
            ItemID.GAUNTLET_MELEE_T3_HM, ItemID.GAUNTLET_MELEE_T3,
            ItemID.GAUNTLET_MELEE_T2_HM, ItemID.GAUNTLET_MELEE_T2,
            ItemID.GAUNTLET_MELEE_T1_HM, ItemID.GAUNTLET_MELEE_T1
    };

    public static State ghState = State.IDLE;

    // Written from client-thread projectile/animation events, read by the script loop.
    private volatile Rs2PrayerEnum nextPrayer = Rs2PrayerEnum.PROTECT_RANGE;
    // Hunllef attack counter: it switches style after 4 attacks (tornado summons counted too), so protection switches
    // once the 4th attack has landed instead of reacting to the first attack of the new style.
    private static final int BOSS_ATTACKS_PER_STYLE = 4;
    private Rs2PrayerEnum bossStyle = Rs2PrayerEnum.PROTECT_RANGE;
    private int bossStyleAttacks = 0;
    private int bossSwitchAtCycle = -1;          // client cycle the 4th attack lands; switch protection after it
    private final Set<Projectile> countedBossProjectiles = Collections.newSetFromMap(new WeakHashMap<>());
    private int lastTornadoSpawnTick = -10;
    private String lastBossAttack = "none";      // style + tick of the last boss attack, for the hit log
    // Lines built on the client thread (logging there deadlocks) and logged by the script loop.
    private final java.util.concurrent.ConcurrentLinkedQueue<String> eventLog = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private HeadIcon bossHeadIcon = null;
    private Rs2NpcModel hunllef = null;
    // Every fight click goes through this one hand: one click at a time, human gaps, F5/ESC tab switches.
    private final ClickHand hand = new ClickHand(() -> this.gameTickCount,
            () -> this.tornadoThreat || (this.tornado != null && !this.tornado.isEmpty()));
    private List<Rs2NpcModel> tornado = null;
    private final AtomicBoolean attackNeeded = new AtomicBoolean(false);

    private int loopCount = 0;

    private boolean shutdownRequested = false;

    private long startTime = -1;
    private long now = -1;
    private long timeNpc = 150;
    private long timePrayOffence = -1;
    private long timePrayProtect = -1;
    private long timeEatAttempted = -1;
    private long timeDrink = -1;
    private long timeWeapon = -1;
    private long timeAttack = -1;
    private long timeSteel = -1;
    private long timeHunllefLog = -1;
    private long timeSafespot = -1;
    private long timeTornadoDodge = -1;
    private long magicBlastEnd = -1;
    private long timeCapture = -1;

    // Capture-mode de-dupe: last logged player tile and click destination (packed sceneX<<8|sceneY, -1 = none).
    private int lastCaptureP = -1;
    private int lastCaptureDest = Integer.MIN_VALUE;

    // Tornado dodge state.
    private boolean dodgeActive = false;
    private volatile boolean tornadoThreat = false;
    private int barriersTotal = -1; // barrier tiles matched anywhere in the scene (diagnostic)
    private int barriersNear = -1;  // barrier tiles within ARENA_BARRIER_RADIUS of the Hunllef (diagnostic)

    // Arena bounds in scene coords, derived once per fight from the 4 barriers. The floor tile id is
    // not unique to the arena, so these bounds are what actually keep retreats inside the room.
    private volatile boolean arenaBoundsKnown = false;
    private int arenaMinX, arenaMaxX, arenaMinY, arenaMaxY;

    private final AtomicBoolean tickHappened = new AtomicBoolean(false);
    private volatile int gameTickCount = 0;
    private int lastDodgeTick = -1;
    private int lastAttackTick = -5;
    private boolean fleePickedWithTornadoes = false;
    private int[] fleeTarget = null;      // committed "get off this hazard" tile; clicked once, kept until reached
    private long fleeClickAt = -1;
    private int fleePickTick = -1;
    private int routeBlockedLogTick = -1; // once-per-tick log guard for tornado-blocked routes
    private boolean loopMode = false;     // circling a fully safe ring around the Hunllef instead of lane shuttling
    private int loopDir = 1;              // +1 / -1 along the ring order
    private int loopRingSize = -1;        // ring distance from the boss centre while in loop mode
    private int lastEatTick = -1;         // tick of the last eat click and the HP then (double-eat guard)
    private int lastEatHp = 0;
    private boolean outOfFoodLogged = false;        // tick the current flee target was picked (one re-pick per tick)
    private int walkClickTick = -1, walkClickX = -1, walkClickY = -1; // last walk click (tick + target)
    private int walkSkipLogTick = -1;
    private int routeFromX = -1, routeFromY = -1, routeToX = -1, routeToY = -1; // last walk click: where it took effect, its tile
    private int offRouteTick = -1;              // last tick we re-clicked a walk that left its planned route
    private long lastWalkClickAt = -1;    // loop timestamp (now) of the last walk click
    private long lastEatClickAt = -1;     // loop timestamp (now) of the last eat/drink click
    private volatile long hitExpectedUntil = -1;  // a dodge move predicted an unavoidable hit: eat at the normal threshold until then
    private volatile long lastPrayerClickAt = -1; // diagnostics: wall-clock time of the last prayer click
    private int lastDiagTick = -1;
    private int runSpeed = 2;                     // tiles per tick this dodge pass: 2 running, 1 walking (run off / no energy)
    private volatile boolean movementCritical = false; // dodge is mid flee/evade or a tornado is within 3: don't eat
    private int[] plannedRunEnd = null;          // lane end an entry walk was simulated toward (taken on arrival)
    private boolean evadeCrossesHazard = false; // the committed evade is a break-out that runs over hazard tiles
    private boolean evadeExpectsHit = false;  // the committed evade was picked knowing it takes a hit
    private int evadePickTick = -1;
    private int[] evadeTarget = null;     // committed tornado-evasion tile (both lane directions were cut off)
    private int evadeExpectedHits = 0;   // tornado hits the current evade expects
    private int[] lastEvade = null;       // last evade target picked (kept through drops, for EVADE_STICK_TICKS)
    private int lastEvadeTick = -100;
    private int runAnchorX = -1, runAnchorY = -1; // where the current run started / we last attacked (scene coords)
    private volatile int lastAttackFiredTick = -100;     // tick our attack animation last started (weapon cooldown)
    private volatile int unknownPlayerAnim = -1;         // last unrecognised player animation, logged from the script thread
    private int loggedUnknownAnim = -1;
    private int lastEquipTick = -100;
    private int shotClickTick = -1, shotClickX = -1, shotClickY = -1; // last tornado-phase attack click (stop-length measurement)
    private boolean shotFiredLogged = true, shotMoveLogged = true;
    private int phaseShots = 0, shotSkipTick = -1;
    private int leaveEarlyLogTick = -1;
    private boolean tornadoesWereActive = false;
    private static final long LOOP_STALL_MS = 900;
    private static final int REPOSITION_RANGE = 4;
    // The Hunllef made 8-14 other attacks between tornado summons in our logs: only from the 7th on can the next
    // summon be close, so only then is it worth moving out of a pocket (moving earlier cost attack time).
    private static final int REPOSITION_AFTER_ATTACKS = 7;
    private volatile int bossAttacksSinceSummon = 0;
    private static final int ESCAPE_RUN_TILES = 3;
    private static final long REPOSITION_CHECK_MS = 1800;
    private int[] repositionTarget = null;
    private int[] repositionFailed = null;
    private long repositionFailedAt = 0;
    private static final long REPOSITION_FAILED_MS = 10000;
    private long repositionClickAt = 0, repositionCheckAt = 0;
    private long lastPassAt = 0;
    private volatile String loopSection = "start";
    // Set by the tornado spawn event; the next game tick runs the dodge right there on the client thread.
    private volatile boolean tornadoDodgeKick = false;
    // "Eat/drink only during tornadoes": eat up to the top-up HP (set when tornadoes spawn and when they despawn), and
    // restore prayer once per tornado phase.
    private boolean hpTopUpPending = false;
    private boolean prayerTopUpPending = false;
    private boolean outOfPotionsLogged = false;
    private final Map<String, Integer> shotSkipReasons = new HashMap<>();
    private int midRunAttackTick = -1;            // tick a mid-run attack was clicked; the run resumes the tick after
    private int lastWalkTargetX = -1;
    private int lastWalkTargetY = -1;

    /**
     * Coordinate-based arrival check: compares player's scene coordinates with desired tile,
     * confirming whether the player has physically reached the tile that was clicked.
     */
    private boolean hasArrived(int targetX, int targetY) {
        if (targetX < 0 || targetY < 0) return true;
        LocalPoint lp = playerTile(Microbot.getClient().getLocalPlayer());
        if (lp == null) return true;
        return lp.getSceneX() == targetX && lp.getSceneY() == targetY;
    }

    /**
     * The tile the player is really on (the server position), as a local point. getLocalLocation() is where the
     * player is drawn - up to a tick behind while running - so the dodge re-planned from a tile we'd already left and
     * re-clicked walks that had already finished. Falls back to the drawn position if the tile can't be mapped.
     */
    private static LocalPoint playerTile(Player p) {
        if (p == null) return null;
        // getWorldLocation() is client-thread only (the drawn position isn't): hop over when called from the script.
        if (!Microbot.getClient().isClientThread()) {
            return Microbot.getClientThread().runOnClientThreadOptional(() -> playerTile(p)).orElse(null);
        }
        WorldPoint wp = p.getWorldLocation();
        LocalPoint lp = wp == null ? null : LocalPoint.fromWorld(Microbot.getClient(), wp);
        return lp != null ? lp : p.getLocalLocation();
    }

    /** True if the player is currently en route to a target tile that was previously clicked. */
    private boolean isEnRouteTo(int targetX, int targetY) {
        if (lastWalkTargetX != targetX || lastWalkTargetY != targetY) return false;
        return !hasArrived(targetX, targetY);
    }

    /** True if the client's current registered walk destination matches the given scene tile. */
    private boolean isAlreadyWalkingTo(int targetSceneX, int targetSceneY) {
        LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
        if (dest == null) return false;
        return dest.getSceneX() == targetSceneX && dest.getSceneY() == targetSceneY;
    }

    private CustomGauntletConfig config;

    @Inject
    public CustomGauntletScript(CustomGauntletPlugin plugin, CustomGauntletConfig config) {
        this.config = config;
    }

    public boolean run() {

        hand.start();
        now = System.currentTimeMillis();
        if (config.autoPrep() || config.startState() == State.PREP) {
            ghState = State.PREP;
        } else {
            ghState = config.startState();
        }
        timeNpc = now;
        timePrayProtect = now;
        timeEatAttempted = now;
        timeDrink = now;
        timeWeapon = now;
        magicBlastEnd = now;
        sleep(300);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                if (shutdownRequested) return;

                now = System.currentTimeMillis();

                switch (ghState) {

                    case IDLE:
                        if (now - timeNpc > CD_NPC) {
                            checkForFightStart();
                        }
                        break;
                    case FIGHTING:
                        /// NOTE: Loop takes 47-60ms
                        // The loop runs every 200ms; a much longer gap means a pass got stuck (a tornado spawn once
                        // went unanswered for 2 ticks). Name the section the slow pass was in.
                        if (lastPassAt > 0 && now - lastPassAt > LOOP_STALL_MS) {
                            Microbot.log(String.format("[Loop] No pass for %dms (last section: %s)", now - lastPassAt, loopSection));
                        }
                        lastPassAt = now;
                        loopSection = "start";
                        if (!tickHappened.get()) break;
                        loopCount++;
                        if (loopCount > LOOPS_PER_TICK) {
                            loopCount = 0;
                            tickHappened.set(false);
                            checkProtectPrayers();
                            break;
                        }

                        loopSection = "checkNpc";
                        if (now - timeNpc > CD_NPC) {
                            checkNpc();
                        }

                        loopSection = "hunllef log";
                        if (now - timeHunllefLog > CD_HUNLLEF_LOG) {
                            logHunllefLocation();
                            logArenaInfo();
                        }

                        if (unknownPlayerAnim != loggedUnknownAnim) {
                            loggedUnknownAnim = unknownPlayerAnim;
                            Microbot.log(String.format("[Attack] Player animation %d is not counted as an attack", loggedUnknownAnim));
                        }

                        for (String line; (line = eventLog.poll()) != null; ) Microbot.log(line);

                        //Fast Section
                        loopSection = "prayers";
                        checkBossStyleSwitch();
                        checkProtectPrayers();
                        if (now - magicBlastEnd < BUFFER_MAGICBLAST)
                            checkProtectPrayers(); //turbo check after magic blast happens

                        boolean tornadoesActive = tornado != null && !tornado.isEmpty();
                        if (!tornadoesActive) {
                            // The NPC refresh runs every CD_NPC: without this the between-phase hazard flee kept
                            // acting for up to half a second after tornadoes spawned.
                            tornado = Microbot.getRs2NpcCache().query().where(npc -> TORNADO_IDS.contains(npc.getId())).within(50).toListOnClientThread();
                            tornadoesActive = !tornado.isEmpty();
                        }

                        // Maintain offensive prayers during combat
                        // Always on, tornadoes too: the toggle is a direct menu action (no mouse), so it costs no walk time.
                        if (now - timePrayOffence > CD_PRAYOFFENCE) checkAttackPrayers();

                        // Only simulate sleep on prayer changes when NOT kiting tornadoes!
                        // ...but never while standing on a hazard: prayer clicks every pass kept this break firing and the
                        // hazard flee below never ran (5 seconds on a hazard at the end of a fight).
                        if (!tornadoesActive && ((now - timePrayProtect < CD_RECENTLY) || (now - timePrayOffence < CD_RECENTLY) || (now - timeSteel < CD_RECENTLY))
                                && isPlayerOnSafeTile())
                            break;

                        //Slow Section

                        // Tornado phase over? Drop the kite state NOW.
                        if (!tornadoesActive && (tornadoThreat || dodgeActive)) {
                            logTornadoPhaseSummary();
                            clearDodge();
                            if (isPlayerOnSafeTile()) {
                                lastWalkTargetX = -1;
                                lastWalkTargetY = -1;
                                attackNeeded.set(true);
                                Microbot.log("[Action] Tornadoes despawned and player is on a safe tile - re-engaging Hunllef immediately!");
                            }
                        }

                        // Capture mode: during a tornado phase the bot stays hands-off so YOU dodge manually,
                        // and we log the geometry of each of your clicks for later analysis. The bot never
                        // walks/eats here (so it can't fight your clicks); prayers already ran above. Between
                        // tornado phases we fall through to normal weapon/attack so the fight still progresses.
                        if (config.captureManualDodge() && tornadoesActive) {
                            captureManualDodge();
                            break;
                        }

                        // Tornadoes just spawned: the escape walk goes first, so drop an attack still waiting on the hand
                        // (one being made aborts itself before its click, see attackHunllef).
                        if (tornadoesActive && !tornadoesWereActive) hand.cancel("attack");
                        if (tornadoesActive) repositionTarget = null;
                        if (tornadoesActive != tornadoesWereActive && config.eatDuringTornadoes()) {
                            hpTopUpPending = true;
                            prayerTopUpPending = tornadoesActive;
                        }
                        tornadoesWereActive = tornadoesActive;

                        // Tornado dodging has TOP priority: keep moving, and eat/drink on the run.
                        loopSection = "dodge";
                        boolean dodging = config.dodgeTornadoes() && tornadoesActive && handleTornadoDodge();

                        // The click hand lands eat/drink after the dodge walk (its priority order), so they no longer
                        // wait for a pass without a walk click - and can't replace the walk any more.
                        loopSection = "food/drink";
                        if (now - timeEatAttempted > CD_EAT) checkFood();
                        if (now - timeDrink > CD_DRINK) checkPrayerPotions(); //Non-Blocking

                        if (dodging || tornadoesActive || tornadoThreat) break; // NEVER attack during tornadoes!

                        // When no tornado is up the dodge is dormant, so this is our only line of defence
                        // against a damage field. handleSafespot ALWAYS flees a damaging tile (standing in
                        // fire is fatal regardless of the runToSafespot opt-out); it only does optional
                        // repositioning when runToSafespot is enabled.
                        loopSection = "safespot";
                        if ((tornado == null || tornado.isEmpty()) && handleSafespot())
                            break; //running to safety takes priority over gear/attack this loop

                        loopSection = "weapon/attack";
                        if (now - timeWeapon > CD_WEAPON) checkWeapon();
                        checkAttack();
                        break;

                } // ----- End of States -----

            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
        return true;
    } //End of Run


    //------------------------------------------------------------------------------------------------------------------
    // --- NPC Checks

    /**
     * While idle, watch for the Hunllef engaging us. The boss NPC only exists in its
     * arena, so finding one means we are in the room; requiring that it is interacting
     * with the local player confirms the fight has actually started. Once both hold we
     * flip to FIGHTING so the script self-starts each fight without a manual state change.
     */
    public void checkForFightStart() {
        timeNpc = now;
        Rs2NpcModel boss = Microbot.getRs2NpcCache().query().where(npc -> HUNLLEF_IDS.contains(npc.getId())).toListOnClientThread().stream()
                .findFirst()
                .orElse(null);
        if (boss == null) return;
        if (!boss.isInteractingWithPlayer()) return;
        if (tileDistanceToPlayer(boss) > HUNLLEF_PRESENCE_RANGE) return; // stale/other-instance reference, not a real engagement

        hunllef = boss;
        bossHeadIcon = boss.getHeadIcon();
        arenaBoundsKnown = false; // new fight = new instance; re-derive the arena from its barriers
        // The Hunllef always opens with ranged. nextPrayer is stale here (null after the last fight, or
        // MAGIC from a prep miniboss's magic projectile), which would flip a pre-set Protect from Missiles
        // to Protect from Magic for the opening ranged attacks.
        nextPrayer = Rs2PrayerEnum.PROTECT_RANGE;
        ghState = State.FIGHTING;
        logVerbose("Hunllef engaged - switching to FIGHTING");
    }

    /**
     * Periodic debug log of the Hunllef's position so it can be verified in-game.
     * Logs both world and local coordinates because the Gauntlet is instanced and the
     * two spaces differ (see docs/INSTANCE_COORDINATE_SPACES.md); the player's own
     * location is included for comparison.
     */
    private void logHunllefLocation() {
        timeHunllefLog = now;
        if (hunllef == null) {
            Microbot.log("Hunllef not found");
            return;
        }
        // tileDistanceToPlayer() uses scene coords (instance-safe, real tiles). NOTE: the NPC model's
        // getDistanceFromPlayer() returns LOCAL units (128 per tile), not tiles - don't use it here.
        Microbot.log("Hunllef " + tileDistanceToPlayer(hunllef) + " tiles to center"
                + " | under=" + isPlayerUnderHunllef(0)
                + " | underOrAdjacent=" + isPlayerUnderHunllef(1));
    }

    /**
     * Periodic debug log of the derived arena bounds (scene coords) and whether the player is inside
     * them, so the barrier-based detection can be validated in-game. Computed on the client thread;
     * the actual logging happens on this thread to avoid the GameChatAppender client-thread deadlock.
     */
    private void logArenaInfo() {
        String msg = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            computeArenaBounds();
            String barriers = " barriers near/total=" + barriersNear + "/" + barriersTotal;
            if (!arenaBoundsKnown) {
                return "Arena bounds unknown (need 4 barriers near the Hunllef)" + barriers;
            }
            String bounds = "Arena scene x[" + arenaMinX + "," + arenaMaxX + "] y[" + arenaMinY + "," + arenaMaxY + "]" + barriers;
            Player local = Microbot.getClient().getLocalPlayer();
            if (local == null || playerTile(local) == null) {
                return bounds + " | player loc unknown";
            }
            int px = playerTile(local).getSceneX();
            int py = playerTile(local).getSceneY();
            return bounds + " | player scene=" + px + "," + py + " | inArena=" + inArena(px, py);
        }).orElse("Arena info unavailable");
        Microbot.log(msg);
    }

    /**
     * Whether the player is standing on the Hunllef's 5x5 footprint (its stomp area), optionally
     * expanded by {@code margin} tiles. Works entirely in scene/LocalPoint space (NPC centre vs
     * player, both via getLocalLocation) so it stays consistent inside the instance - comparing
     * WorldPoints here breaks because the NPC and player resolve to different spaces
     * (see docs/INSTANCE_COORDINATE_SPACES.md).
     */
    private boolean isPlayerUnderHunllef(int margin) {
        if (hunllef == null) return false;
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player local = Microbot.getClient().getLocalPlayer();
            LocalPoint bossLp = hunllef.getLocalLocation();
            if (local == null || bossLp == null) return false;
            LocalPoint meLp = playerTile(local);
            if (meLp == null) return false;
            return isUnderBoss(meLp.getSceneX(), meLp.getSceneY(),
                    bossLp.getSceneX(), bossLp.getSceneY(), Math.max(0, margin));
        }).orElse(false);
    }

    /**
     * Chebyshev tile distance from the player to the given NPC, in scene/LocalPoint space so it is
     * correct inside the instance. NOTE: Rs2NpcModel.getDistanceFromPlayer() and LocalPoint.distanceTo()
     * return LOCAL units (128 per tile), and MAX_VALUE across world views - never use those for a tile
     * threshold.
     *
     * @return distance in tiles, or Integer.MAX_VALUE if it can't be determined
     */
    private int tileDistanceToPlayer(Rs2NpcModel npc) {
        if (npc == null) return Integer.MAX_VALUE;
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player local = Microbot.getClient().getLocalPlayer();
            LocalPoint npcLp = npc.getLocalLocation();
            if (local == null || npcLp == null) return Integer.MAX_VALUE;
            LocalPoint meLp = playerTile(local);
            if (meLp == null) return Integer.MAX_VALUE;
            return Math.max(Math.abs(meLp.getSceneX() - npcLp.getSceneX()),
                    Math.abs(meLp.getSceneY() - npcLp.getSceneY()));
        }).orElse(Integer.MAX_VALUE);
    }

    /**
     * A scene tile is "under" the boss when it falls within its 5x5 footprint, optionally expanded by {@code margin}.
     * Computed purely in scene coordinates from Hunllef's local center point (100% instance-safe).
     */
    private boolean isUnderBoss(int sceneX, int sceneY, int bossSceneX, int bossSceneY, int margin) {
        int reach = HUNLLEF_RADIUS + margin;
        return Math.abs(sceneX - bossSceneX) <= reach && Math.abs(sceneY - bossSceneY) <= reach;
    }

    /**
     * Runs to the nearest safe tile - a floor tile (ground object {@link #SAFE_TILE_GROUND_ID}) that is
     * not under the Hunllef. Only acts while stationary (so it never fights an in-progress or manual
     * walk) and throttled by {@link #CD_SAFESPOT}. Everything is computed in scene/LocalPoint space and
     * the walk is issued via walkFastLocal, which sidesteps the instance WorldPoint pitfalls.
     *
     * @return true if a run-to-safety was issued (caller should skip gear/attack this loop)
     */
    private boolean handleSafespot() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player local = Microbot.getClient().getLocalPlayer();
            if (local == null || hunllef == null) {
                return false;
            }
            LocalPoint playerLp = playerTile(local);
            LocalPoint bossLp = hunllef.getLocalLocation();
            if (playerLp == null || bossLp == null) {
                return false;
            }

            computeArenaBounds();
            if (!arenaBoundsKnown) {
                return false; // can't guarantee staying in the room without the barriers; do nothing
            }

            int bossX = bossLp.getSceneX(), bossY = bossLp.getSceneY();
            int playerX = playerLp.getSceneX(), playerY = playerLp.getSceneY();

            // Standing in a damage field is always fatal, so flee it immediately regardless of movement or config.
            boolean onDanger = isOnDangerousTile(playerX, playerY);
            boolean safeHere = inArena(playerX, playerY) && isSafeFloor(playerX, playerY) && !onDanger
                    && !isUnderBoss(playerX, playerY, bossX, bossY, 0);
            if (!safeHere) repositionTarget = null;
            if (!onDanger && !config.runToSafespot()) {
                return safeHere && config.dodgeTornadoes() && repositionOutOfPocket(playerX, playerY, bossX, bossY);
            }

            // already safe: inside the arena, on a safe floor tile, not on danger, and clear of boss footprint
            if (safeHere) {
                fleeTarget = null; // safe again: the next hazard picks a fresh nearest tile
                return config.dodgeTornadoes() && repositionOutOfPocket(playerX, playerY, bossX, bossY);
            }

            if (!onDanger && now - timeSafespot < CD_SAFESPOT) {
                return false;
            }

            boolean fled = fleeToNearestSafeTile(playerX, playerY, bossX, bossY, tornadoSceneCoords());
            if (fled) timeSafespot = now;
            return fled;
        }).orElse(false);
    }

    /**
     * Between tornado phases, don't attack from a pocket: a tile without room to run both ways along some line (a lane
     * end or arena corner). Tornadoes spawn next to us; in a corner one spawning diagonally cut off both directions
     * and hit twice. Moves once to the nearest tile within REPOSITION_RANGE with a two-way escape, then re-attacks.
     * Client thread.
     *
     * @return true while walking there (the attack waits)
     */
    private boolean repositionOutOfPocket(int px, int py, int bossX, int bossY) {
        if (repositionTarget != null) {
            int[] t = repositionTarget;
            boolean arrived = px == t[0] && py == t[1];
            if (arrived || !isTileFullySafe(t[0], t[1], bossX, bossY)) {
                repositionTarget = null;
                if (arrived) attackNeeded.set(true);
                else {
                    // It burned before we got there: don't pick it again for a while.
                    repositionFailed = t;
                    repositionFailedAt = now;
                }
                return false;
            }
            if (!isAlreadyWalkingTo(t[0], t[1]) && now - repositionClickAt > 1200) {
                repositionClickAt = now;
                walkExactTile(t[0], t[1]);
            }
            return true;
        }
        if (bossAttacksSinceSummon < REPOSITION_AFTER_ATTACKS) return false; // no tornadoes due yet: keep attacking
        if (now - repositionCheckAt < REPOSITION_CHECK_MS) return false;
        repositionCheckAt = now;
        if (hasTwoWayEscape(px, py, bossX, bossY)) return false;
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        boolean bestNearBoss = true, bestEdge = true;
        boolean failedRecently = repositionFailed != null && now - repositionFailedAt < REPOSITION_FAILED_MS;
        for (int x = Math.max(arenaMinX, px - REPOSITION_RANGE); x <= Math.min(arenaMaxX, px + REPOSITION_RANGE); x++) {
            for (int y = Math.max(arenaMinY, py - REPOSITION_RANGE); y <= Math.min(arenaMaxY, py + REPOSITION_RANGE); y++) {
                if (x == px && y == py) continue;
                if (failedRecently && x == repositionFailed[0] && y == repositionFailed[1]) continue;
                if (!isTileFullySafe(x, y, bossX, bossY)) continue;
                if (!isStraightPathFullySafe(px, py, x, y, bossX, bossY)) continue;
                if (!hasTwoWayEscape(x, y, bossX, bossY)) continue;
                // A tile at the edge of the safe area burns first when the floor changes: (73,58) burned twice and
                // the flee sent us straight back into the pocket.
                boolean edge = onSafeAreaEdge(x, y);
                boolean nearBoss = isUnderBoss(x, y, bossX, bossY, 1);
                int dist = Math.max(Math.abs(x - px), Math.abs(y - py));
                if (best == null || (edge != bestEdge ? !edge : nearBoss != bestNearBoss ? !nearBoss : dist < bestDist)) {
                    best = new int[]{x, y};
                    bestDist = dist;
                    bestNearBoss = nearBoss;
                    bestEdge = edge;
                }
            }
        }
        if (best == null) return false;
        Microbot.log(String.format("[Position] (%d,%d) is a pocket (no room to run both ways) and tornadoes may come (%d attacks since the last) - moving to (%d,%d)",
                px, py, bossAttacksSinceSummon, best[0], best[1]));
        repositionTarget = best;
        repositionClickAt = now;
        lastWalkTargetX = best[0];
        lastWalkTargetY = best[1];
        walkExactTile(best[0], best[1]);
        return true;
    }

    /** True if, along some line (straight or diagonal), both directions have ESCAPE_RUN_TILES safe tiles in a row. */
    private boolean hasTwoWayEscape(int x, int y, int bossX, int bossY) {
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            if (safeRun(x, y, d[0], d[1], bossX, bossY) >= ESCAPE_RUN_TILES
                    && safeRun(x, y, -d[0], -d[1], bossX, bossY) >= ESCAPE_RUN_TILES) return true;
        }
        return false;
    }

    private int safeRun(int x, int y, int dx, int dy, int bossX, int bossY) {
        int n = 0;
        while (n < ESCAPE_RUN_TILES && isTileFullySafe(x + (n + 1) * dx, y + (n + 1) * dy, bossX, bossY)) n++;
        return n;
    }

    // ------------------------------------------------------------------
    // --- Tornado dodging
    // ------------------------------------------------------------------

    /**
     * Capture mode (observe-only). Logs a parseable [Capture] line while the HUMAN dodges manually, so the
     * demonstrated path can be analysed against the live tile layout. One line per decision: it samples at
     * {@link #CD_CAPTURE} but de-dupes, emitting only when the player tile or the click target changes.
     * All scene lookups run on the client thread; the log itself fires on this (script) thread to avoid the
     * GameChatAppender client-thread deadlock. Fields logged (all scene coords):
     *   p      = where you stood,  click = your walk destination (the red-X / clicked tile, "none" if idle),
     *   boss   = Hunllef centre (5x5 footprint),  torn = every tornado,  danger = all damaging tiles in the
     *   arena, arena = [minX,maxX,minY,maxY].
     */
    private void captureManualDodge() {
        if (now - timeCapture < CD_CAPTURE) return;
        timeCapture = now;
        String line = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player local = Microbot.getClient().getLocalPlayer();
            if (local == null || hunllef == null) return null;
            LocalPoint plp = playerTile(local);
            LocalPoint blp = hunllef.getLocalLocation();
            if (plp == null || blp == null) return null;
            computeArenaBounds();
            if (!arenaBoundsKnown) return null;

            int px = plp.getSceneX(), py = plp.getSceneY();
            LocalPoint dlp = Microbot.getClient().getLocalDestinationLocation();
            int dx = dlp == null ? -1 : dlp.getSceneX();
            int dy = dlp == null ? -1 : dlp.getSceneY();

            // De-dupe: skip if neither our tile nor the click target changed since the last emitted line.
            int pPack = (px << 8) | py;
            int dPack = dlp == null ? -1 : ((dx << 8) | dy);
            if (pPack == lastCaptureP && dPack == lastCaptureDest) return null;
            lastCaptureP = pPack;
            lastCaptureDest = dPack;

            int bx = blp.getSceneX(), by = blp.getSceneY();
            List<int[]> torn = tornadoSceneCoords();
            StringBuilder danger = new StringBuilder("[");
            for (int x = arenaMinX; x <= arenaMaxX; x++) {
                for (int y = arenaMinY; y <= arenaMaxY; y++) {
                    if (isOnDangerousTile(x, y)) danger.append("(").append(x).append(",").append(y).append(")");
                }
            }
            danger.append("]");

            String click = dlp == null ? "none" : "(" + dx + "," + dy + ")";
            return "[Capture] p=(" + px + "," + py + ") click=" + click + " boss=(" + bx + "," + by + ")"
                    + " torn=" + formatTornadoes(torn) + " danger=" + danger
                    + " arena=[" + arenaMinX + "," + arenaMaxX + "," + arenaMinY + "," + arenaMaxY + "]";
        }).orElse(null);
        if (line != null) Microbot.log(line); // always log (not gated on verbose) so captures are easy to collect
    }

    /**
     * While tornadoes are active, kite to the reachable safe tile that is furthest from every tornado.
     * A flood-fill from the player over safe tiles (arena floor, not a damaging tile, not under the
     * Hunllef) yields the tiles reachable WITHOUT crossing anything unsafe; among those we pick the one
     * maximising the distance to the nearest tornado, then step along that safe path (as far as a
     * straight safe line reaches, so we actually run) and re-evaluate every loop so the kite tracks the
     * tornadoes as they chase. Light hysteresis keeps the current target unless a tornado has closed back
     * in, the target became unreachable, or a clearly-better tile opened up. Must run on the client thread.
     *
     * @return true if a dodge move was issued this loop (caller skips gear/attack)
     */
    /**
     * 12-Tile Safe Lane Shuttle System & Waypoint Navigation:
     * - Discovers a long safe lane (straight horizontal or vertical line) in the arena clear of Hunllef and hazards.
     * - Transition (Point 2 & 3): Enters the lane at the closest reachable tile matching player's X or Y coordinate,
     *   navigating via corner-to-corner cardinal waypoints to prevent game engine diagonal cutting through hazards.
     * - Shuttles back and forth along the safe lane, standing still to attack Hunllef when tornado gap > 3.
     * - Turnaround (Point 4): When gap <= 3 at an endpoint, avoids 180° head-on collisions into trailing tornadoes
     *   by turning 90° into a connected perpendicular lane (L-turn) or side-stepping to an adjacent parallel safe lane.
     * - Floor Stability: Recalculates the route only once when dangerous floor patterns change.
     *
     * @return true if a dodge move was issued this loop (caller skips gear/attack)
     */
    private boolean handleTornadoDodge() {
        int tick = gameTickCount;
        lastDodgeTick = tick;
        timeTornadoDodge = now;

        boolean result = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player local = Microbot.getClient().getLocalPlayer();
            if (local == null || hunllef == null) { clearDodge(); return false; }
            LocalPoint playerLp = playerTile(local);
            LocalPoint bossLp = hunllef.getLocalLocation();
            if (playerLp == null || bossLp == null) { clearDodge(); return false; }

            computeArenaBounds();
            if (!arenaBoundsKnown) { tornadoThreat = false; return false; }

            List<int[]> tornadoes = tornadoSceneCoords();
            if (tornadoes.isEmpty()) { clearDodge(); return false; }

            int px = playerLp.getSceneX(), py = playerLp.getSceneY();
            int bossX = bossLp.getSceneX(), bossY = bossLp.getSceneY();

            tornadoThreat = true;
            dodgeActive = true;

            if (!Rs2Player.isRunEnabled()) {
                Rs2Player.toggleRunEnergy(true);
            }

            // Every tornado prediction assumes our speed; when run is off or energy is empty we cover 1 tile a tick.
            runSpeed = Rs2Player.isRunEnabled() && Microbot.getClient().getEnergy() > 0 ? 2 : 1;

            int nearestNow = minTornadoDistance(px, py, tornadoes);
            if (isOnDangerousTile(px, py) && nearestNow <= 2) hitExpectedUntil = now + 600;
            if (tick != lastDiagTick) {
                lastDiagTick = tick;
                LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
                long wall = System.currentTimeMillis();
                Microbot.log(String.format("[Dodge] tick %d pos=(%d,%d) dest=%s tornado=%d torn=%s hazard=%b %s | walk %dms, eat %dms, prayer %dms ago",
                        tick, px, py, dest == null ? "none" : "(" + dest.getSceneX() + "," + dest.getSceneY() + ")",
                        nearestNow, formatTornadoes(tornadoes), isOnDangerousTile(px, py), vitalsTag(),
                        lastWalkClickAt < 0 ? -1 : now - lastWalkClickAt, lastEatClickAt < 0 ? -1 : now - lastEatClickAt,
                        lastPrayerClickAt < 0 ? -1 : wall - lastPrayerClickAt));
            }

            movementCritical = nearestNow <= 3 || evadeTarget != null
                    || isOnDangerousTile(px, py);

            // The game walked a different route than the one we checked, and it's taking us beside the Hunllef:
            // re-click the walk from where we actually are (short steps near the boss) before we're under it.
            if (offRouteTick != tick && routeFromX >= 0 && gameTickCount - walkClickTick >= 2
                    && isAlreadyWalkingTo(routeToX, routeToY) && isUnderBoss(px, py, bossX, bossY, 1)
                    && !isUnderBoss(px, py, bossX, bossY, 0) && !isOnDangerousTile(px, py)
                    && !onWalkRoute(routeFromX, routeFromY, routeToX, routeToY, px, py)) {
                offRouteTick = tick;
                Microbot.log(String.format("[Walk] Off the planned route at (%d,%d) beside the Hunllef (walking (%d,%d)->(%d,%d)) - re-clicking from here %s",
                        px, py, routeFromX, routeFromY, routeToX, routeToY, vitalsTag()));
                int tx = routeToX, ty = routeToY;
                walkClickTick = -1; // a fresh route, not a second destination this tick
                walkExactTile(tx, ty);
                return true;
            }

            // Standing on a hazard (or under the boss) trumps the lane: get off it in one committed click.
            if (isOnDangerousTile(px, py) || isUnderBoss(px, py, bossX, bossY, 0)) {
                // The evade's target is past the floor that just lit up: once off it, the evade walked us back
                // across the same hazards into a tornado (24 damage). Pick afresh from wherever the flee lands.
                // A break-out crosses hazards on purpose: keep running it while its target is still safe.
                if (evadeTarget != null && evadeCrossesHazard && (px != evadeTarget[0] || py != evadeTarget[1])
                        && isTileFullySafe(evadeTarget[0], evadeTarget[1], bossX, bossY)) {
                    if (!isAlreadyWalkingTo(evadeTarget[0], evadeTarget[1])) walkExactTile(evadeTarget[0], evadeTarget[1]);
                    return true;
                }
                evadeTarget = null;
                if (fleeToNearestSafeTile(px, py, bossX, bossY, tornadoes)) return true;
            } else {
                fleeTarget = null;
            }

            logShotTiming(px, py);

            // An attack clicked from a standstill or mid-run: hold still until it fires (up to the pause).
            if (holdingForAttack() && nearestNow > 2) return true;

            // Fit a shot in whenever the run we're on survives standing still for it (lane run, evade, standstill).
            if (!loopMode && tryTornadoAttack(px, py, bossX, bossY, tornadoes, nearestNow)) return true;

            // Late in the fight the safe floor shrinks to a ring around the Hunllef. Lanes along it are short dead
            // ends (a corner stop cost 35-38 HP a tick), but the ring is a loop: run around it instead.
            if (loopRun(px, py, bossX, bossY, tornadoes)) return true;

            // Committed tornado evasion (both lane directions were cut off): finish it before picking a new lane,
            // otherwise the lane logic would immediately route us back toward the tornadoes.
            if (evadeTarget != null) {
                boolean arrived = px == evadeTarget[0] && py == evadeTarget[1];
                if (arrived || !isTileFullySafe(evadeTarget[0], evadeTarget[1], bossX, bossY)
                        || (!evadeCrossesHazard && !isStraightPathFullySafe(px, py, evadeTarget[0], evadeTarget[1], bossX, bossY))
                        // A hit-taking evade ends beside a tornado by design: dropping it for that re-picked it twice
                        // a tick and we stood still 3 ticks while the tornado closed in. Only clean evades re-plan.
                        || (!evadeExpectsHit && minTornadoDistance(evadeTarget[0], evadeTarget[1], tornadoes) <= 1)) {
                    evadeTarget = null;
                    if (arrived && tryStandingAttack(px, py, tornadoes, "end of evade")) return true;
                } else {
                    if (!isAlreadyWalkingTo(evadeTarget[0], evadeTarget[1])) walkExactTile(evadeTarget[0], evadeTarget[1]);
                    return true;
                }
            }

            // Recalculate route only when dangerous floors change or lane is invalid
            long dangerFp = computeDangerFloorFingerprint();
            boolean floorsChanged = (dangerFp != lastDangerFloorFingerprint);
            boolean laneInvalid = (safeLaneTileA == null || safeLaneTileB == null
                    || !isSegmentFullySafe(safeLaneTileA, safeLaneTileB, bossX, bossY));

            if (floorsChanged || laneInvalid) {
                lastDangerFloorFingerprint = dangerFp;
                recalculateSafeLaneAndRoute(px, py, bossX, bossY, tornadoes);
            }

            if (safeLaneTileA == null || safeLaneTileB == null) {
                // No lane reachable over safe tiles (e.g. boxed into a corner when the outer rings light up).
                return escapeAcrossDanger(px, py, bossX, bossY, tornadoes);
            }

            int nearest = minTornadoDistance(px, py, tornadoes);

            // Off the lane: follow the safe route to it leg by leg (re-validated every loop as floors change).
            // Only once we stand on the lane does the endpoint shuttle below take over - previously the first
            // corner waypoint was treated as a lane endpoint, so reaching it sprinted straight at the lane end.
            if (inTransition) {
                if (!isPlayerOnSafeLane(px, py) && transitionEntryTile != null) {
                    return enterLaneAndRun(px, py, bossX, bossY, tornadoes)
                            || walkSafeRoute(px, py, transitionEntryTile[0], transitionEntryTile[1], bossX, bossY, tornadoes)
                            || evadeTornadoes(px, py, bossX, bossY, tornadoes)
                            || escapeAcrossDanger(px, py, bossX, bossY, tornadoes);
                }
                inTransition = false;
                transitionEntryTile = null;
                transitionWaypoints = new ArrayList<>();
                // The end the entry was planned toward, if the run there is still clean from here.
                currentShuttleTarget = plannedRunEnd != null && simulateRun(px, py, plannedRunEnd, tornadoes)[0] >= 1
                        ? plannedRunEnd : pickRunEnd(px, py, tornadoes);
                plannedRunEnd = null;
                if (currentShuttleTarget == null) {
                    if (evadeTornadoes(px, py, bossX, bossY, tornadoes)) return true;
                    currentShuttleTarget = pickLessBadRunEnd(px, py, tornadoes);
                }
                setRunAnchor(px, py);
                Microbot.log(String.format("[Action] Reached safe lane at (%d,%d) - shuttling toward (%d,%d)",
                        px, py, currentShuttleTarget[0], currentShuttleTarget[1]));
                // Arrived on a lane end with the tornadoes still well back: take the shot before the run.
                boolean onEnd = (px == safeLaneTileA[0] && py == safeLaneTileA[1]) || (px == safeLaneTileB[0] && py == safeLaneTileB[1]);
                if (onEnd && tryStandingAttack(px, py, tornadoes, "lane reached")) return true;
            }

            // Determine initial sprint target endpoint (furthest from tornadoes) if not set
            if (currentShuttleTarget == null) {
                currentShuttleTarget = pickRunEnd(px, py, tornadoes);
                if (currentShuttleTarget == null) {
                    if (evadeTornadoes(px, py, bossX, bossY, tornadoes)) return true;
                    currentShuttleTarget = pickLessBadRunEnd(px, py, tornadoes);
                }
            }

            int distToTarget = Math.max(Math.abs(currentShuttleTarget[0] - px), Math.abs(currentShuttleTarget[1] - py));
            // Within 1 tile only counts as arrived if the game isn't walking us somewhere else: after a mid-run
            // attack the game once pathed us toward the Hunllef from 1 tile short of the end, and the dodge sat
            // "at the endpoint" for 4 seconds while we walked onto a hazard into a tornado.
            LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
            boolean walkingElsewhere = dest != null
                    && (dest.getSceneX() != currentShuttleTarget[0] || dest.getSceneY() != currentShuttleTarget[1]);
            boolean atTarget = hasArrived(currentShuttleTarget[0], currentShuttleTarget[1])
                    || (distToTarget <= 1 && !walkingElsewhere);

            int[] runBack = (currentShuttleTarget[0] == safeLaneTileA[0] && currentShuttleTarget[1] == safeLaneTileA[1])
                    ? safeLaneTileB : safeLaneTileA;
            if (atTarget) {
                // Wait at the end only while the run back would still be clean after one more tick of waiting (clicks
                // now take ~a tick to land). "Leave at gap 3" stood 8 ticks in a corner while the tornadoes came in on
                // a diagonal and cut the run back off; we left at gap 2 and took two hits.
                boolean canWait = nearest > 3 && simulateRun(px, py, runBack, tornadoesAfter(px, py, tornadoes, 1))[0] >= 2;
                if (nearest > 3 && !canWait && leaveEarlyLogTick != gameTickCount) {
                    leaveEarlyLogTick = gameTickCount;
                    Microbot.log(String.format("[Action] Leaving the lane end at tornado gap %d - one more tick would cut off the run back %s",
                            nearest, vitalsTag()));
                }
                if (canWait) {
                    // Standing clear at endpoint: Attack boss & pre-hover next move
                    boolean canAttackBoss = config.autoAttack() && hunllef != null
                            && (isBowEquipped() || isStaffEquipped() || (isHalberdEquipped() && Math.max(Math.abs(px - bossX), Math.abs(py - bossY)) <= 2));
                    // Click only once the weapon is off cooldown: a click before that waits for the cooldown, and the
                    // next run away cancelled it before it fired.
                    // ...and a shot stands us still longer: the run back must survive the whole pause.
                    if (weaponReady() && canAttackBoss && !isAttackingBoss() && !isWarningTile(px, py)
                            && simulateRun(px, py, runBack, tornadoesAfterPause(px, py, tornadoes))[0] >= 2) {
                        Microbot.log(String.format("[Action] Standing clear at endpoint (gap %d > 3) - attacking Hunllef", nearest));
                        attackHunllef();
                        timeAttack = now;
                        lastAttackTick = gameTickCount;
                        setRunAnchor(px, py);
                    }

                    int[] opposite = (currentShuttleTarget[0] == safeLaneTileA[0] && currentShuttleTarget[1] == safeLaneTileA[1])
                            ? safeLaneTileB : safeLaneTileA;
                    hoverSceneTile(opposite[0], opposite[1]);
                    return false;
                } else {
                    // Tornadoes reached gap <= 3. They chase us along this lane, so the opposite endpoint is
                    // back through them: turn onto a fresh perpendicular lane out of this endpoint if there is one.
                    // "At target" allows one tile short, but the turn-off is the endpoint itself (the tile before it
                    // can be boxed in by the boss), so step onto it first while no tornado can get there before us.
                    int[] end = currentShuttleTarget;
                    boolean laneHorizontal = safeLaneTileA[1] == safeLaneTileB[1];
                    if ((px != end[0] || py != end[1]) && minTornadoDistance(end[0], end[1], tornadoes) >= 2
                            && isTileFullySafe(end[0], end[1], bossX, bossY)
                            && isStraightPathFullySafe(px, py, end[0], end[1], bossX, bossY)
                            && hasPerpendicularExit(end[0], end[1], laneHorizontal, findAllSafeSegments(bossX, bossY))) {
                        if (!isAlreadyWalkingTo(end[0], end[1])) {
                            lastWalkTargetX = end[0];
                            lastWalkTargetY = end[1];
                            walkExactTile(end[0], end[1]);
                        }
                        return true;
                    }
                    if (switchToPerpendicularLane(px, py, bossX, bossY, tornadoes)) {
                        setRunAnchor(px, py);
                        lastWalkTargetX = currentShuttleTarget[0];
                        lastWalkTargetY = currentShuttleTarget[1];
                        walkExactTile(currentShuttleTarget[0], currentShuttleTarget[1]);
                        return true;
                    }
                    int[] opposite = (currentShuttleTarget[0] == safeLaneTileA[0] && currentShuttleTarget[1] == safeLaneTileA[1])
                            ? safeLaneTileB : safeLaneTileA;
                    // The opposite end is back past a tornado: run to the safe tile farthest from all tornadoes instead.
                    if (!outrunsTornadoes(px, py, opposite, tornadoes) && evadeTornadoes(px, py, bossX, bossY, tornadoes)) {
                        return true;
                    }
                    // No usable new lane: sprint to opposite endpoint on the fixed safe lane!
                    currentShuttleTarget = opposite;
                    setRunAnchor(px, py);
                    lastWalkTargetX = currentShuttleTarget[0];
                    lastWalkTargetY = currentShuttleTarget[1];
                    Microbot.log(String.format("[Action] Tornado gap %d <= 3 - sprinting to opposite endpoint (%d,%d)",
                            nearest, currentShuttleTarget[0], currentShuttleTarget[1]));
                    walkExactTile(currentShuttleTarget[0], currentShuttleTarget[1]);
                    return true;
                }
            } else {
                // Mid-run attack: the attack click stops the run, so hold for the tick it was clicked (a walk click
                // in the same tick would replace it) and pick the run back up on the next one.
                // ...but if the attack makes the game walk us anywhere but the lane end (toward the boss), take
                // the run back at once instead.
                if ((midRunAttackTick == gameTickCount || holdingForAttack()) && !walkingElsewhere && nearest > 2) return true;
                if (tryMidRunAttack(px, py, bossX, bossY, nearest, distToTarget, tornadoes)) return true;
                if (walkSafeRoute(px, py, currentShuttleTarget[0], currentShuttleTarget[1], bossX, bossY, tornadoes)) {
                    return true;
                }
                // No safe route to the endpoint: snap back onto the lane, but only along a safe, tornado-free leg.
                // On the lane with the way to this end cut off by a tornado: turn around or take a cross lane now,
                // while the tornado is still a few tiles off. (The snap below used to pick the tile we stood on,
                // and we stood still 4 ticks while a tornado closed from 4 to 1.)
                if (isPlayerOnSafeLane(px, py)) {
                    int[] opposite = (currentShuttleTarget[0] == safeLaneTileA[0] && currentShuttleTarget[1] == safeLaneTileA[1])
                            ? safeLaneTileB : safeLaneTileA;
                    if ((opposite[0] != px || opposite[1] != py)
                            && walkSafeRoute(px, py, opposite[0], opposite[1], bossX, bossY, tornadoes)) {
                        Microbot.log(String.format("[Action] Way to (%d,%d) cut off - turning to the other end (%d,%d) %s",
                                currentShuttleTarget[0], currentShuttleTarget[1], opposite[0], opposite[1], vitalsTag()));
                        currentShuttleTarget = opposite;
                        setRunAnchor(px, py);
                        lastWalkTargetX = opposite[0];
                        lastWalkTargetY = opposite[1];
                        return true;
                    }
                    if (switchToPerpendicularLane(px, py, bossX, bossY, tornadoes)) {
                        setRunAnchor(px, py);
                        lastWalkTargetX = currentShuttleTarget[0];
                        lastWalkTargetY = currentShuttleTarget[1];
                        walkExactTile(currentShuttleTarget[0], currentShuttleTarget[1]);
                        return true;
                    }
                }
                int[] snap = findClosestSafeLaneTile(px, py, bossX, bossY);
                if (snap != null && (snap[0] != px || snap[1] != py)
                        && isStraightPathFullySafe(px, py, snap[0], snap[1], bossX, bossY)
                        && (tornadoes.isEmpty() || simulateRun(px, py, snap, tornadoes, CLICK_LATENCY_TICKS, 0)[0] >= 1)) {
                    if (!isAlreadyWalkingTo(snap[0], snap[1])) {
                        lastWalkTargetX = snap[0];
                        lastWalkTargetY = snap[1];
                        Microbot.log(String.format("[Action] Snap-walking to safe lane tile (%d,%d)", snap[0], snap[1]));
                        walkExactTile(snap[0], snap[1]);
                    }
                    return true;
                }
                return evadeTornadoes(px, py, bossX, bossY, tornadoes)
                        || escapeAcrossDanger(px, py, bossX, bossY, tornadoes);
            }
        }).orElse(false);

        return result;
    }

    /**
     * Steps onto the lane at its entry tile and runs on to a lane end, judged as one route: a 1-tile entry step that
     * ends beside a tornado is fine if the run along the lane then pulls away. Judging the entry on its own refused
     * the step onto a fully clear 12-tile lane and we died beside the boss instead. Only a straight, fully safe
     * entry; the run to the end gets the click latency again at the entry tile (the second click).
     *
     * @return true if the entry walk is under way
     */
    private boolean enterLaneAndRun(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        if (tornadoes.isEmpty() || safeLaneTileA == null || safeLaneTileB == null) return false;
        int[] entry = transitionEntryTile;
        if (!isStraightPathFullySafe(px, py, entry[0], entry[1], bossX, bossY)) return false;
        int[] pred = predictedStart(px, py);
        if ((pred[0] != px || pred[1] != py) && !isStraightPathFullySafe(pred[0], pred[1], entry[0], entry[1], bossX, bossY)) return false;
        int[] bestEnd = null;
        int[] bestSim = null;
        for (int[] end : new int[][]{safeLaneTileA, safeLaneTileB}) {
            if (end[0] == entry[0] && end[1] == entry[1]) continue;
            int[] sim = simulateEntryRun(px, py, entry, end, tornadoes);
            if (sim[0] < 1 || sim[1] < 2) continue;
            if (bestSim == null || sim[0] > bestSim[0] || (sim[0] == bestSim[0] && sim[1] > bestSim[1])) {
                bestEnd = end;
                bestSim = sim;
            }
        }
        if (bestEnd == null) return false;
        plannedRunEnd = bestEnd;
        if (!isAlreadyWalkingTo(entry[0], entry[1])) {
            lastWalkTargetX = entry[0];
            lastWalkTargetY = entry[1];
            Microbot.log(String.format("[Action] Entering lane at (%d,%d) and running on to (%d,%d) (closest pass %d, end gap %d) %s",
                    entry[0], entry[1], bestEnd[0], bestEnd[1], bestSim[0], bestSim[1], vitalsTag()));
            walkExactTile(entry[0], entry[1]);
        }
        return true;
    }

    /** simulateRun for px -> entry, a click-latency stop at the entry, then entry -> end: {margin, end gap, hits}. */
    private int[] simulateEntryRun(int px, int py, int[] entry, int[] end, List<int[]> tornadoes) {
        List<int[]> tz = new ArrayList<>();
        for (int[] t : tornadoes) tz.add(new int[]{t[0], t[1]});
        int[] pos = {px, py};
        int margin = Integer.MAX_VALUE, gap = Integer.MAX_VALUE, hits = 0;
        int[][] legs = {entry, end};
        for (int leg = 0; leg < 2; leg++) {
            for (int l = 0; l < CLICK_LATENCY_TICKS; l++) {
                for (int[] t : tz) stepToward(t, pos[0], pos[1]);
                gap = minTornadoDistance(pos[0], pos[1], tz);
                margin = Math.min(margin, gap);
                hits += tornadoesOn(pos[0], pos[1], tz);
            }
            int[] target = legs[leg];
            while (pos[0] != target[0] || pos[1] != target[1]) {
                for (int k = 0; k < runSpeed && (pos[0] != target[0] || pos[1] != target[1]); k++) stepToward(pos, target[0], target[1]);
                for (int[] t : tz) stepToward(t, pos[0], pos[1]);
                gap = minTornadoDistance(pos[0], pos[1], tz);
                margin = Math.min(margin, gap);
                hits += tornadoesOn(pos[0], pos[1], tz);
            }
        }
        return new int[]{margin, gap, hits};
    }

    /**
     * Walks toward (tx,ty) without crossing an unsafe tile (damage/warning floor, Hunllef footprint, off-arena).
     * If the direct walk is safe we go straight there; otherwise we follow the cardinal safe BFS path and walk to
     * the farthest tile on it still reachable by one safe leg - i.e. the next corner waypoint. Called every loop,
     * so each leg is re-planned from where we stand and re-validated as the floors change.
     *
     * With tornadoes up, a leg is only taken if the tornado simulation says we're not caught on the way (the walk
     * to a lane once routed straight into a group of three: 55 damage in one tick). The farthest clean leg wins.
     *
     * @return true if a safe walk toward the target is under way; false if no safe (or tornado-free) route exists
     */
    private boolean walkSafeRoute(int px, int py, int tx, int ty, int bossX, int bossY, List<int[]> tornadoes) {
        List<int[]> legs = new ArrayList<>();
        if (isStraightPathFullySafe(px, py, tx, ty, bossX, bossY)) {
            legs.add(new int[]{tx, ty});
        } else {
            List<int[]> path = findCardinalSafePath(px, py, tx, ty, bossX, bossY);
            if (path.size() <= 1) return false;
            for (int i = path.size() - 1; i >= 1; i--) {
                int[] candidate = path.get(i);
                if (isStraightPathFullySafe(px, py, candidate[0], candidate[1], bossX, bossY)) legs.add(candidate);
            }
            if (legs.isEmpty()) legs.add(path.get(1)); // adjacent cardinal step on a safe tile
        }
        int[] leg = null;
        int[] pred = predictedStart(px, py);
        for (int[] candidate : legs) {
            if ((pred[0] != px || pred[1] != py)
                    && !isStraightPathFullySafe(pred[0], pred[1], candidate[0], candidate[1], bossX, bossY)) continue;
            if (tornadoes.isEmpty()) { leg = candidate; break; }
            // Already walking there: no click latency. Only the way there counts; at the leg's end we re-plan.
            int latency = isAlreadyWalkingTo(candidate[0], candidate[1]) ? 0 : CLICK_LATENCY_TICKS;
            // ...and don't end beside one: a leg ending at gap 1 got hit on the next tick (93 -> 75).
            int[] sim = simulateRun(px, py, candidate, tornadoes, latency, 0);
            if (sim[0] >= 1 && sim[1] >= 2) { leg = candidate; break; }
        }
        if (leg == null) {
            if (routeBlockedLogTick != gameTickCount) {
                routeBlockedLogTick = gameTickCount;
                Microbot.log(String.format("[Action] Route to (%d,%d) runs into a tornado - not taking it %s", tx, ty, vitalsTag()));
            }
            return false;
        }
        if (!isAlreadyWalkingTo(leg[0], leg[1])) {
            lastWalkTargetX = leg[0];
            lastWalkTargetY = leg[1];
            if (leg[0] != tx || leg[1] != ty) {
                Microbot.log(String.format("[Action] Routing around hazard via waypoint (%d,%d) (target %d,%d)",
                        leg[0], leg[1], tx, ty));
            }
            walkExactTile(leg[0], leg[1]);
        }
        return true;
    }

    /**
     * Checks every tile the player would actually cross walking from (fromX, fromY) to (toX, toY). The game walks
     * the major axis straight until the remaining gaps are equal, then diagonally (e.g. dx=5,dy=2 -> E,E,E,NE,NE),
     * so that is the path simulated here - not diagonal-first, which approves lines the real walk doesn't take.
     * Returns true ONLY if all tiles are 100% safe (not active damage, not warning floor, not Hunllef).
     */
    private boolean isStraightPathFullySafe(int fromX, int fromY, int toX, int toY, int bossX, int bossY, boolean logFail) {
        int cx = fromX;
        int cy = fromY;
        while (cx != toX || cy != toY) {
            int rx = toX - cx, ry = toY - cy;
            if (Math.abs(rx) > Math.abs(ry)) {
                cx += Integer.signum(rx);
            } else if (Math.abs(ry) > Math.abs(rx)) {
                cy += Integer.signum(ry);
            } else {
                cx += Integer.signum(rx);
                cy += Integer.signum(ry);
            }
            if (!isTileFullySafe(cx, cy, bossX, bossY)) {
                if (logFail) {
                    Microbot.log(String.format("[Trajectory Blocked] Tile (%d,%d) unsafe on path to (%d,%d): inArena=%b safeFloor=%b onDanger=%b underBoss=%b (boss center=%d,%d)",
                            cx, cy, toX, toY, inArena(cx, cy), isSafeFloor(cx, cy), isOnDangerousTile(cx, cy), isUnderBoss(cx, cy, bossX, bossY, 0), bossX, bossY));
                }
                return false;
            }
        }
        return true;
    }

    private boolean isStraightPathFullySafe(int fromX, int fromY, int toX, int toY, int bossX, int bossY) {
        return isStraightPathFullySafe(fromX, fromY, toX, toY, bossX, bossY, false);
    }

    private static class SafeSegment {
        int[] p1;
        int[] p2;
        int dist;
        int rimDist;
        boolean horizontal;

        SafeSegment(int[] p1, int[] p2, int rimDist, boolean horizontal) {
            this.p1 = p1;
            this.p2 = p2;
            this.dist = Math.max(Math.abs(p2[0] - p1[0]), Math.abs(p2[1] - p1[1]));
            this.rimDist = rimDist;
            this.horizontal = horizontal;
        }
    }

    /** True if a single tile is completely safe to stand on (in arena, floor, not fire/warning, not under boss 5x5). */
    private boolean isTileFullySafe(int x, int y, int bossX, int bossY) {
        if (!inArena(x, y)) return false;
        if (!isSafeFloor(x, y)) return false;
        if (isOnDangerousTile(x, y)) return false;
        if (isUnderBoss(x, y, bossX, bossY, 0)) return false;
        return true;
    }

    /** True if all tiles between p1 and p2 (inclusive) are completely safe. */
    private boolean isSegmentFullySafe(int[] p1, int[] p2, int bossX, int bossY) {
        if (p1 == null || p2 == null) return false;
        int dx = Integer.signum(p2[0] - p1[0]);
        int dy = Integer.signum(p2[1] - p1[1]);
        int cx = p1[0], cy = p1[1];
        while (true) {
            if (!isTileFullySafe(cx, cy, bossX, bossY)) return false;
            if (cx == p2[0] && cy == p2[1]) break;
            cx += dx;
            cy += dy;
        }
        return true;
    }

    /** Computes a unique fingerprint of all dangerous floor tiles in the arena to detect layout changes. */
    private long computeDangerFloorFingerprint() {
        if (!arenaBoundsKnown) return 0L;
        long h = 1125899906842597L;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (isOnDangerousTile(x, y)) {
                    h = 31L * h + ((x << 8) | y);
                }
            }
        }
        return h;
    }

    /** Discovers all contiguous safe segments in the arena along rows and columns. */
    private List<SafeSegment> findAllSafeSegments(int bossX, int bossY) {
        List<SafeSegment> segments = new ArrayList<>();
        if (!arenaBoundsKnown) return segments;

        // 1. Scan horizontal rows (same Y)
        for (int y = arenaMinY; y <= arenaMaxY; y++) {
            int rimDist = Math.min(y - arenaMinY, arenaMaxY - y);
            int segStart = -1;
            for (int x = arenaMinX; x <= arenaMaxX; x++) {
                if (isTileFullySafe(x, y, bossX, bossY)) {
                    if (segStart == -1) segStart = x;
                } else {
                    if (segStart != -1) {
                        int segEnd = x - 1;
                        if (segEnd > segStart) {
                            segments.add(new SafeSegment(new int[]{segStart, y}, new int[]{segEnd, y}, rimDist, true));
                        }
                        segStart = -1;
                    }
                }
            }
            if (segStart != -1) {
                int segEnd = arenaMaxX;
                if (segEnd > segStart) {
                    segments.add(new SafeSegment(new int[]{segStart, y}, new int[]{segEnd, y}, rimDist, true));
                }
            }
        }

        // 2. Scan vertical columns (same X)
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            int rimDist = Math.min(x - arenaMinX, arenaMaxX - x);
            int segStart = -1;
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (isTileFullySafe(x, y, bossX, bossY)) {
                    if (segStart == -1) segStart = y;
                } else {
                    if (segStart != -1) {
                        int segEnd = y - 1;
                        if (segEnd > segStart) {
                            segments.add(new SafeSegment(new int[]{x, segStart}, new int[]{x, segEnd}, rimDist, false));
                        }
                        segStart = -1;
                    }
                }
            }
            if (segStart != -1) {
                int segEnd = arenaMaxY;
                if (segEnd > segStart) {
                    segments.add(new SafeSegment(new int[]{x, segStart}, new int[]{x, segEnd}, rimDist, false));
                }
            }
        }

        return segments;
    }

    /** 4-direction cardinal BFS over safe tiles from (startX, startY). Returns map of packed tile -> distance. */
    private Map<Integer, Integer> computeReachableTiles(int startX, int startY, int bossX, int bossY) {
        Map<Integer, Integer> distances = new HashMap<>();
        Queue<Integer> queue = new ArrayDeque<>();

        int startPacked = (startX << 8) | startY;
        distances.put(startPacked, 0);
        queue.add(startPacked);

        int[] dx = {0, 0, -1, 1};
        int[] dy = {1, -1, 0, 0};

        while (!queue.isEmpty()) {
            int curr = queue.poll();
            int d = distances.get(curr);
            int cx = curr >> 8;
            int cy = curr & 0xFF;

            for (int i = 0; i < 4; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];

                if (!inArena(nx, ny)) continue;
                if (!isTileFullySafe(nx, ny, bossX, bossY)) continue;

                int nPacked = (nx << 8) | ny;
                if (!distances.containsKey(nPacked)) {
                    distances.put(nPacked, d + 1);
                    queue.add(nPacked);
                }
            }
        }
        return distances;
    }

    /** Finds shortest cardinal safe path from start to target. */
    private List<int[]> findCardinalSafePath(int startX, int startY, int targetX, int targetY, int bossX, int bossY) {
        if (startX == targetX && startY == targetY) {
            return Collections.singletonList(new int[]{startX, startY});
        }

        Queue<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        Set<Integer> visited = new HashSet<>();

        int startPacked = (startX << 8) | startY;
        queue.add(startPacked);
        visited.add(startPacked);

        int targetPacked = (targetX << 8) | targetY;
        boolean found = false;

        int[] dx = {0, 0, -1, 1};
        int[] dy = {1, -1, 0, 0};

        while (!queue.isEmpty()) {
            int curr = queue.poll();
            if (curr == targetPacked) {
                found = true;
                break;
            }

            int cx = curr >> 8;
            int cy = curr & 0xFF;

            for (int i = 0; i < 4; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];

                if (!inArena(nx, ny)) continue;
                if (!isTileFullySafe(nx, ny, bossX, bossY)) continue;

                int nPacked = (nx << 8) | ny;
                if (visited.add(nPacked)) {
                    cameFrom.put(nPacked, curr);
                    queue.add(nPacked);
                }
            }
        }

        if (!found) return Collections.emptyList();

        List<int[]> path = new ArrayList<>();
        int curr = targetPacked;
        while (curr != startPacked) {
            path.add(new int[]{curr >> 8, curr & 0xFF});
            curr = cameFrom.get(curr);
        }
        path.add(new int[]{startX, startY});
        Collections.reverse(path);
        return path;
    }

    /**
     * Compresses a cardinal path into turn/corner waypoints.
     * All movements between waypoints are purely along a single axis (pure N/S or pure E/W),
     * preventing diagonal path cutting through hazards or boss footprints.
     */
    private List<int[]> extractWaypoints(List<int[]> path) {
        if (path == null || path.size() <= 1) return Collections.emptyList();

        List<int[]> waypoints = new ArrayList<>();
        int prevDirX = path.get(1)[0] - path.get(0)[0];
        int prevDirY = path.get(1)[1] - path.get(0)[1];

        for (int i = 2; i < path.size(); i++) {
            int dirX = path.get(i)[0] - path.get(i - 1)[0];
            int dirY = path.get(i)[1] - path.get(i - 1)[1];

            if (dirX != prevDirX || dirY != prevDirY) {
                waypoints.add(path.get(i - 1));
                prevDirX = dirX;
                prevDirY = dirY;
            }
        }
        waypoints.add(path.get(path.size() - 1));
        return waypoints;
    }

    /**
     * At a lane endpoint with tornadoes closing in: adopt a safe lane of at least {@link #MIN_NEW_LANE_TILES}
     * through the player's tile on the OTHER axis (so never the line we just ran), targeting the end we can run
     * to without a tornado catching us on the way. Prefers the longest run, then the end farthest from tornadoes.
     *
     * @return true if a new lane was adopted (currentShuttleTarget is set to its chosen end)
     */
    private boolean switchToPerpendicularLane(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        boolean oldHorizontal = safeLaneTileA[1] == safeLaneTileB[1];
        List<SafeSegment> all = findAllSafeSegments(bossX, bossY);
        SafeSegment bestSeg = null;
        int[] bestEnd = null;
        boolean bestExit = false;
        int bestRun = 0;
        int bestGap = -1;

        for (SafeSegment raw : all) {
            if (raw.horizontal == oldHorizontal) continue;
            if (raw.dist + 1 < MIN_NEW_LANE_TILES) continue;
            if (!segmentContains(raw, px, py)) continue;
            // Stop short of a dead end where we can, but never trim away the tile we're standing on.
            SafeSegment seg = trimToExits(raw, all);
            if (!segmentContains(seg, px, py)) seg = raw;

            for (int[] end : new int[][]{seg.p1, seg.p2}) {
                int run = Math.max(Math.abs(end[0] - px), Math.abs(end[1] - py));
                if (run < MIN_NEW_LANE_RUN) continue;
                if (!outrunsTornadoes(px, py, end, tornadoes)) continue;
                // The row between the Hunllef and the wall has no side exits: tornadoes closed it from both ends
                // and pinned us in a 4-tile pocket (x8, x9 hits).
                if (bossTilesOnRun(px, py, end, bossX, bossY) >= BOSS_CORRIDOR_TILES) continue;
                boolean exit = hasPerpendicularExit(end[0], end[1], seg.horizontal, all);
                // A dead end toward the Hunllef runs into the tornado spawn with no way out: evade instead.
                if (!exit && Math.max(Math.abs(end[0] - bossX), Math.abs(end[1] - bossY))
                        < Math.max(Math.abs(px - bossX), Math.abs(py - bossY))) continue;
                int gap = minTornadoDistance(end[0], end[1], tornadoes);
                boolean better = exit != bestExit ? exit
                        : (run > bestRun || (run == bestRun && gap > bestGap));
                if (bestSeg == null || better) {
                    bestSeg = seg;
                    bestEnd = end;
                    bestExit = exit;
                    bestRun = run;
                    bestGap = gap;
                }
            }
        }

        if (bestSeg == null) return false;

        safeLaneTileA = bestSeg.p1;
        safeLaneTileB = bestSeg.p2;
        rebuildCurrentLaneTiles();
        currentShuttleTarget = bestEnd;
        Microbot.log(String.format("[Safe Lane] Turning onto perpendicular lane (%d,%d)-(%d,%d) len=%d, running %d tiles to (%d,%d) (end gap %d%s)",
                bestSeg.p1[0], bestSeg.p1[1], bestSeg.p2[0], bestSeg.p2[1], bestSeg.dist + 1,
                bestRun, bestEnd[0], bestEnd[1], bestGap, bestExit ? "" : ", dead end"));
        return true;
    }

    /**
     * A tile right beside the Hunllef's footprint whose next tile outward (away from the boss) is off the arena or
     * hazard: the 1-wide strip between the boss and the wall/floor, where tornadoes close you in from both ends.
     */
    private boolean isBossCorridorTile(int x, int y, int bossX, int bossY) {
        int dx = x - bossX, dy = y - bossY;
        if (Math.max(Math.abs(dx), Math.abs(dy)) != 3) return false; // footprint is +-2; 3 = touching it
        int ox = x, oy = y;
        if (Math.abs(dx) >= Math.abs(dy)) ox += Integer.signum(dx);
        if (Math.abs(dy) >= Math.abs(dx)) oy += Integer.signum(dy);
        return !inArena(ox, oy) || !isTileFullySafe(ox, oy, bossX, bossY);
    }

    /**
     * The walk clicked earlier this tick still stands: we're not on its tile yet and the game isn't walking us
     * somewhere else. A click to the tile we already stood on once blocked the evade that would have saved us.
     */
    private boolean earlierWalkClickLive() {
        Player me = Microbot.getClient().getLocalPlayer();
        if (me == null || playerTile(me) == null) return false;
        if (playerTile(me).getSceneX() == walkClickX && playerTile(me).getSceneY() == walkClickY) return false;
        LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
        return dest == null || (dest.getSceneX() == walkClickX && dest.getSceneY() == walkClickY);
    }

    /**
     * Where a walk click sent now takes effect: one tick along the current walk if we're moving, else where we
     * stand. A new click is processed a tick later, so a path checked from the current tile can differ from the
     * one the game walks (a clean line along y=35 became a route under the Hunllef from two tiles further on).
     */
    private int[] predictedStart(int px, int py) {
        int[] pos = {px, py};
        LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
        if (dest == null) return pos;
        for (int i = 0; i < runSpeed; i++) stepToward(pos, dest.getSceneX(), dest.getSceneY());
        return pos;
    }

    /** True if the walk to (tx,ty) is clear of the Hunllef from both where we stand and where the click lands. */
    private boolean clearOfBossFromBoth(int px, int py, int[] pred, int tx, int ty, int bossX, int bossY) {
        if (walkLineCrossesBoss(px, py, tx, ty, bossX, bossY)) return false;
        return (pred[0] == px && pred[1] == py) || isUnderBoss(pred[0], pred[1], bossX, bossY, 0)
                || !walkLineCrossesBoss(pred[0], pred[1], tx, ty, bossX, bossY);
    }

    /** Tiles on the walk from (px,py) to end (excluding the start) within 1 tile of the Hunllef's footprint. */
    private static int bossTilesOnRun(int px, int py, int[] end, int bossX, int bossY) {
        int n = 0;
        int[] pos = {px, py};
        while (pos[0] != end[0] || pos[1] != end[1]) {
            stepToward(pos, end[0], end[1]);
            if (Math.max(Math.abs(pos[0] - bossX), Math.abs(pos[1] - bossY)) - 2 <= 1) n++;
        }
        return n;
    }

    private static boolean segmentContains(SafeSegment seg, int x, int y) {
        return seg.horizontal
                ? (y == seg.p1[1] && x >= seg.p1[0] && x <= seg.p2[0])
                : (x == seg.p1[0] && y >= seg.p1[1] && y <= seg.p2[1]);
    }

    /**
     * True if a fresh perpendicular lane (at least {@link #MIN_NEW_LANE_TILES} long, {@link #MIN_NEW_LANE_RUN}
     * tiles of run from here) crosses (x,y) - i.e. a way out when tornadoes pin us to this tile of the lane.
     */
    private boolean hasPerpendicularExit(int x, int y, boolean laneHorizontal, List<SafeSegment> all) {
        for (SafeSegment s : all) {
            if (s.horizontal == laneHorizontal || s.dist + 1 < MIN_NEW_LANE_TILES) continue;
            if (!segmentContains(s, x, y)) continue;
            int run = s.horizontal ? Math.max(x - s.p1[0], s.p2[0] - x) : Math.max(y - s.p1[1], s.p2[1] - y);
            if (run >= MIN_NEW_LANE_RUN) return true;
        }
        return false;
    }

    /**
     * Cuts dead ends off a lane: each end moves in to the outermost tile with a perpendicular exit. Running into a
     * dead end with tornadoes chasing leaves only the way back through them (the corner traps), so turning at the
     * last exit beats a few extra tiles of run. Returns the segment unchanged if the trimmed lane would be shorter
     * than {@link #MIN_NEW_LANE_TILES}.
     */
    private SafeSegment trimToExits(SafeSegment seg, List<SafeSegment> all) {
        int[] first = null, last = null;
        for (int[] t : getSegmentTiles(seg)) {
            if (hasPerpendicularExit(t[0], t[1], seg.horizontal, all)) {
                if (first == null) first = t;
                last = t;
            }
        }
        if (first == null) return seg;
        if (Math.max(Math.abs(last[0] - first[0]), Math.abs(last[1] - first[1])) + 1 < MIN_NEW_LANE_TILES) return seg;
        if (Arrays.equals(first, seg.p1) && Arrays.equals(last, seg.p2)) return seg;
        return new SafeSegment(first, last, seg.rimDist, seg.horizontal);
    }

    /**
     * Loop mode: when hazards cover most of the arena and a square ring around the Hunllef is fully safe, circle it
     * with the tornadoes trailing behind, so there's never a lane end to stop at. The direction is picked by
     * simulating a run both ways and only flipped when the other way is clearly better. Each pass clicks the
     * farthest ring tile up to {@link #LOOP_LOOKAHEAD} ahead that a straight safe leg reaches, so corners are taken
     * as part of the run. Client thread.
     *
     * @return true if loop mode handled movement this pass; false to fall back to the lanes
     */
    private boolean loopRun(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        int ringD = -1;
        for (int d = LOOP_MAX_RING; d >= LOOP_MIN_RING; d--) {
            if (isRingFullySafe(bossX, bossY, d)) { ringD = d; break; }
        }
        if (ringD < 0) {
            if (loopMode) Microbot.log("[Loop] Safe ring around the Hunllef broke - back to lanes");
            loopMode = false;
            loopRingSize = -1;
            return false;
        }
        if (!loopMode) {
            // Enter only once the floor is mostly hazard; while in loop mode, stay while a ring exists (no flapping).
            int safe = 0, total = 0;
            for (int x = arenaMinX; x <= arenaMaxX; x++) {
                for (int y = arenaMinY; y <= arenaMaxY; y++) {
                    total++;
                    if (isTileFullySafe(x, y, bossX, bossY)) safe++;
                }
            }
            if (total == 0 || (double) safe / total > LOOP_MAX_SAFE_FRACTION) return false;
            loopMode = true;
            Microbot.log(String.format("[Loop] Safe ring around the Hunllef (distance %d, %d tiles, %d%% of arena safe) - circling it",
                    ringD, ringD * 8, safe * 100 / total));
        }
        if (ringD != loopRingSize && loopRingSize > 0) {
            Microbot.log(String.format("[Loop] Ring changed to distance %d", ringD));
        }
        loopRingSize = ringD;
        // A running evade finishes first: wiping it here re-picked an evade back through the tornadoes (x9) and
        // the clicks that followed walked us under the Hunllef.
        if (evadeTarget != null) return false;

        List<int[]> ring = ringTiles(bossX, bossY, ringD);
        int n = ring.size();
        int idx = -1;
        for (int i = 0; i < n; i++) {
            if (ring.get(i)[0] == px && ring.get(i)[1] == py) { idx = i; break; }
        }
        if (idx < 0) {
            // Not on the ring (just entered loop mode, or the boss moved): get onto its nearest tile.
            int[] nearest = null;
            int best = Integer.MAX_VALUE;
            for (int[] t : ring) {
                int d = Math.max(Math.abs(t[0] - px), Math.abs(t[1] - py));
                if (d < best) { best = d; nearest = t; }
            }
            return nearest != null && walkSafeRoute(px, py, nearest[0], nearest[1], bossX, bossY, tornadoes);
        }

        if (!tornadoes.isEmpty()) {
            int[] cur = simulateLoop(ring, idx, loopDir, tornadoes);
            int[] other = simulateLoop(ring, idx, -loopDir, tornadoes);
            boolean flip = (cur[0] < 1 && other[0] >= 1)
                    || (cur[0] < 1 && other[0] < 1 && other[1] < cur[1]);
            if (flip) {
                loopDir = -loopDir;
                Microbot.log(String.format("[Loop] Reversing - tornado ahead (closest pass %d -> %d) %s",
                        cur[0], other[0], vitalsTag()));
                cur = other;
            }
            if (cur[0] < 1) hitExpectedUntil = now + HIT_EXPECTED_MS;

            // Attack from the ring while the tornadoes are far behind: hold still for the shot, then keep circling.
            int gap = minTornadoDistance(px, py, tornadoes);
            if (holdingForAttack() && gap > 2) return true;
            if (config.autoAttack() && hunllef != null && (isBowEquipped() || isStaffEquipped())
                    && weaponReady() && gap > 3
                    && simulateLoop(ring, idx, loopDir, tornadoesAfterPause(px, py, tornadoes))[0] >= 2) {
                Microbot.log(String.format("[Loop] Attacking from the ring (tornado gap %d)", gap));
                attackHunllef();
                timeAttack = now;
                lastAttackTick = gameTickCount;
                midRunAttackTick = gameTickCount;
                return true;
            }
        }

        int[] target = null;
        for (int k = LOOP_LOOKAHEAD; k >= 1; k--) {
            int[] t = ring.get(Math.floorMod(idx + loopDir * k, n));
            if (walkLineStaysOnRing(px, py, t, bossX, bossY, ringD)
                    && isStraightPathFullySafe(px, py, t[0], t[1], bossX, bossY)) { target = t; break; }
        }
        if (target == null) return false;

        // Keep the current walk while it's still heading along the ring our way and has room left; else re-click.
        LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
        if (dest != null && !(dest.getSceneX() == target[0] && dest.getSceneY() == target[1])) {
            for (int k = 3; k <= LOOP_LOOKAHEAD; k++) {
                int[] t = ring.get(Math.floorMod(idx + loopDir * k, n));
                if (t[0] == dest.getSceneX() && t[1] == dest.getSceneY()) return true;
            }
        }
        if (!isAlreadyWalkingTo(target[0], target[1])) {
            lastWalkTargetX = target[0];
            lastWalkTargetY = target[1];
            walkExactTile(target[0], target[1]);
        }
        return true;
    }

    /** True if the game's walk from (px,py) to t never leaves the ring (no corner shortcut off it). */
    private static boolean walkLineStaysOnRing(int px, int py, int[] t, int bx, int by, int d) {
        int[] pos = {px, py};
        while (pos[0] != t[0] || pos[1] != t[1]) {
            stepToward(pos, t[0], t[1]);
            if (Math.max(Math.abs(pos[0] - bx), Math.abs(pos[1] - by)) != d) return false;
        }
        return true;
    }

    /** True if every tile at Chebyshev distance d from the boss centre is fully safe (a closed loop). */
    private boolean isRingFullySafe(int bossX, int bossY, int d) {
        for (int[] t : ringTiles(bossX, bossY, d)) {
            if (!isTileFullySafe(t[0], t[1], bossX, bossY)) return false;
        }
        return true;
    }

    /** The 8d tiles at Chebyshev distance d from (bx,by), in order around the square. */
    private static List<int[]> ringTiles(int bx, int by, int d) {
        List<int[]> ring = new ArrayList<>(8 * d);
        for (int x = bx - d; x < bx + d; x++) ring.add(new int[]{x, by - d});
        for (int y = by - d; y < by + d; y++) ring.add(new int[]{bx + d, y});
        for (int x = bx + d; x > bx - d; x--) ring.add(new int[]{x, by + d});
        for (int y = by + d; y > by - d; y--) ring.add(new int[]{bx - d, y});
        return ring;
    }

    /**
     * Simulates running around the ring from index idx in direction dir for {@link #LOOP_SIM_TICKS} ticks, with
     * tornadoes chasing (one free step first for the click latency).
     *
     * @return {closest end-of-tick gap (0 = hit), tornado hits}
     */
    private int[] simulateLoop(List<int[]> ring, int idx, int dir, List<int[]> tornadoes) {
        int n = ring.size();
        List<int[]> tz = new ArrayList<>();
        for (int[] t : tornadoes) tz.add(new int[]{t[0], t[1]});
        int[] pos = ring.get(idx);
        int margin = Integer.MAX_VALUE, hits = 0;
        for (int tick = 0; tick < CLICK_LATENCY_TICKS + LOOP_SIM_TICKS; tick++) {
            if (tick >= CLICK_LATENCY_TICKS) {
                idx = Math.floorMod(idx + dir * runSpeed, n);
                pos = ring.get(idx);
            }
            for (int[] t : tz) stepToward(t, pos[0], pos[1]);
            int gap = minTornadoDistance(pos[0], pos[1], tz);
            margin = Math.min(margin, gap);
            hits += tornadoesOn(pos[0], pos[1], tz);
        }
        return new int[]{margin, hits};
    }

    /**
     * The lane end to run to: a clean run (never caught) first, then the most room from the tornadoes on the way,
     * then the most room at the end. Picking the end merely farthest from the tornadoes once sent us down a lane
     * that passed one at gap 1. Returns null when neither end is clean - then the evade, which weighs every tile,
     * decides (picking the less-bad end once ran us 11 tiles through two tornadoes sitting on the lane).
     */
    private int[] pickRunEnd(int px, int py, List<int[]> tornadoes) {
        if (tornadoes.isEmpty()) return safeLaneTileA;
        int[] end = pickLessBadRunEnd(px, py, tornadoes);
        return outrunsTornadoes(px, py, end, tornadoes) ? end : null;
    }

    /** The lane end whose run the simulation scores best, clean or not: margin, then fewest hits, then end gap. */
    private int[] pickLessBadRunEnd(int px, int py, List<int[]> tornadoes) {
        if (tornadoes.isEmpty()) return safeLaneTileA;
        int[] a = simulateRun(px, py, safeLaneTileA, tornadoes);
        int[] b = simulateRun(px, py, safeLaneTileB, tornadoes);
        boolean pickA;
        if ((a[0] >= 1) != (b[0] >= 1)) pickA = a[0] >= 1;
        else if (a[0] != b[0]) pickA = a[0] > b[0];
        else if (a[2] != b[2]) pickA = a[2] < b[2];
        else pickA = a[1] >= b[1];
        return pickA ? safeLaneTileA : safeLaneTileB;
    }

    /** True if running the game's walk from (px,py) to {@code end} is never caught by a tornado (see {@link #simulateRun}). */
    private boolean outrunsTornadoes(int px, int py, int[] end, List<int[]> tornadoes) {
        return simulateRun(px, py, end, tornadoes)[0] >= 1;
    }

    /**
     * Tick-by-tick simulation of running to {@code end} while the tornadoes chase. Each tick we cover 2 tiles of
     * the game's walk (major axis straight, then diagonal), then every tornado steps 1 tile toward where we now
     * stand by the same model; we're hit only when one ends the tick on our tile. Runs
     * {@link #SIM_TICKS_AFTER_ARRIVAL} extra ticks standing on {@code end}, so a tile they'd catch us on right
     * after we stop also scores badly. Tornadoes moving after us is the conservative order.
     *
     * @return {min end-of-tick gap over the whole run (0 = hit), gap at the end of the simulation, tornado hits}
     */
    private int[] simulateRun(int px, int py, int[] end, List<int[]> tornadoes) {
        return simulateRun(px, py, end, tornadoes, CLICK_LATENCY_TICKS, SIM_TICKS_AFTER_ARRIVAL);
    }

    /** {@link #simulateRun} with explicit click-latency ticks and ticks simulated standing on {@code end}. */
    private int[] simulateRun(int px, int py, int[] end, List<int[]> tornadoes, int latencyTicks, int afterTicks) {
        return simulateRun(px, py, end, tornadoes, latencyTicks, afterTicks, null);
    }

    /** As above; if endTornadoes is given it receives the tornado positions when the simulation ends. */
    private int[] simulateRun(int px, int py, int[] end, List<int[]> tornadoes, int latencyTicks, int afterTicks,
                              List<int[]> endTornadoes) {
        if (tornadoes.isEmpty()) return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, 0};
        List<int[]> line = new ArrayList<>();
        int[] pos = {px, py};
        while (pos[0] != end[0] || pos[1] != end[1]) {
            stepToward(pos, end[0], end[1]);
            line.add(new int[]{pos[0], pos[1]});
        }
        List<int[]> tz = new ArrayList<>();
        for (int[] t : tornadoes) tz.add(new int[]{t[0], t[1]});

        int cx = px, cy = py, idx = 0, margin = Integer.MAX_VALUE, gap = Integer.MAX_VALUE, hits = 0;
        // The click lands mid-tick, so the tornadoes step before we do (seen: 1 tile in the first tick after a
        // click with an adjacent tornado, then hit). Modelling it keeps every outrun check honest.
        for (int l = 0; l < latencyTicks; l++) {
            for (int[] t : tz) stepToward(t, cx, cy);
            gap = minTornadoDistance(cx, cy, tz);
            margin = Math.min(margin, gap);
            hits += tornadoesOn(cx, cy, tz);
        }
        int ticks = (line.size() + runSpeed - 1) / runSpeed + afterTicks;
        for (int tick = 0; tick < ticks; tick++) {
            for (int k = 0; k < runSpeed && idx < line.size(); k++, idx++) {
                cx = line.get(idx)[0];
                cy = line.get(idx)[1];
            }
            for (int[] t : tz) stepToward(t, cx, cy);
            gap = minTornadoDistance(cx, cy, tz);
            margin = Math.min(margin, gap);
            hits += tornadoesOn(cx, cy, tz);
        }
        if (endTornadoes != null) endTornadoes.addAll(tz);
        return new int[]{margin, gap, hits};
    }

    /** Tornadoes ending the tick on (x,y): each one hits, so a bunched group on our tile counts several times. */
    private static int tornadoesOn(int x, int y, List<int[]> tz) {
        int n = 0;
        for (int[] t : tz) if (t[0] == x && t[1] == y) n++;
        return n;
    }

    /** Moves {@code pos} one tile toward (tx,ty) the way the game walks: major axis straight, then diagonal. */
    private static void stepToward(int[] pos, int tx, int ty) {
        int rx = tx - pos[0], ry = ty - pos[1];
        if (Math.abs(rx) > Math.abs(ry)) {
            pos[0] += Integer.signum(rx);
        } else if (Math.abs(ry) > Math.abs(rx)) {
            pos[1] += Integer.signum(ry);
        } else {
            pos[0] += Integer.signum(rx);
            pos[1] += Integer.signum(ry);
        }
    }

    /**
     * Both lane directions are cut off by tornadoes: pick, among safe tiles we can reach in one safe walk, the one
     * whose run keeps the most distance from the tornadoes the whole way ({@link #simulateRun}), then the larger
     * final gap, then the closer tile. Unlike "farthest tile from the tornadoes", this never picks a far tile whose
     * run passes through them (that ran us back down the lane into them). Committed via {@link #evadeTarget}; the
     * lane is dropped so a new one is chosen from where we land. Client thread.
     *
     * @return true if an evasion walk was issued
     */
    private boolean evadeTornadoes(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        // One pick per tick: each fresh click replaces the last, and re-picking every pass stood us still.
        if (evadeTarget != null && evadePickTick == gameTickCount) {
            if (!isAlreadyWalkingTo(evadeTarget[0], evadeTarget[1])) walkExactTile(evadeTarget[0], evadeTarget[1]);
            return true;
        }
        int[] best = null;
        int bestMargin = -1, bestGap = -1, bestDist = Integer.MAX_VALUE, bestHits = Integer.MAX_VALUE;
        boolean bestNearBoss = true, bestCorridor = true;
        int[] pred = predictedStart(px, py);
        boolean moving = pred[0] != px || pred[1] != py;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!isTileFullySafe(x, y, bossX, bossY)) continue;
                if (!isStraightPathFullySafe(px, py, x, y, bossX, bossY)) continue;
                if (moving && !isStraightPathFullySafe(pred[0], pred[1], x, y, bossX, bossY)) continue;
                int[] tile = new int[]{x, y};
                int[] sim = simulateRun(px, py, tile, tornadoes);
                int dist = Math.max(Math.abs(x - px), Math.abs(y - py));
                boolean nearBoss = isUnderBoss(x, y, bossX, bossY, 1);
                // The strip between the Hunllef and the wall: an evade there was caught by the next floor change
                // with nowhere to go but along the strip. Ranked as one extra hit.
                boolean corridor = isBossCorridorTile(x, y, bossX, bossY);
                boolean better;
                if (best == null) {
                    better = true;
                } else if ((sim[0] >= 1) != (bestMargin >= 1)) {
                    better = sim[0] >= 1;
                } else if (sim[0] >= 1) {
                    // Clean escapes: out of the boss strips, most room on the way, then at the end, then away from
                    // the boss, then shorter.
                    better = corridor != bestCorridor ? !corridor
                            : sim[0] != bestMargin ? sim[0] > bestMargin
                            : sim[1] != bestGap ? sim[1] > bestGap
                            : nearBoss != bestNearBoss ? !nearBoss
                            : dist < bestDist;
                } else {
                    // A hit is unavoidable: take the fewest, not next to the boss, over the shortest run. Ranking by
                    // end gap here once sent us 10 tiles back through the tornadoes to a tile beside the Hunllef.
                    int eff = sim[2] + (corridor ? 1 : 0), bestEff = bestHits + (bestCorridor ? 1 : 0);
                    better = eff != bestEff ? eff < bestEff
                            : sim[2] != bestHits ? sim[2] < bestHits
                            : nearBoss != bestNearBoss ? !nearBoss
                            : dist != bestDist ? dist < bestDist
                            : sim[1] > bestGap;
                }
                if (better) {
                    best = tile;
                    bestMargin = sim[0];
                    bestGap = sim[1];
                    bestHits = sim[2];
                    bestNearBoss = nearBoss;
                    bestCorridor = corridor;
                    bestDist = dist;
                }
            }
        }
        if (best == null) return false;
        boolean noWayOut = false;
        if (bestMargin >= 1) {
            // A clean run can still end in a pocket (the strip between the Hunllef and the wall) where every next
            // move is hit: 65 -> 9 in one tick. Prefer the best clean target that leaves a clean run onward.
            int[] withExit = bestCleanEvadeWithFollowUp(px, py, bossX, bossY, tornadoes);
            if (withExit != null) {
                if (withExit[0] != best[0] || withExit[1] != best[1]) {
                    int[] sim = simulateRun(px, py, withExit, tornadoes);
                    best = withExit;
                    bestMargin = sim[0];
                    bestGap = sim[1];
                }
            } else {
                noWayOut = true;
            }
        }
        // Two moves ahead: the run to the target AND the best run on from it, tornadoes simulated through both. One-move
        // picks kept ending in pockets late in the fight (4 tornadoes, 27% safe floor): three 1-tile evades in a row
        // each expected hits and none led anywhere.
        Plan plan = planTwoMoves(px, py, bossX, bossY, tornadoes);
        if (plan != null) {
            int[] sim = simulateRun(px, py, plan.first, tornadoes);
            if (plan.first[0] != best[0] || plan.first[1] != best[1]) {
                Microbot.log(String.format("[Plan] (%d,%d) then %s: hits %d+%d, hazard ticks %d+%d, end gap %d (one-move pick was (%d,%d)) %s",
                        plan.first[0], plan.first[1],
                        plan.second == null ? "nowhere" : "(" + plan.second[0] + "," + plan.second[1] + ")",
                        plan.hits1, plan.hits2, plan.landings1, plan.landings2, plan.endGap, best[0], best[1], vitalsTag()));
            }
            best = plan.first;
            bestMargin = sim[0];
            bestGap = sim[1];
            bestHits = sim[2];
            noWayOut = plan.second == null;
        }
        // A plan across a hazard block is a break-out of its own: keep running it while we cross the hazard.
        boolean breakout = plan != null && best == plan.first && plan.landings1 > 0;
        if (bestMargin < 1 && plan == null) {
            // Fewest hits, but not another tile of the same pocket: prefer a target with a clean run onward.
            int[] withExit = bestHitEvadeWithFollowUp(px, py, bossX, bossY, tornadoes, bestHits);
            if (withExit != null) {
                int[] sim = simulateRun(px, py, withExit, tornadoes);
                best = withExit;
                bestMargin = sim[0];
                bestGap = sim[1];
            }
        }
        if (bestMargin < 1) {
            // Expected hits that would kill us aren't "a few hits": then a hazard crossing is always worth a look.
            // (A x4 evade at 68 HP walked into a 4-tornado stack - 68 damage in one tick.)
            int hpNow = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            boolean lethal = bestHits * TORNADO_HIT_DMG >= hpNow - LETHAL_MARGIN;
            if (lethal) {
                Microbot.log(String.format("[Plan] All safe options lethal (x%d at %d HP) - looking for a hazard crossing", bestHits, hpNow));
            }
            // Boxed in: a couple of hazard landings beat a stack of tornado hits.
            if (bestHits >= breakoutMinHits() || lethal) {
                int[] out = pickBreakout(px, py, bossX, bossY, tornadoes, bestHits);
                if (out != null) {
                    int[] sim = simulateRun(px, py, out, tornadoes);
                    Microbot.log(String.format("[Breakout] Evade would take x%d - crossing %d hazard tile(s) to (%d,%d) (tornado hits x%d) %s",
                            bestHits, hazardLandings(px, py, out[0], out[1]), out[0], out[1], sim[2], vitalsTag()));
                    best = out;
                    bestMargin = sim[0];
                    bestGap = sim[1];
                    bestHits = sim[2];
                    breakout = true;
                }
            }
        }
        // Stick with the evade picked a moment ago unless the new one is actually better: every change is a new
        // ~350ms click, and 4 different targets in 5 ticks left us standing while the tornadoes walked in.
        if (lastEvade != null && gameTickCount - lastEvadeTick <= EVADE_STICK_TICKS && !breakout
                && (lastEvade[0] != best[0] || lastEvade[1] != best[1])
                && (lastEvade[0] != px || lastEvade[1] != py)
                && isTileFullySafe(lastEvade[0], lastEvade[1], bossX, bossY)
                && (plan != null ? !walkLineCrossesBoss(px, py, lastEvade[0], lastEvade[1], bossX, bossY)
                    : isStraightPathFullySafe(px, py, lastEvade[0], lastEvade[1], bossX, bossY))) {
            int[] old = simulateRun(px, py, lastEvade, tornadoes);
            boolean newBetter;
            if (plan != null) {
                // Judged the way it was picked: two moves ahead (a clearly better plan, not a tie, replaces it).
                Plan oldPlan = planFrom(px, py, lastEvade, bossX, bossY, tornadoes, buildSafeGrid(bossX, bossY));
                newBetter = oldPlan == null || plan.cost < oldPlan.cost - PLAN_SWITCH_MARGIN;
            } else {
                newBetter = (bestMargin >= 1 && old[0] < 1) || (bestMargin < 1 && old[0] < 1 && bestHits < old[2]);
            }
            if (!newBetter) {
                Microbot.log(String.format("[Evade] Keeping (%d,%d) (pass %d%s) - new pick (%d,%d) isn't better",
                        lastEvade[0], lastEvade[1], old[0], old[0] < 1 ? ", hit x" + old[2] : "", best[0], best[1]));
                best = lastEvade;
                bestMargin = old[0];
                bestGap = old[1];
                bestHits = old[2];
            }
        }
        if (bestMargin < 1) hitExpectedUntil = now + HIT_EXPECTED_MS;
        dropLane();
        evadeTarget = best;
        if (lastEvade == null || lastEvade[0] != best[0] || lastEvade[1] != best[1]) lastEvadeTick = gameTickCount;
        lastEvade = best;
        evadeExpectsHit = bestMargin < 1;
        evadeExpectedHits = bestMargin < 1 ? bestHits : 0;
        // Crossing hazard tiles on purpose (a break-out or a plan across a block): the hazard flee mustn't cut it short.
        evadeCrossesHazard = breakout || hazardLandings(px, py, best[0], best[1]) > 0;
        evadePickTick = gameTickCount;
        lastWalkTargetX = best[0];
        lastWalkTargetY = best[1];
        Microbot.log(String.format("[Evade] Both lane directions cut off by tornadoes - running to (%d,%d) (closest pass %d, end gap %d%s) %s",
                best[0], best[1], bestMargin, bestGap,
                (bestMargin >= 1 ? "" : String.format(", WILL BE HIT x%d", bestHits)) + (noWayOut ? ", no clean run onward" : ""),
                vitalsTag()));
        walkExactTile(best[0], best[1]);
        return true;
    }

    /**
     * The best clean evade target (same ranking as {@link #evadeTornadoes}) from which a clean follow-up run exists,
     * judged against where the tornadoes will be when we arrive. Null if no clean target has one.
     */
    private int[] bestCleanEvadeWithFollowUp(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        boolean[][] safe = buildSafeGrid(bossX, bossY);
        List<int[]> clean = new ArrayList<>(); // {x, y, margin, endGap, nearBoss, dist}
        int[] pred = predictedStart(px, py);
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!safe[x - arenaMinX][y - arenaMinY]) continue;
                if (!gridPathSafe(safe, px, py, x, y) || !gridPathSafe(safe, pred[0], pred[1], x, y)) continue;
                int[] sim = simulateRun(px, py, new int[]{x, y}, tornadoes);
                if (sim[0] < 1) continue;
                clean.add(new int[]{x, y, sim[0], sim[1], isUnderBoss(x, y, bossX, bossY, 1) ? 1 : 0,
                        Math.max(Math.abs(x - px), Math.abs(y - py)), isBossCorridorTile(x, y, bossX, bossY) ? 1 : 0});
            }
        }
        clean.sort((a, b) -> a[6] != b[6] ? a[6] - b[6] : a[2] != b[2] ? b[2] - a[2] : a[3] != b[3] ? b[3] - a[3]
                : a[4] != b[4] ? a[4] - b[4] : a[5] - b[5]);
        for (int i = 0; i < clean.size() && i < EVADE_FOLLOW_UP_CANDIDATES; i++) {
            int[] tile = {clean.get(i)[0], clean.get(i)[1]};
            List<int[]> arrived = new ArrayList<>();
            simulateRun(px, py, tile, tornadoes, CLICK_LATENCY_TICKS, 0, arrived);
            if (hasCleanRunFrom(safe, tile, arrived, 1)) return tile;
        }
        return null;
    }

    /**
     * Among evade targets taking exactly {@code hits} tornado hits, the best (not beside the boss, then shortest)
     * that leaves a clean run onward from where the tornadoes will be on arrival. Null if none does.
     */
    private int[] bestHitEvadeWithFollowUp(int px, int py, int bossX, int bossY, List<int[]> tornadoes, int hits) {
        boolean[][] safe = buildSafeGrid(bossX, bossY);
        List<int[]> cands = new ArrayList<>(); // {x, y, nearBoss, dist}
        int[] pred = predictedStart(px, py);
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!safe[x - arenaMinX][y - arenaMinY] || !gridPathSafe(safe, px, py, x, y)
                        || !gridPathSafe(safe, pred[0], pred[1], x, y)) continue;
                if (simulateRun(px, py, new int[]{x, y}, tornadoes)[2] != hits) continue;
                cands.add(new int[]{x, y, isUnderBoss(x, y, bossX, bossY, 1) ? 1 : 0, Math.max(Math.abs(x - px), Math.abs(y - py)),
                        isBossCorridorTile(x, y, bossX, bossY) ? 1 : 0});
            }
        }
        cands.sort((a, b) -> a[4] != b[4] ? a[4] - b[4] : a[2] != b[2] ? a[2] - b[2] : a[3] - b[3]);
        for (int i = 0; i < cands.size() && i < EVADE_FOLLOW_UP_CANDIDATES; i++) {
            int[] tile = {cands.get(i)[0], cands.get(i)[1]};
            List<int[]> arrived = new ArrayList<>();
            simulateRun(px, py, tile, tornadoes, CLICK_LATENCY_TICKS, 0, arrived);
            if (hasCleanRunFrom(safe, tile, arrived, 1)) return tile;
        }
        return null;
    }

    /**
     * A run to a safe tile that may cross hazard tiles (never the Hunllef), costing fewer than {@code evadeHits}
     * tornado hits' worth: BREAKOUT_HIT_COST per tornado hit + 1 per hazard landing. Prefers a target with a clean
     * run onward. Null if nothing beats the evade.
     */
    private int[] pickBreakout(int px, int py, int bossX, int bossY, List<int[]> tornadoes, int evadeHits) {
        boolean[][] safe = buildSafeGrid(bossX, bossY);
        int[] pred = predictedStart(px, py);
        int[] best = null;
        int bestCost = Integer.MAX_VALUE;
        boolean bestExit = false;
        int limit = evadeHits * BREAKOUT_HIT_COST; // must cost strictly less than taking the evade
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!safe[x - arenaMinX][y - arenaMinY]) continue;
                if (gridPathSafe(safe, px, py, x, y)) continue; // hazard-free targets were the evade's to pick
                if (!clearOfBossFromBoth(px, py, pred, x, y, bossX, bossY)) continue;
                int landings = hazardLandings(px, py, x, y);
                if (landings == 0 || landings * BREAKOUT_HAZARD_COST >= limit) continue; // no hazard landing: not a break-out
                // Same hit count as the evade (incl. the ticks after arrival): counting only to arrival once rated
                // a run back past the tornadoes x2 when it was x5.
                int[] sim = simulateRun(px, py, new int[]{x, y}, tornadoes);
                int cost = sim[2] * BREAKOUT_HIT_COST + landings * BREAKOUT_HAZARD_COST;
                if (cost >= limit || cost > bestCost) continue;
                List<int[]> arrived = new ArrayList<>();
                simulateRun(px, py, new int[]{x, y}, tornadoes, CLICK_LATENCY_TICKS, 0, arrived);
                boolean exit = hasCleanRunFrom(safe, new int[]{x, y}, arrived, 1);
                if (cost < bestCost || (exit && !bestExit)) {
                    best = new int[]{x, y};
                    bestCost = cost;
                    bestExit = exit;
                }
            }
        }
        return best;
    }

    /**
     * Tornado hits an evade must expect before a break-out is tried. Two hazard landings to dodge two hits cost 46 HP
     * (prayer was down), so it takes three - two only while healthy with a protection prayer up.
     */
    private int breakoutMinHits() {
        boolean healthy = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) >= BREAKOUT_HEALTHY_HP
                && (Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC) || Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE));
        return healthy ? BREAKOUT_MIN_HITS_HEALTHY : BREAKOUT_MIN_HITS;
    }

    /** A two-move plan: run to {@code first}, then on to {@code second} (null: no safe run on from there). */
    private static final class Plan {
        int[] first, second;
        int cost, hits1, hits2, endGap, landings1, landings2;
    }

    /**
     * Best two-move plan from (px,py): every safe straight run to a first tile (from where we stand and from where the
     * click takes effect), then the best safe straight run on from it, with the tornadoes simulated through both.
     * Cost: tornado hits dominate (both legs), then a dead end, then the gap at the end of the second run, then room
     * on the way. Ending with every tornado behind us on one side scores best on its own (bigger end gap).
     */
    private Plan planTwoMoves(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        if (tornadoes.isEmpty()) return null;
        boolean[][] safe = buildSafeGrid(bossX, bossY);
        int[] pred = predictedStart(px, py);
        int gapNow = minTornadoDistance(px, py, tornadoes);
        // First runs to any safe tile, across hazards too (never under the Hunllef), ranked on their own expected
        // damage; only the most promising get a full second-move search (client thread).
        List<int[]> firsts = new ArrayList<>();
        List<int[]> firstSims = new ArrayList<>(); // {margin, gap, hits, damage cost}
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!safe[x - arenaMinX][y - arenaMinY]) continue;
                if (!clearOfBossFromBoth(px, py, pred, x, y, bossX, bossY)) continue;
                int[] sim = simulateRun(px, py, new int[]{x, y}, tornadoes, CLICK_LATENCY_TICKS, 0);
                if (gapNow <= CLOSE_TORNADO_TILES && sim[2] > 0
                        && Math.max(Math.abs(x - px), Math.abs(y - py)) < MIN_RUN_NEAR_TORNADO) continue;
                int landings = hazardLandings(px, py, x, y);
                firsts.add(new int[]{x, y});
                firstSims.add(new int[]{sim[0], sim[1], sim[2], 100 * sim[2] + PLAN_HAZARD_LANDING_COST * landings});
            }
        }
        if (firsts.isEmpty()) return null;
        Integer[] order = new Integer[firsts.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int[] sa = firstSims.get(a), sb = firstSims.get(b);
            if (sa[3] != sb[3]) return sa[3] - sb[3];
            return Math.min(sb[0], 8) - Math.min(sa[0], 8);
        });
        Plan best = null;
        for (int i = 0; i < Math.min(PLAN_FIRST_CANDIDATES, order.length); i++) {
            Plan p = planFrom(px, py, firsts.get(order[i]), bossX, bossY, tornadoes, safe);
            if (p != null && (best == null || p.cost < best.cost)) best = p;
        }
        return best;
    }

    /**
     * The best plan whose first run goes to {@code first}; null if it isn't a safe tile or the run goes under the
     * Hunllef. Both runs may cross hazards: each tick ending on one costs PLAN_HAZARD_LANDING_COST (a tornado hit 100).
     */
    private Plan planFrom(int px, int py, int[] first, int bossX, int bossY, List<int[]> tornadoes, boolean[][] safe) {
        int fx = first[0] - arenaMinX, fy = first[1] - arenaMinY;
        if (fx < 0 || fy < 0 || fx >= safe.length || fy >= safe[0].length || !safe[fx][fy]) return null;
        if (walkLineCrossesBoss(px, py, first[0], first[1], bossX, bossY)) return null;
        List<int[]> atArrival = new ArrayList<>();
        int[] sim1 = simulateRun(px, py, first, tornadoes, CLICK_LATENCY_TICKS, 0, atArrival);
        Plan plan = new Plan();
        plan.first = first;
        plan.hits1 = sim1[2];
        plan.landings1 = hazardLandings(px, py, first[0], first[1]);
        int bestLeg2 = Integer.MAX_VALUE;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (Math.max(Math.abs(x - first[0]), Math.abs(y - first[1])) < 2) continue;
                if (!safe[x - arenaMinX][y - arenaMinY]) continue;
                if (walkLineCrossesBoss(first[0], first[1], x, y, bossX, bossY)) continue;
                // The second click is sent as the first run ends (it's known in advance): 1 tick, not the full latency.
                int[] sim2 = simulateRun(first[0], first[1], new int[]{x, y}, atArrival, PLAN_SECOND_LATENCY_TICKS, SIM_TICKS_AFTER_ARRIVAL);
                int landings2 = hazardLandings(first[0], first[1], x, y);
                int leg2 = 100 * sim2[2] + PLAN_HAZARD_LANDING_COST * landings2 - 5 * Math.min(sim2[1], 6) - Math.min(sim2[0], 4)
                        + (isBossCorridorTile(x, y, bossX, bossY) ? 30 : 0);
                if (leg2 < bestLeg2) {
                    bestLeg2 = leg2;
                    plan.second = new int[]{x, y};
                    plan.hits2 = sim2[2];
                    plan.landings2 = landings2;
                    plan.endGap = Math.min(sim2[1], 99);
                }
            }
        }
        if (plan.second == null) bestLeg2 = PLAN_DEAD_END_COST; // nowhere to run on to: a pocket
        plan.cost = 100 * plan.hits1 + PLAN_HAZARD_LANDING_COST * plan.landings1 - 2 * Math.min(sim1[0], 4) + bestLeg2;
        return plan;
    }

    /** One floor read per arena tile; nested searches run on this grid (they're on the client thread). */
    private boolean[][] buildSafeGrid(int bossX, int bossY) {
        boolean[][] safe = new boolean[arenaMaxX - arenaMinX + 1][arenaMaxY - arenaMinY + 1];
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                safe[x - arenaMinX][y - arenaMinY] = isTileFullySafe(x, y, bossX, bossY);
            }
        }
        return safe;
    }

    /** True if some safe tile 2+ away can be run to from 'from' with the tornadoes never closer than minMargin. */
    private boolean hasCleanRunFrom(boolean[][] safe, int[] from, List<int[]> tornadoes, int minMargin) {
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (Math.max(Math.abs(x - from[0]), Math.abs(y - from[1])) < 2) continue;
                if (!safe[x - arenaMinX][y - arenaMinY]) continue;
                if (!gridPathSafe(safe, from[0], from[1], x, y)) continue;
                if (simulateRun(from[0], from[1], new int[]{x, y}, tornadoes)[0] >= minMargin) return true;
            }
        }
        return false;
    }

    /** isStraightPathFullySafe on a precomputed safe grid (same straight-then-diagonal walk). */
    private boolean gridPathSafe(boolean[][] safe, int fx, int fy, int tx, int ty) {
        int[] pos = {fx, fy};
        while (pos[0] != tx || pos[1] != ty) {
            stepToward(pos, tx, ty);
            int gx = pos[0] - arenaMinX, gy = pos[1] - arenaMinY;
            if (gx < 0 || gy < 0 || gx >= safe.length || gy >= safe[0].length || !safe[gx][gy]) return false;
        }
        return true;
    }

    /**
     * Gets off a hazard fast: picks the closest fully safe tile (Chebyshev = run time), breaking ties by the most
     * straight-line one (smallest min(|dx|,|dy|), i.e. sharing X or Y with us as nearly as possible), then by the
     * fewest hazard tiles on the walk there. Tiles on/next to a tornado are skipped when any other option exists.
     * The tile is clicked ONCE and committed: while it stays safe we keep walking to it, re-clicking only if the
     * walk dropped - re-planning every loop was producing 1-tile steps. Client thread.
     *
     * @return true if a flee walk is under way
     */
    private boolean fleeToNearestSafeTile(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        if (!arenaBoundsKnown) return false;

        // Keep the committed target while it stays safe. A tornado closing on it allows a re-pick, but at most
        // once per tick: re-picking every loop pass clicked three targets in one tick.
        if (fleeTarget != null && (px != fleeTarget[0] || py != fleeTarget[1])
                && isTileFullySafe(fleeTarget[0], fleeTarget[1], bossX, bossY)
                // A target the between-phase flee picked ignoring tornadoes (usually a 1-tile hop) isn't kept once
                // they're up: the dodge picks its own.
                && (tornadoes.isEmpty() || (fleePickedWithTornadoes && (fleePickTick == gameTickCount
                    || minTornadoDistance(fleeTarget[0], fleeTarget[1], tornadoes) > 1)))) {
            if (!isAlreadyWalkingTo(fleeTarget[0], fleeTarget[1]) && now - fleeClickAt >= FLEE_RECLICK_MS) {
                fleeClickAt = now;
                walkExactTile(fleeTarget[0], fleeTarget[1]);
            }
            return true;
        }

        int[] best = pickFleeTile(px, py, bossX, bossY, tornadoes, true);
        if (best == null && tornadoes.isEmpty()) {
            best = pickFleeTile(px, py, bossX, bossY, tornadoes, false);
        } else if (best == null) {
            // No flee tile clears the tornadoes, so we're taking a hit on the way. The nearest tile is the worst
            // answer here: 1-tile hops never shake a tornado that moves 1 tile a tick (it sat on us for 3 ticks).
            best = pickHitMinimisingFleeTile(px, py, bossX, bossY, tornadoes);
            if (best != null) hitExpectedUntil = now + HIT_EXPECTED_MS;
        }
        // No clean safe tile close by: the cheapest path across the hazards beats a long run to a far tile (a flee
        // once ran 6 tiles at a tile behind the Hunllef and died on the detour).
        if (best == null || Math.max(Math.abs(best[0] - px), Math.abs(best[1] - py)) > FLEE_MAX_TILES) {
            fleeTarget = null;
            if (escapeAcrossDanger(px, py, bossX, bossY, tornadoes)) return true;
            if (best == null) return false;
        }
        fleeTarget = best;
        fleePickedWithTornadoes = !tornadoes.isEmpty();
        fleePickTick = gameTickCount;
        fleeClickAt = now;
        lastWalkTargetX = best[0];
        lastWalkTargetY = best[1];
        Microbot.log(String.format("[Flee] On hazard at (%d,%d) - running to nearest safe tile (%d,%d) %s", px, py, best[0], best[1], vitalsTag()));
        walkExactTile(best[0], best[1]);
        return true;
    }

    /**
     * Flee tile when every option gets hit (same ranking as the evade): fewest simulated hits, not next to the
     * Hunllef, fewest hazards on the way, then shortest. Within {@link #FLEE_MAX_TILES}; farther is escapeAcrossDanger's job.
     */
    private int[] pickHitMinimisingFleeTile(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        int[] best = null;
        int bestHits = Integer.MAX_VALUE, bestHazards = Integer.MAX_VALUE, bestDist = Integer.MAX_VALUE;
        boolean bestNearBoss = true;
        for (int x = Math.max(arenaMinX, px - FLEE_MAX_TILES); x <= Math.min(arenaMaxX, px + FLEE_MAX_TILES); x++) {
            for (int y = Math.max(arenaMinY, py - FLEE_MAX_TILES); y <= Math.min(arenaMaxY, py + FLEE_MAX_TILES); y++) {
                if (x == px && y == py) continue;
                if (!isTileFullySafe(x, y, bossX, bossY)) continue;
                if (walkLineCrossesBoss(px, py, x, y, bossX, bossY)) continue;
                int hits = simulateRun(px, py, new int[]{x, y}, tornadoes)[2];
                boolean nearBoss = isUnderBoss(x, y, bossX, bossY, 1);
                int hazards = hazardLandings(px, py, x, y);
                int dist = Math.max(Math.abs(x - px), Math.abs(y - py));
                boolean better = best == null
                        || (hits != bestHits ? hits < bestHits
                        : nearBoss != bestNearBoss ? !nearBoss
                        : hazards != bestHazards ? hazards < bestHazards
                        : dist < bestDist);
                if (better) {
                    best = new int[]{x, y};
                    bestHits = hits;
                    bestNearBoss = nearBoss;
                    bestHazards = hazards;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    private int[] pickFleeTile(int px, int py, int bossX, int bossY, List<int[]> tornadoes, boolean avoidTornadoes) {
        if (avoidTornadoes && !tornadoes.isEmpty()) {
            int[] onward = pickFleeTileWithRunOnward(px, py, bossX, bossY, tornadoes);
            if (onward != null) return onward;
        }
        int[] best = null;
        int[] pred = predictedStart(px, py);
        int bestLandings = Integer.MAX_VALUE, bestDist = Integer.MAX_VALUE, bestOffAxis = Integer.MAX_VALUE,
                bestHazards = Integer.MAX_VALUE;
        boolean bestEdge = true;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!isTileFullySafe(x, y, bossX, bossY)) continue;
                if (avoidTornadoes && !tornadoes.isEmpty() && minTornadoDistance(x, y, tornadoes) <= 1) continue;
                // The game can't walk through the Hunllef: a straight line across it is really a long detour.
                if (!clearOfBossFromBoth(px, py, pred, x, y, bossX, bossY)) continue;
                int adx = Math.abs(x - px), ady = Math.abs(y - py);
                int dist = Math.max(adx, ady);
                int offAxis = Math.min(adx, ady);
                // Floor damage lands on the tiles where a tick ends: fewest of those on hazard first (closest-first
                // fled 4 tiles across the hazard for 12-14 damage a tick), then the closest, straightest tile.
                int landings = hazardLandings(px, py, x, y);
                int hazards = hazardsOnWalkLine(px, py, x, y, bossX, bossY);
                // A tile at the edge of the safe area is the next to burn when the floor pattern spreads: three
                // nearest-tile flees in a row landed on tiles that lit up as we arrived (94 damage).
                boolean edge = onSafeAreaEdge(x, y);
                boolean better = best == null
                        || (landings != bestLandings ? landings < bestLandings
                        : edge != bestEdge ? !edge
                        : dist != bestDist ? dist < bestDist
                        : offAxis != bestOffAxis ? offAxis < bestOffAxis
                        : hazards < bestHazards);
                if (!better) continue;
                // The nearest safe tile is worthless if the run there ends a tick on a tornado: a flee once
                // headed 2 tiles straight at one and the player stood in it on the hazard (65 damage in 3 ticks).
                if (avoidTornadoes && !tornadoes.isEmpty() && simulateRun(px, py, new int[]{x, y}, tornadoes)[0] < 1) continue;
                best = new int[]{x, y};
                bestLandings = landings;
                bestEdge = edge;
                bestDist = dist;
                bestOffAxis = offAxis;
                bestHazards = hazards;
            }
        }
        return best;
    }

    /** Hazard tiles we'd end a tick on running (runSpeed tiles a tick) the game's walk from (fx,fy) to (tx,ty). */
    /**
     * Tornado-phase flee: within {@link #FLEE_MAX_TILES}, fewest hazard landings, then a target that leaves a clean
     * run onward (from where the tornadoes will be on arrival), then the most room from them on arrival, then the
     * shortest. Nearest-first made 1-tile hops into dead ends: 8 of 20 flees went 1 tile, one into the strip behind
     * the Hunllef where the tornadoes caught us next tick. Null if no flee here passes the tornadoes cleanly.
     */
    private int[] pickFleeTileWithRunOnward(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        boolean[][] safe = buildSafeGrid(bossX, bossY);
        int[] pred = predictedStart(px, py);
        int[] best = null;
        int bestLandings = Integer.MAX_VALUE, bestGap = -1, bestDist = Integer.MAX_VALUE;
        boolean bestExit = false, bestEdge = true;
        for (int x = Math.max(arenaMinX, px - FLEE_MAX_TILES); x <= Math.min(arenaMaxX, px + FLEE_MAX_TILES); x++) {
            for (int y = Math.max(arenaMinY, py - FLEE_MAX_TILES); y <= Math.min(arenaMaxY, py + FLEE_MAX_TILES); y++) {
                if (x == px && y == py) continue;
                if (!safe[x - arenaMinX][y - arenaMinY]) continue;
                if (minTornadoDistance(x, y, tornadoes) <= 1) continue;
                if (!clearOfBossFromBoth(px, py, pred, x, y, bossX, bossY)) continue;
                int landings = hazardLandings(px, py, x, y);
                if (landings > bestLandings) continue;
                List<int[]> arrived = new ArrayList<>();
                int[] sim = simulateRun(px, py, new int[]{x, y}, tornadoes, CLICK_LATENCY_TICKS, 0, arrived);
                if (sim[0] < 1) continue;
                boolean exit = hasCleanRunFrom(safe, new int[]{x, y}, arrived, 1);
                int dist = Math.max(Math.abs(x - px), Math.abs(y - py));
                boolean edge = onSafeAreaEdge(x, y);
                boolean better = best == null
                        || (landings != bestLandings ? landings < bestLandings
                        : exit != bestExit ? exit
                        : edge != bestEdge ? !edge
                        : sim[1] != bestGap ? sim[1] > bestGap
                        : dist < bestDist);
                if (!better) continue;
                best = new int[]{x, y};
                bestLandings = landings;
                bestExit = exit;
                bestEdge = edge;
                bestGap = sim[1];
                bestDist = dist;
            }
        }
        return best;
    }

    /** True if a floor tile next to (x,y) is a hazard or not floor (the arena wall doesn't count). Client thread. */
    private boolean onSafeAreaEdge(int x, int y) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (!inArena(nx, ny)) continue;
                if (!isSafeFloor(nx, ny) || isOnDangerousTile(nx, ny)) return true;
            }
        }
        return false;
    }

    private int hazardLandings(int fx, int fy, int tx, int ty) {
        int n = 0, step = 0;
        int[] pos = {fx, fy};
        while (pos[0] != tx || pos[1] != ty) {
            stepToward(pos, tx, ty);
            step++;
            boolean landing = step % runSpeed == 0 || (pos[0] == tx && pos[1] == ty);
            if (landing && isOnDangerousTile(pos[0], pos[1])) n++;
        }
        return n;
    }

    /** True if (x,y) is on our walk model's route from (fx,fy) to (tx,ty), ends included. */
    private static boolean onWalkRoute(int fx, int fy, int tx, int ty, int x, int y) {
        int[] pos = {fx, fy};
        if (x == fx && y == fy) return true;
        while (pos[0] != tx || pos[1] != ty) {
            stepToward(pos, tx, ty);
            if (pos[0] == x && pos[1] == y) return true;
        }
        return false;
    }

    /** True if the game's walk from (fx,fy) to (tx,ty) would pass through the Hunllef's footprint. */
    private boolean walkLineCrossesBoss(int fx, int fy, int tx, int ty, int bossX, int bossY) {
        int[] pos = {fx, fy};
        while (pos[0] != tx || pos[1] != ty) {
            stepToward(pos, tx, ty);
            if (isUnderBoss(pos[0], pos[1], bossX, bossY, 0)) return true;
        }
        return false;
    }

    private String vitalsTag() {
        return String.format("hp=%d run=%d%%%s", Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS),
                Microbot.getClient().getEnergy() / 100, Rs2Player.isRunEnabled() ? "" : " (run off)");
    }

    /** Number of unsafe tiles the game's walk from (fx,fy) to (tx,ty) crosses (target excluded). */
    private int hazardsOnWalkLine(int fx, int fy, int tx, int ty, int bossX, int bossY) {
        int n = 0;
        int cx = fx, cy = fy;
        while (true) {
            int rx = tx - cx, ry = ty - cy;
            if (Math.abs(rx) > Math.abs(ry)) {
                cx += Integer.signum(rx);
            } else if (Math.abs(ry) > Math.abs(rx)) {
                cy += Integer.signum(ry);
            } else {
                cx += Integer.signum(rx);
                cy += Integer.signum(ry);
            }
            if (cx == tx && cy == ty) return n;
            if (!isTileFullySafe(cx, cy, bossX, bossY)) n++;
        }
    }

    /** True if we reach lane tile {@code t} ({@code pathTiles} steps away, running 2/tick) before any tornado. */
    private boolean isEnterableBeforeTornadoes(int[] t, int pathTiles, List<int[]> tornadoes) {
        if (tornadoes.isEmpty()) return true;
        return minTornadoDistance(t[0], t[1], tornadoes) > (pathTiles + runSpeed - 1) / runSpeed + 1;
    }

    private void dropLane() {
        plannedRunEnd = null;
        safeLaneTileA = null;
        safeLaneTileB = null;
        currentShuttleTarget = null;
        currentLaneTiles = new ArrayList<>();
        transitionWaypoints = new ArrayList<>();
        transitionEntryTile = null;
        inTransition = false;
    }

    /**
     * Last resort when no safe tile can be reached without crossing hazards: run the cheapest way out. Dijkstra
     * (8-directional, like the game's walk) over every arena floor tile, where entering a tile costs 1 if safe,
     * {@link #ESCAPE_COST_WARNING} on a warning tile (no damage for ~3 ticks, so cross these first),
     * {@link #ESCAPE_COST_DAMAGE} on an active damage tile, {@link #ESCAPE_COST_BOSS} under the Hunllef, plus a
     * penalty on/next to a tornado. Targets safe tiles on a lane of at least {@link #ESCAPE_MIN_LANE_TILES}
     * (somewhere we can dodge from), falling back to any safe tile. Walks one leg at a time: the farthest path
     * tile whose real walk line stays on the planned path. Client thread.
     *
     * @return true if an escape walk is under way
     */
    private boolean escapeAcrossDanger(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        if (!arenaBoundsKnown) return false;

        Set<Integer> targets = new HashSet<>();
        List<SafeSegment> segments = findAllSafeSegments(bossX, bossY);
        // Aim for a full-length lane; settle for a short one only if the floor leaves nothing longer.
        for (int minTiles : new int[]{MIN_NEW_LANE_TILES, ESCAPE_MIN_LANE_TILES}) {
            for (SafeSegment seg : segments) {
                if (seg.dist + 1 < minTiles) continue;
                for (int[] t : getSegmentTiles(seg)) targets.add((t[0] << 8) | t[1]);
            }
            if (!targets.isEmpty()) break;
        }
        if (targets.isEmpty()) {
            for (int x = arenaMinX; x <= arenaMaxX; x++) {
                for (int y = arenaMinY; y <= arenaMaxY; y++) {
                    if (isTileFullySafe(x, y, bossX, bossY)) targets.add((x << 8) | y);
                }
            }
        }
        int start = (px << 8) | py;
        if (targets.isEmpty() || targets.contains(start)) return false;

        Map<Integer, Integer> cost = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        PriorityQueue<int[]> open = new PriorityQueue<>(Comparator.comparingInt(e -> e[1]));
        cost.put(start, 0);
        open.add(new int[]{start, 0});
        int goal = -1;

        while (!open.isEmpty()) {
            int[] cur = open.poll();
            int node = cur[0];
            if (cur[1] > cost.get(node)) continue; // stale queue entry
            if (targets.contains(node)) { goal = node; break; }
            int cx = node >> 8, cy = node & 0xFF;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    int step = escapeStepCost(nx, ny, bossX, bossY, tornadoes);
                    if (step < 0) continue;
                    int next = (nx << 8) | ny;
                    int nc = cur[1] + step;
                    Integer known = cost.get(next);
                    if (known == null || nc < known) {
                        cost.put(next, nc);
                        cameFrom.put(next, node);
                        open.add(new int[]{next, nc});
                    }
                }
            }
        }
        if (goal < 0) return false;

        List<int[]> path = new ArrayList<>();
        for (int n = goal; n != start; n = cameFrom.get(n)) path.add(new int[]{n >> 8, n & 0xFF});
        path.add(new int[]{px, py});
        Collections.reverse(path);

        // Farthest path tile whose actual walk line (major axis straight, then diagonal) costs no more than the
        // planned path to it. Requiring the line to match the path tile-for-tile only ever accepted 1-tile legs,
        // since Dijkstra's tie-broken zigzags rarely coincide with the game's walk.
        int[] leg = path.get(1);
        for (int i = path.size() - 1; i >= 1; i--) {
            int[] t = path.get(i);
            int lineCost = walkLineCost(px, py, t[0], t[1], bossX, bossY, tornadoes);
            if (lineCost >= 0 && lineCost <= cost.get((t[0] << 8) | t[1])) {
                leg = t;
                break;
            }
        }

        if (!isAlreadyWalkingTo(leg[0], leg[1])) {
            lastWalkTargetX = leg[0];
            lastWalkTargetY = leg[1];
            Microbot.log(String.format("[Escape] No safe route - crossing hazards to (%d,%d) (cost %d) via (%d,%d)",
                    goal >> 8, goal & 0xFF, cost.get(goal), leg[0], leg[1]));
            walkExactTile(leg[0], leg[1]);
        }
        return true;
    }

    /** Cost of stepping onto a tile for {@link #escapeAcrossDanger}, or -1 if it isn't walkable arena floor. */
    private int escapeStepCost(int x, int y, int bossX, int bossY, List<int[]> tornadoes) {
        if (!inArena(x, y)) return -1;
        boolean danger = isOnDangerousTile(x, y);
        if (!danger && !isSafeFloor(x, y)) return -1;
        int c = 1;
        if (danger) c = isWarningTile(x, y) ? ESCAPE_COST_WARNING : ESCAPE_COST_DAMAGE;
        if (isUnderBoss(x, y, bossX, bossY, 0)) c += ESCAPE_COST_BOSS;
        int gap = minTornadoDistance(x, y, tornadoes);
        if (gap == 0) c += ESCAPE_COST_TORNADO;
        else if (gap == 1) c += ESCAPE_COST_TORNADO / 4;
        return c;
    }

    /** True if the tile's hazard is still in its warning stage (not yet damaging). Client thread. */
    private boolean isWarningTile(int x, int y) {
        if (x < 0 || y < 0 || x >= SCENE_SIZE || y >= SCENE_SIZE) return false;
        Tile tile = Microbot.getClient().getScene().getTiles()[Microbot.getClient().getPlane()][x][y];
        if (tile == null) return false;
        GroundObject ground = tile.getGroundObject();
        if (ground != null && WARNING_TILES.contains(getActualTileObjectId(ground))) return true;
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject go : gameObjects) {
                if (go != null && WARNING_TILES.contains(getActualTileObjectId(go))) return true;
            }
        }
        return false;
    }

    /** Escape cost of the game's walk from (fx,fy) to (tx,ty), or -1 if it crosses a non-floor tile. */
    private int walkLineCost(int fx, int fy, int tx, int ty, int bossX, int bossY, List<int[]> tornadoes) {
        int total = 0;
        int cx = fx, cy = fy;
        while (cx != tx || cy != ty) {
            int rx = tx - cx, ry = ty - cy;
            if (Math.abs(rx) > Math.abs(ry)) {
                cx += Integer.signum(rx);
            } else if (Math.abs(ry) > Math.abs(rx)) {
                cy += Integer.signum(ry);
            } else {
                cx += Integer.signum(rx);
                cy += Integer.signum(ry);
            }
            int c = escapeStepCost(cx, cy, bossX, bossY, tornadoes);
            if (c < 0) return -1;
            total += c;
        }
        return total;
    }

    /** Returns all tiles belonging to a segment. */
    private List<int[]> getSegmentTiles(SafeSegment seg) {
        List<int[]> tiles = new ArrayList<>();
        if (seg == null || seg.p1 == null || seg.p2 == null) return tiles;
        int dx = Integer.signum(seg.p2[0] - seg.p1[0]);
        int dy = Integer.signum(seg.p2[1] - seg.p1[1]);
        int cx = seg.p1[0], cy = seg.p1[1];
        while (true) {
            tiles.add(new int[]{cx, cy});
            if (cx == seg.p2[0] && cy == seg.p2[1]) break;
            cx += dx;
            cy += dy;
        }
        return tiles;
    }

    /** True if the tile is on the active safe lane. */
    private boolean isPlayerOnSafeLane(int px, int py) {
        if (currentLaneTiles == null || currentLaneTiles.isEmpty()) return false;
        for (int[] t : currentLaneTiles) {
            if (t[0] == px && t[1] == py) return true;
        }
        return false;
    }

    /** Rebuilds currentLaneTiles from safeLaneTileA and safeLaneTileB. */
    private void rebuildCurrentLaneTiles() {
        if (safeLaneTileA == null || safeLaneTileB == null) {
            currentLaneTiles = new ArrayList<>();
            return;
        }
        List<int[]> tiles = new ArrayList<>();
        int dx = Integer.signum(safeLaneTileB[0] - safeLaneTileA[0]);
        int dy = Integer.signum(safeLaneTileB[1] - safeLaneTileA[1]);
        int cx = safeLaneTileA[0], cy = safeLaneTileA[1];
        while (true) {
            tiles.add(new int[]{cx, cy});
            if (cx == safeLaneTileB[0] && cy == safeLaneTileB[1]) break;
            cx += dx;
            cy += dy;
        }
        currentLaneTiles = tiles;
    }

    /** Finds the closest fully safe tile on the active safe lane to snap back to if displaced. */
    private int[] findClosestSafeLaneTile(int px, int py, int bossX, int bossY) {
        if (safeLaneTileA == null || safeLaneTileB == null) return null;
        boolean isHorizontal = (safeLaneTileA[1] == safeLaneTileB[1]);

        // First try orthogonal projection directly onto the lane
        if (isHorizontal) {
            int laneY = safeLaneTileA[1];
            int minX = Math.min(safeLaneTileA[0], safeLaneTileB[0]);
            int maxX = Math.max(safeLaneTileA[0], safeLaneTileB[0]);
            int clampedX = Math.max(minX, Math.min(maxX, px));
            if (isTileFullySafe(clampedX, laneY, bossX, bossY)) {
                return new int[]{clampedX, laneY};
            }
        } else {
            int laneX = safeLaneTileA[0];
            int minY = Math.min(safeLaneTileA[1], safeLaneTileB[1]);
            int maxY = Math.max(safeLaneTileA[1], safeLaneTileB[1]);
            int clampedY = Math.max(minY, Math.min(maxY, py));
            if (isTileFullySafe(laneX, clampedY, bossX, bossY)) {
                return new int[]{laneX, clampedY};
            }
        }

        // Fallback: search all tiles in currentLaneTiles
        int[] bestTile = null;
        int bestDist = Integer.MAX_VALUE;
        if (currentLaneTiles != null) {
            for (int[] t : currentLaneTiles) {
                if (isTileFullySafe(t[0], t[1], bossX, bossY)) {
                    int d = Math.abs(t[0] - px) + Math.abs(t[1] - py);
                    if (d < bestDist) {
                        bestDist = d;
                        bestTile = t;
                    }
                }
            }
        }
        return bestTile;
    }

    /**
     * Recalculates the safe lane and safe transition route from the player's position.
     * Selects an entry tile matching player's X or Y coordinate and builds waypoint path.
     */
    private void recalculateSafeLaneAndRoute(int px, int py, int bossX, int bossY, List<int[]> tornadoes) {
        // Any failure below drops the lane: keeping the old (now unsafe) one left the dodge with no safe route
        // and no fallback, so it stood still on the hazard. With no lane the dodge escapes across danger instead.
        // A floor change doesn't break a lane we're running on: keep it while it's still fully safe. Re-picking
        // here once swapped a clear run north for a lane with two tornadoes between us and its far end.
        if (safeLaneTileA != null && safeLaneTileB != null && !inTransition && isPlayerOnSafeLane(px, py)
                && isSegmentFullySafe(safeLaneTileA, safeLaneTileB, bossX, bossY)) {
            return;
        }

        List<SafeSegment> allSegments = findAllSafeSegments(bossX, bossY);
        if (allSegments.isEmpty()) { dropLane(); return; }

        Map<Integer, Integer> reachable = computeReachableTiles(px, py, bossX, bossY);
        if (reachable.isEmpty()) { dropLane(); return; }

        List<SafeSegment> reachableSegments = new ArrayList<>();
        Map<SafeSegment, Integer> segmentBfsDist = new HashMap<>();

        for (SafeSegment raw : allSegments) {
            SafeSegment seg = trimToExits(raw, allSegments);
            int minD = Integer.MAX_VALUE;
            for (int[] t : getSegmentTiles(seg)) {
                int packed = (t[0] << 8) | t[1];
                if (reachable.containsKey(packed)) {
                    minD = Math.min(minD, reachable.get(packed));
                }
            }
            if (minD != Integer.MAX_VALUE) {
                reachableSegments.add(seg);
                segmentBfsDist.put(seg, minD);
            }
        }

        if (reachableSegments.isEmpty()) { dropLane(); return; }

        // Lanes shorter than MIN_NEW_LANE_TILES leave no room to outrun the tornadoes: only fall back to one when
        // the floor leaves nothing longer reachable (a short lane still beats standing on danger).
        List<SafeSegment> fullLength = new ArrayList<>();
        for (SafeSegment seg : reachableSegments) {
            if (seg.dist + 1 >= MIN_NEW_LANE_TILES) fullLength.add(seg);
        }
        if (!fullLength.isEmpty()) {
            reachableSegments = fullLength;
        } else {
            Microbot.log(String.format("[Safe Lane] No reachable lane of %d+ tiles - falling back to a shorter one", MIN_NEW_LANE_TILES));
        }

        // Score reachable segments (In Phase 3 < 333HP, prioritize proximity heavily to avoid running across dangerous floor)
        boolean isPhase3 = hunllef != null && hunllef.getHealthPercentage() > 0 && hunllef.getHealthPercentage() <= 34.0;
        int bfsWeight = isPhase3 ? 45 : 15;

        SafeSegment bestSeg = null;
        int bestScore = Integer.MIN_VALUE;

        for (SafeSegment seg : reachableSegments) {
            int bfsD = segmentBfsDist.get(seg);
            int perpDist = seg.horizontal ? Math.abs(py - seg.p1[1]) : Math.abs(px - seg.p1[0]);
            boolean onAxis = seg.horizontal ? (py == seg.p1[1]) : (px == seg.p1[0]);

            int score = (seg.dist * 100) + ((12 - seg.rimDist) * 20) - (bfsD * bfsWeight) - perpDist + (onAxis ? 100 : 0);

            // Tornadoes spawn around the Hunllef, and a lane squeezed between it and the wall has no way off in
            // the middle - three deaths came from lanes running right alongside its footprint.
            for (int[] t : getSegmentTiles(seg)) {
                int fromFootprint = Math.max(Math.abs(t[0] - bossX), Math.abs(t[1] - bossY)) - 2;
                if (fromFootprint <= 1) score -= BOSS_HUG_PENALTY;
                else if (fromFootprint == 2) score -= BOSS_NEAR_PENALTY;
            }

            // Tornado-aware: a lane we can only enter by running into the tornadoes is a death trap however long
            // it is (a floor switch once re-picked a lane behind them and routed us straight back through). Need
            // an entry tile we reach before any tornado does, and prefer lanes with room between us and them.
            if (!tornadoes.isEmpty()) {
                // Each end with a perpendicular way out is a turn we can take when tornadoes pin us there.
                if (hasPerpendicularExit(seg.p1[0], seg.p1[1], seg.horizontal, allSegments)) score += EXIT_END_BONUS;
                if (hasPerpendicularExit(seg.p2[0], seg.p2[1], seg.horizontal, allSegments)) score += EXIT_END_BONUS;
                boolean enterable = false;
                int roomiest = 0;
                for (int[] t : getSegmentTiles(seg)) {
                    int gap = minTornadoDistance(t[0], t[1], tornadoes);
                    roomiest = Math.max(roomiest, gap);
                    Integer d = reachable.get((t[0] << 8) | t[1]);
                    if (d != null && isEnterableBeforeTornadoes(t, d, tornadoes)) enterable = true;
                }
                if (!enterable) score -= 5000;
                int corridor = 0;
                for (int[] t : getSegmentTiles(seg)) {
                    if (Math.max(Math.abs(t[0] - bossX), Math.abs(t[1] - bossY)) - 2 <= 1
                            && !hasPerpendicularExit(t[0], t[1], seg.horizontal, allSegments)) corridor++;
                }
                if (corridor >= BOSS_CORRIDOR_TILES) score -= BOSS_CORRIDOR_PENALTY;
                score += Math.min(roomiest, 8) * 40;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSeg = seg;
            }
        }

        if (bestSeg == null) { dropLane(); return; }

        boolean sameLane = safeLaneTileA != null && safeLaneTileB != null && currentShuttleTarget != null
                && Arrays.equals(safeLaneTileA, bestSeg.p1) && Arrays.equals(safeLaneTileB, bestSeg.p2);
        if (sameLane && !inTransition && isPlayerOnSafeLane(px, py)) {
            return; // unchanged lane and we're on it: keep the current run direction instead of flipping it
        }

        safeLaneTileA = bestSeg.p1;
        safeLaneTileB = bestSeg.p2;
        rebuildCurrentLaneTiles();

        Microbot.log(String.format("[Safe Lane] Selected lane from (%d,%d) to (%d,%d) len=%d | Boss center=(%d,%d) footprint=x[%d,%d] y[%d,%d]",
                safeLaneTileA[0], safeLaneTileA[1], safeLaneTileB[0], safeLaneTileB[1], bestSeg.dist + 1,
                bossX, bossY, bossX - 2, bossX + 2, bossY - 2, bossY + 2));

        if (isPlayerOnSafeLane(px, py)) {
            inTransition = false;
            transitionWaypoints.clear();
            currentShuttleTarget = pickRunEnd(px, py, tornadoes);
            return;
        }

        // Point 3: Target closest reachable tile on lane matching player's X or Y coordinate
        int[] entryTile = null;
        int minSegX = Math.min(bestSeg.p1[0], bestSeg.p2[0]);
        int maxSegX = Math.max(bestSeg.p1[0], bestSeg.p2[0]);
        int minSegY = Math.min(bestSeg.p1[1], bestSeg.p2[1]);
        int maxSegY = Math.max(bestSeg.p1[1], bestSeg.p2[1]);

        if (bestSeg.horizontal) {
            int idealX = Math.max(minSegX, Math.min(maxSegX, px));
            int laneY = bestSeg.p1[1];
            int idealPacked = (idealX << 8) | laneY;
            if (reachable.containsKey(idealPacked)
                    && isEnterableBeforeTornadoes(new int[]{idealX, laneY}, reachable.get(idealPacked), tornadoes)) {
                entryTile = new int[]{idealX, laneY};
            }
        } else {
            int laneX = bestSeg.p1[0];
            int idealY = Math.max(minSegY, Math.min(maxSegY, py));
            int idealPacked = (laneX << 8) | idealY;
            if (reachable.containsKey(idealPacked)
                    && isEnterableBeforeTornadoes(new int[]{laneX, idealY}, reachable.get(idealPacked), tornadoes)) {
                entryTile = new int[]{laneX, idealY};
            }
        }

        if (entryTile == null) {
            int bestTileScore = Integer.MIN_VALUE;
            for (int[] t : getSegmentTiles(bestSeg)) {
                int packed = (t[0] << 8) | t[1];
                if (!reachable.containsKey(packed)) continue;

                int d = reachable.get(packed);
                int manhattan = Math.abs(t[0] - px) + Math.abs(t[1] - py);
                boolean matchCoord = (t[0] == px || t[1] == py);

                int tScore = (matchCoord ? 300 : 0) - (manhattan * 10) - (d * 15)
                        + (isEnterableBeforeTornadoes(t, d, tornadoes) ? 0 : -5000);
                if (tScore > bestTileScore) {
                    bestTileScore = tScore;
                    entryTile = t;
                }
            }
        }

        if (entryTile == null) {
            entryTile = bestSeg.p1;
        }

        // Point 2: Generate cardinal safe path from player to entryTile, then extract turn waypoints
        List<int[]> path = findCardinalSafePath(px, py, entryTile[0], entryTile[1], bossX, bossY);
        transitionWaypoints = extractWaypoints(path); // overlay only; walkSafeRoute re-plans each leg live
        transitionWaypointIndex = 0;
        transitionEntryTile = entryTile;
        inTransition = true;
        currentShuttleTarget = entryTile;

        Microbot.log(String.format("[Action] Transition planned to safe lane entry (%d,%d) via %d waypoints (matching coord: %s)",
                entryTile[0], entryTile[1], transitionWaypoints.size(),
                (entryTile[0] == px ? "X=" + px : (entryTile[1] == py ? "Y=" + py : "none"))));
    }

    /**
     * Evaluates dangerous floor changes and recalculates the safe lane only once when floor changes occur.
     */
    public void updateSafeLaneContinuous() {
        if (!arenaBoundsKnown || hunllef == null) return;
        if (evadeTarget != null) return; // mid-evasion: pick the next lane from where we land, not from here
        Player local = Microbot.getClient().getLocalPlayer();
        if (local == null || playerTile(local) == null) return;
        LocalPoint bossLp = hunllef.getLocalLocation();
        if (bossLp == null) return;

        int px = playerTile(local).getSceneX();
        int py = playerTile(local).getSceneY();
        int bossX = bossLp.getSceneX();
        int bossY = bossLp.getSceneY();
        List<int[]> tornadoes = tornadoSceneCoords();

        long currentFingerprint = computeDangerFloorFingerprint();
        boolean floorsChanged = (currentFingerprint != lastDangerFloorFingerprint);
        boolean laneInvalid = (safeLaneTileA == null || safeLaneTileB == null
                || !isSegmentFullySafe(safeLaneTileA, safeLaneTileB, bossX, bossY));

        if (floorsChanged || laneInvalid) {
            lastDangerFloorFingerprint = currentFingerprint;
            recalculateSafeLaneAndRoute(px, py, bossX, bossY, tornadoes);
        }
    }

    /**
     * Accurately clicks a scene tile by computing its screen polygon centroid and verifying it is inside the viewport.
     * Falls back to the top-down 2D minimap for distant or off-screen tiles, eliminating 3D camera raycast skew.
     */
    private void walkExactTile(int sceneX, int sceneY) {
        if (sceneX < 0 || sceneY < 0 || sceneX >= SCENE_SIZE || sceneY >= SCENE_SIZE) return;
        boolean tornadoesUp = tornadoThreat || (tornado != null && !tornado.isEmpty());
        // One new destination per tick during tornadoes: evade, loop, lane and flee each clicking their own target
        // in one tick left the game walking a route none of them had checked. The first click of the tick wins.
        if (tornadoesUp && walkClickTick == gameTickCount && (walkClickX != sceneX || walkClickY != sceneY)
                && earlierWalkClickLive()) {
            if (walkSkipLogTick != gameTickCount) {
                walkSkipLogTick = gameTickCount;
                Microbot.log(String.format("[Walk] Already clicked (%d,%d) this tick - not also clicking (%d,%d)",
                        walkClickX, walkClickY, sceneX, sceneY));
            }
            return;
        }
        // Last line of defence: never send a walk whose route, from where the click takes effect, steps under the
        // Hunllef (stomp). Shorten it to the last tile before the footprint, or don't click.
        Player me = Microbot.getClient().getLocalPlayer();
        if (hunllef != null && me != null && playerTile(me) != null && hunllef.getLocalLocation() != null) {
            int bx = hunllef.getLocalLocation().getSceneX(), by = hunllef.getLocalLocation().getSceneY();
            int mx = playerTile(me).getSceneX(), my = playerTile(me).getSceneY();
            int[] start = predictedStart(mx, my);
            // Already under it (or about to be): this click is the way out, don't block it.
            boolean escapingBoss = isUnderBoss(mx, my, bx, by, 0) || isUnderBoss(start[0], start[1], bx, by, 0);
            // Off a hazard, a route under the Hunllef is fine: its stomp hits once, the floor every tick we stand
            // there. Refusing that flee kept us 3 ticks on the hazard and killed us (62 HP gone).
            boolean onHazard = isOnDangerousTile(mx, my) || isOnDangerousTile(start[0], start[1]);
            if (onHazard && !escapingBoss && walkLineCrossesBoss(start[0], start[1], sceneX, sceneY, bx, by)) {
                Microbot.log(String.format("[Walk] Route from (%d,%d) to (%d,%d) goes under the Hunllef - clicking it anyway, we're on a hazard",
                        start[0], start[1], sceneX, sceneY));
            } else if (!escapingBoss && walkLineCrossesBoss(start[0], start[1], sceneX, sceneY, bx, by)) {
                int[] pos = {start[0], start[1]};
                int[] lastClear = null;
                while (pos[0] != sceneX || pos[1] != sceneY) {
                    stepToward(pos, sceneX, sceneY);
                    if (isUnderBoss(pos[0], pos[1], bx, by, 0)) break;
                    lastClear = new int[]{pos[0], pos[1]};
                }
                Microbot.log(String.format("[Walk] Route from (%d,%d) to (%d,%d) goes under the Hunllef - %s",
                        start[0], start[1], sceneX, sceneY,
                        lastClear == null ? "not clicking" : "clicking (" + lastClear[0] + "," + lastClear[1] + ") instead"));
                if (lastClear == null) return;
                sceneX = lastClear[0];
                sceneY = lastClear[1];
            }
            routeFromX = start[0];
            routeFromY = start[1];
        } else {
            routeFromX = routeFromY = -1;
        }
        // Never step back: a waypoint worked out from where we're drawn (a little behind our real tile) can be one we
        // already passed on the way to the current destination; clicking it turned us around beside the boss.
        LocalPoint curDest = Microbot.getClient().getLocalDestinationLocation();
        if (curDest != null && me != null && playerTile(me) != null) {
            int dx = curDest.getSceneX(), dy = curDest.getSceneY();
            int mx = playerTile(me).getSceneX(), my = playerTile(me).getSceneY();
            boolean behindDest = (sceneX != dx || sceneY != dy) && onWalkRoute(mx, my, dx, dy, sceneX, sceneY);
            if (behindDest && hunllef != null && hunllef.getLocalLocation() != null) {
                int bx = hunllef.getLocalLocation().getSceneX(), by = hunllef.getLocalLocation().getSceneY();
                // ...but only while the run to that destination is still a good one (safe floor, no tornado hit):
                // a nearer tile on the same line can be a deliberate stop short of a tornado.
                List<int[]> torn = tornadoSceneCoords();
                if (isTileFullySafe(dx, dy, bx, by) && isStraightPathFullySafe(mx, my, dx, dy, bx, by)
                        && (torn.isEmpty() || simulateRun(mx, my, new int[]{dx, dy}, torn)[2] == 0)) return;
            }
        }
        routeToX = sceneX;
        routeToY = sceneY;
        walkClickTick = gameTickCount;
        walkClickX = sceneX;
        walkClickY = sceneY;
        lastWalkClickAt = now;
        final int tx = sceneX, ty = sceneY;
        // A walk from a hazard tile is a flee: the hand clicks it before anything else and never drops it for age.
        LocalPoint standing = playerTile(Microbot.getClient().getLocalPlayer());
        boolean flee = standing != null && isOnDangerousTile(standing.getSceneX(), standing.getSceneY());
        hand.submit(flee ? ClickHand.Kind.FLEE : ClickHand.Kind.WALK, "walk", ClickHand.Tab.NONE, () -> clickTile(tx, ty), () -> true,
                String.format("walk (%d,%d)", tx, ty));
    }

    /**
     * The hand's walk click: the tile's screen centre (computed now - the camera may have moved since it was queued)
     * with natural mouse travel, else the minimap, else walkFastLocal. Runs on the hand's thread.
     */
    private void clickTile(int sceneX, int sceneY) {
        LocalPoint lp = LocalPoint.fromScene(sceneX, sceneY);
        if (lp == null) return;
        java.awt.Point tilePoint = tileCanvasPoint(lp);
        if (tilePoint != null) {
            // Move onto the tile first, then look again: a newer walk queued meanwhile replaces this one (a flee walk
            // decided a tick earlier was clicked anyway and took us a tile closer to a tornado stack).
            if (Microbot.naturalMouse != null) Microbot.naturalMouse.moveTo(tilePoint.x, tilePoint.y);
            if (hand.isPending("walk")) {
                hand.abortCurrent("skipped - newer walk queued");
                return;
            }
            // Aim again now: the camera can turn during the 0.3-0.4s move, and the click then walked to the tile next
            // to the one we meant (onto a hazard for 42 damage, and beside a tornado stack).
            java.awt.Point aim = tileCanvasPoint(lp);
            if (aim == null) {
                clickTileOffScreen(lp);
                return;
            }
            if (aim.distance(tilePoint) > 3 && Microbot.naturalMouse != null) Microbot.naturalMouse.moveTo(aim.x, aim.y);
            LocalPoint before = currentDestination();
            invokeWalk(aim);
            // Check where the game is taking us; one more click if it's the wrong tile.
            LocalPoint dest = awaitDestination(sceneX, sceneY, before, 300);
            if (dest != null && (dest.getSceneX() != sceneX || dest.getSceneY() != sceneY) && !hand.isPending("walk")) {
                java.awt.Point again = tileCanvasPoint(lp);
                Microbot.log(String.format("[Walk] Click landed on (%d,%d) instead of (%d,%d) - %s", dest.getSceneX(),
                        dest.getSceneY(), sceneX, sceneY, again == null ? "tile off screen, not re-clicking" : "clicking again"));
                if (again != null) {
                    if (Microbot.naturalMouse != null) Microbot.naturalMouse.moveTo(again.x, again.y);
                    invokeWalk(again);
                }
            }
            return;
        }
        clickTileOffScreen(lp);
    }

    /** Centre of the tile on the canvas, if it's inside the viewport; null otherwise. Any thread. */
    private static java.awt.Point tileCanvasPoint(LocalPoint lp) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null || poly.npoints != 4) return null;
            int cx = (poly.xpoints[0] + poly.xpoints[1] + poly.xpoints[2] + poly.xpoints[3]) / 4;
            int cy = (poly.ypoints[0] + poly.ypoints[1] + poly.ypoints[2] + poly.ypoints[3]) / 4;
            Rectangle viewport = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
                    client.getViewportWidth(), client.getViewportHeight());
            return viewport.contains(cx, cy) && poly.contains(cx, cy) ? new java.awt.Point(cx, cy) : null;
        }).orElse(null);
    }

    private static void invokeWalk(java.awt.Point p) {
        NewMenuEntry entry = new NewMenuEntry()
                .param0(p.x)
                .param1(p.y)
                .type(MenuAction.WALK)
                .identifier(0)
                .itemId(-1)
                .option("Walk here");
        Microbot.doInvoke(entry, new Rectangle(p.x - 2, p.y - 2, 4, 4));
    }

    /**
     * Waits up to maxMs for the click to show as the walk destination: returns it as soon as it is the target or has
     * changed from before the click; null if nothing changed (a re-click of the same tile returns at once).
     */
    private static LocalPoint awaitDestination(int sceneX, int sceneY, LocalPoint before, long maxMs) {
        long until = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < until) {
            LocalPoint d = currentDestination();
            if (d != null && ((d.getSceneX() == sceneX && d.getSceneY() == sceneY) || !d.equals(before))) return d;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static LocalPoint currentDestination() {
        return Microbot.getClientThread().runOnClientThreadOptional(
                () -> Microbot.getClient().getLocalDestinationLocation()).orElse(null);
    }

    private void clickTileOffScreen(LocalPoint lp) {
        // Minimap fallback: exact top-down projection without 3D perspective distortion
        net.runelite.api.Point minimapPoint = Microbot.getClientThread().runOnClientThreadOptional(() -> Rs2MiniMap.localToMinimap(lp)).orElse(null);
        if (minimapPoint != null && Rs2MiniMap.isPointInsideMinimap(minimapPoint)) {
            Microbot.getMouse().click(minimapPoint);
            return;
        }

        Rs2Walker.walkFastLocal(lp);
    }

    private long lastHoverTime = -1;
    private int lastHoverX = -1, lastHoverY = -1;

    /** Pre-hovers the mouse over a scene tile so the next click lands with 0 travel delay. */
    private void hoverSceneTile(int sceneX, int sceneY) {
        if (sceneX < 0 || sceneY < 0 || sceneX >= SCENE_SIZE || sceneY >= SCENE_SIZE) return;
        if (lastHoverX == sceneX && lastHoverY == sceneY && now - lastHoverTime < 600) return;
        lastHoverX = sceneX;
        lastHoverY = sceneY;
        lastHoverTime = now;
        // Only when the hand has nothing else to do (any other click replaces it).
        hand.submit(ClickHand.Kind.HOVER, "hover", ClickHand.Tab.NONE, () -> {
            net.runelite.api.Point canvas = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                LocalPoint lp = LocalPoint.fromScene(sceneX, sceneY);
                if (lp == null) return null;
                Polygon poly = Perspective.getCanvasTilePoly(Microbot.getClient(), lp);
                if (poly != null && poly.npoints == 4) {
                    int cx = (poly.xpoints[0] + poly.xpoints[1] + poly.xpoints[2] + poly.xpoints[3]) / 4;
                    int cy = (poly.ypoints[0] + poly.ypoints[1] + poly.ypoints[2] + poly.ypoints[3]) / 4;
                    return new net.runelite.api.Point(cx, cy);
                }
                return Perspective.localToCanvas(Microbot.getClient(), lp, Microbot.getClient().getPlane());
            }).orElse(null);
            if (canvas == null) return;
            if (Microbot.naturalMouse != null && Rs2AntibanSettings.naturalMouse) {
                Microbot.naturalMouse.moveTo(canvas.getX(), canvas.getY());
            } else {
                Microbot.getMouse().move(canvas);
            }
        }, () -> true, "hover");
    }

    private String formatTornadoes(List<int[]> tornadoes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tornadoes.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append("(").append(tornadoes.get(i)[0]).append(",").append(tornadoes.get(i)[1]).append(")");
        }
        return sb.append("]").toString();
    }

    /** Live scene coords of every tornado in range (queried fresh — the throttled list lags the kite). */
    private List<int[]> tornadoSceneCoords() {
        List<int[]> coords = new ArrayList<>();
        List<Rs2NpcModel> list = Microbot.getRs2NpcCache().query()
                .where(npc -> TORNADO_IDS.contains(npc.getId())).within(50).toList();
        for (Rs2NpcModel t : list) {
            if (t == null) continue;
            LocalPoint lp = t.getLocalLocation();
            if (lp != null) coords.add(new int[]{lp.getSceneX(), lp.getSceneY()});
        }
        return coords;
    }

    /** Minimum Chebyshev (tile) distance from a scene tile to the nearest tornado. */
    private int minTornadoDistance(int x, int y, List<int[]> tornadoes) {
        int min = Integer.MAX_VALUE;
        for (int[] t : tornadoes) {
            min = Math.min(min, Math.max(Math.abs(x - t[0]), Math.abs(y - t[1])));
        }
        return min;
    }

    private void clearDodge() {
        dodgeActive = false;
        tornadoThreat = false;
        lastDodgeTick = -1;
        currentShuttleTarget = null;
        safeLaneTileA = null;
        safeLaneTileB = null;
        loopMode = false;
        loopRingSize = -1;
        currentLaneTiles = new ArrayList<>();
        transitionWaypoints = new ArrayList<>();
        transitionWaypointIndex = 0;
        inTransition = false;
        transitionEntryTile = null;
        lastDangerFloorFingerprint = -1L;
        runAnchorX = runAnchorY = -1;
        midRunAttackTick = -1;
        fleeTarget = null;
        evadeTarget = null;
        movementCritical = false;
    }

    /**
     * Reacts to a live config change (wired from the plugin's ConfigChanged subscriber). Config is read
     * live each loop, so most options adapt on their own; this just drops any in-progress tornado kite
     * the instant dodging is switched off.
     */
    public void onConfigChanged(String key, String newValue) {
        // Runs on the client thread (ConfigChanged is posted there) — do NOT log here: Microbot.log on
        // the client thread can deadlock via the GameChatAppender. Just flip transient state.
        if ("dodgeTornadoes".equals(key) && "false".equalsIgnoreCase(newValue)) {
            clearDodge();
        }
    }

    /**
     * Resolves the live object ID for a tile object, evaluating varbit impostor transforms so that
     * dynamic state changes (such as floor warning/damage patterns) are accurately reflected.
     */
    private int getActualTileObjectId(TileObject object) {
        if (object == null) return -1;
        int id = object.getId();
        try {
            ObjectComposition comp = Microbot.getClient().getObjectDefinition(id);
            if (comp != null && comp.getImpostorIds() != null) {
                ObjectComposition impostor = comp.getImpostor();
                if (impostor != null) {
                    return impostor.getId();
                }
            }
        } catch (Exception ignored) {
        }
        return id;
    }

    /** True if the scene tile carries the safe floor ground object (evaluating impostors). Must run on the client thread. */
    private boolean isSafeFloor(int sceneX, int sceneY) {
        int plane = Microbot.getClient().getPlane();
        if (sceneX < 0 || sceneY < 0 || sceneX >= SCENE_SIZE || sceneY >= SCENE_SIZE) return false;
        Tile tile = Microbot.getClient().getScene().getTiles()[plane][sceneX][sceneY];
        if (tile == null) return false;
        GroundObject ground = tile.getGroundObject();
        if (ground == null) return false;
        int actualId = getActualTileObjectId(ground);
        return FLOOR_TILES.contains(actualId);
    }

    /** True if the scene tile carries a damaging/warning ground or game object (evaluating impostors). Client thread. */
    private boolean isOnDangerousTile(int sceneX, int sceneY) {
        int plane = Microbot.getClient().getPlane();
        if (sceneX < 0 || sceneY < 0 || sceneX >= SCENE_SIZE || sceneY >= SCENE_SIZE) return false;
        Tile tile = Microbot.getClient().getScene().getTiles()[plane][sceneX][sceneY];
        if (tile == null) return false;

        GroundObject ground = tile.getGroundObject();
        if (ground != null) {
            int actualId = getActualTileObjectId(ground);
            if (DANGEROUS_TILES.contains(actualId)) return true;
        }

        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject go : gameObjects) {
                if (go != null) {
                    int actualId = getActualTileObjectId(go);
                    if (DANGEROUS_TILES.contains(actualId)) return true;
                }
            }
        }

        DecorativeObject deco = tile.getDecorativeObject();
        if (deco != null) {
            int actualId = getActualTileObjectId(deco);
            if (DANGEROUS_TILES.contains(actualId)) return true;
        }

        WallObject wall = tile.getWallObject();
        if (wall != null) {
            int actualId = getActualTileObjectId(wall);
            if (DANGEROUS_TILES.contains(actualId)) return true;
        }

        return false;
    }

    /**
     * True if the tile carries an arena-barrier object of any type. The normal barrier (37339) is a
     * GameObject, but the Corrupted one is a GroundObject — so we must check every object layer, not just
     * getGameObjects().
     */
    private boolean tileHasBarrier(Tile tile) {
        if (tile == null) return false;
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject go : gameObjects) {
                if (go != null && BARRIER_IDS.contains(go.getId())) return true;
            }
        }
        GroundObject ground = tile.getGroundObject();
        if (ground != null && BARRIER_IDS.contains(ground.getId())) return true;
        WallObject wall = tile.getWallObject();
        if (wall != null && BARRIER_IDS.contains(wall.getId())) return true;
        DecorativeObject deco = tile.getDecorativeObject();
        return deco != null && BARRIER_IDS.contains(deco.getId());
    }

    /**
     * Derives the 12x12 arena from the 4 barriers ({@link #BARRIER_IDS}) that sit just outside it:
     * the arena is their scene bounding box shrunk by one tile on every side. Recomputed live each call
     * (not cached) and anchored to the Hunllef, because 37339 is a generic maze barrier scattered across
     * the whole gauntlet — after roaming to gather resources the scene holds other 37339s that would
     * otherwise pollute the bounds. Must run on the client thread.
     */
    private void computeArenaBounds() {
        arenaBoundsKnown = false;
        if (hunllef == null) return; // the Hunllef is our anchor for which barriers belong to this arena
        LocalPoint bossLp = hunllef.getLocalLocation();
        if (bossLp == null) return;
        int bossX = bossLp.getSceneX(), bossY = bossLp.getSceneY();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int found = 0, total = 0;
        for (Tile tile : Rs2GameObject.getTiles()) {
            if (!tileHasBarrier(tile)) continue;
            total++;
            LocalPoint lp = tile.getLocalLocation();
            if (lp == null) continue;
            int sx = lp.getSceneX(), sy = lp.getSceneY();
            // Ignore maze barriers elsewhere in the instance — only the ones around this arena count.
            if (Math.max(Math.abs(sx - bossX), Math.abs(sy - bossY)) > ARENA_BARRIER_RADIUS) continue;
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
            found++;
        }
        barriersTotal = total;
        barriersNear = found;
        if (found < 4) return; // need all 4 arena barriers before we trust the bounds
        arenaMinX = minX + 1;
        arenaMaxX = maxX - 1;
        arenaMinY = minY + 1;
        arenaMaxY = maxY - 1;
        arenaBoundsKnown = true;
    }

    /** True if the scene tile lies within the derived arena bounds. */
    private boolean inArena(int sceneX, int sceneY) {
        return arenaBoundsKnown
                && sceneX >= arenaMinX && sceneX <= arenaMaxX
                && sceneY >= arenaMinY && sceneY <= arenaMaxY;
    }

    public void checkNpc() {
        Microbot.log("Check NPC for hunleff");
        timeNpc = now;
        tornado = Microbot.getRs2NpcCache().query().where(npc -> TORNADO_IDS.contains(npc.getId())).within(50).toListOnClientThread();
        hunllef = Microbot.getRs2NpcCache().query().where(npc -> HUNLLEF_IDS.contains(npc.getId())).within(50).nearestOnClientThread();

        // A Hunllef farther than the arena can possibly be is a stale/other-instance reference, not the
        // boss we are fighting. Drop it so the fight ends instead of us clinging to the old arena.
        if (hunllef != null && tileDistanceToPlayer(hunllef) > HUNLLEF_PRESENCE_RANGE) {
            hunllef = null;
        }

        if (hunllef == null && ghState == State.FIGHTING) {
            ghState = State.IDLE;
            Rs2Prayer.disableAllPrayers();
            nextPrayer = null;
            resetBossCounter();
            clearDodge();
            safeLaneTileA = null;
            safeLaneTileB = null;
            currentShuttleTarget = null;
            currentLaneTiles = new ArrayList<>();
            return;
        } else {
            ghState = State.FIGHTING;
        }

        bossHeadIcon = hunllef.getHeadIcon();
    }


    //------------------------------------------------------------------------------------------------------------------
    //----- Events

    // Note: The below method is triggered by a subscribe on the plugin file to (GameTick event) so it happens every 600ms
    public void eventGameTick() {
        tickHappened.set(true);
        gameTickCount++; // drives the once-per-tick tornado-dodge re-plan (see handleTornadoDodge)
        if (ghState == State.FIGHTING && arenaBoundsKnown && hunllef != null) {
            updateSafeLaneContinuous();
        }
        // Tornadoes just spawned: dodge on this tick instead of waiting for the script loop to notice (it took 2 ticks
        // once, and a tornado that spawned next to us hit twice). The loop's next pass carries on from here.
        if (tornadoDodgeKick) {
            tornadoDodgeKick = false;
            if (ghState == State.FIGHTING && hunllef != null && config.dodgeTornadoes() && !config.captureManualDodge()) {
                tornado = Microbot.getRs2NpcCache().query().where(npc -> TORNADO_IDS.contains(npc.getId())).within(50).toList();
                if (!tornado.isEmpty() && lastDodgeTick != gameTickCount) {
                    now = System.currentTimeMillis();
                    hand.cancel("attack");
                    handleTornadoDodge();
                }
            }
        }
        if (loopCount > LOOPS_PER_TICK * 2) {
            tickHappened.set(true);
        } //failsafe
    }

    public void onGroundObjectSpawned(GroundObjectSpawned event) {
        if (event == null || event.getGroundObject() == null) return;
        int id = event.getGroundObject().getId();
        if (WARNING_TILES.contains(id)) {
            if (floorWarningStartTick != gameTickCount) {
                floorWarningStartTick = gameTickCount;
            }
            // No lane recalculation here: a floor switch spawns dozens of tiles per tick, and recalculating on
            // each one re-picked the lane/target dozens of times a tick. The tick + dodge loop pick up the change.
        }
    }

    public void onGroundObjectDespawned(GroundObjectDespawned event) {
        // Intentionally empty: like spawns, the tick + dodge loop recalculate once the floor change is complete.
    }


    //The below three methods are triggered by subscribed events
    public void animMagicSeen() {
        if (ghState != State.FIGHTING) return; // prep minibosses share this callback; only the boss fight matters
        if (nextPrayer == null) nextPrayer = Rs2PrayerEnum.PROTECT_MAGIC;
    }

    public void animRangeSeen() {
        if (ghState != State.FIGHTING) return;
        if (nextPrayer == null) nextPrayer = Rs2PrayerEnum.PROTECT_RANGE;
    }

    public void projMageBlastEnd() {
        magicBlastEnd = System.currentTimeMillis();
    }

    /** Client thread (AnimationChanged): no logging here. */
    public void onPlayerAnimation(int anim) {
        if (anim == -1) return;
        if (PLAYER_ATTACK_ANIMS.contains(anim)) lastAttackFiredTick = gameTickCount;
        else unknownPlayerAnim = anim;
    }

    /** The weapon is off cooldown (last shot 4+ ticks ago), so an attack click fires on the next tick. */
    private boolean weaponReady() {
        return gameTickCount - lastAttackFiredTick >= MID_RUN_ATTACK_MIN_TICKS
                && gameTickCount - lastAttackTick >= ATTACK_RECLICK_TICKS;
    }

    /** True while a mid-run/loop attack click is still waiting for its shot (hold the run for it, up to the pause). */
    private boolean holdingForAttack() {
        return midRunAttackTick >= 0 && lastAttackFiredTick < midRunAttackTick
                && gameTickCount - midRunAttackTick < MID_RUN_ATTACK_PAUSE_TICKS;
    }

    /**
     * Attack from a standstill during tornadoes (end of an evade, lane end reached) when the tornadoes are more than
     * 3 tiles off and the weapon is ready; the dodge then holds still for the shot via {@link #holdingForAttack()}.
     */
    private boolean tryStandingAttack(int px, int py, List<int[]> tornadoes, String why) {
        int nearestTornado = minTornadoDistance(px, py, tornadoes);
        if (!config.autoAttack() || hunllef == null || nearestTornado <= 3) return false;
        if (!isBowEquipped() && !isStaffEquipped()) return false;
        if (!weaponReady() || isAttackingBoss()) return false;
        // The floor lighting up under us needs the next click to be a walk: an attack sent with it cancelled the
        // escape click and we lost a tick getting off the hazard.
        if (isWarningTile(px, py) || isOnDangerousTile(px, py)) return false;
        // Same bar as a mid-run attack: after standing still for the shot, a run must still keep 2 tiles clear.
        // A gap-4 standing shot left us at gap 2 with the next run clicked too late (26 damage).
        LocalPoint bossLp = hunllef.getLocalLocation();
        if (bossLp == null) return false;
        boolean[][] safe = buildSafeGrid(bossLp.getSceneX(), bossLp.getSceneY());
        if (!hasCleanRunFrom(safe, new int[]{px, py}, tornadoesAfterPause(px, py, tornadoes), 2)) return false;
        Microbot.log(String.format("[Action] Attacking Hunllef at %s (tornado gap %d)", why, nearestTornado));
        attackHunllef();
        timeAttack = now;
        lastAttackTick = gameTickCount;
        midRunAttackTick = gameTickCount;
        return true;
    }

    /**
     * One attack check for every kind of tornado movement. With the weapon ready and {@link #TORNADO_ATTACK_INTERVAL_TICKS}
     * since the last attack, simulate standing still for the shot and then carrying on to the same target; if that
     * run is still clean (closest pass >= 1, end gap >= 2), click the Hunllef - the next tick's movement logic
     * re-clicks the run. The old gates (lane 8+, 4 tiles run, gap > 3, mid-run only) let 1 shot through in 190
     * tornado ticks; clicking the boss every 3-4s by hand gets ~3 per tornado phase.
     */
    private boolean tryTornadoAttack(int px, int py, int bossX, int bossY, List<int[]> tornadoes, int nearestTornado) {
        if (!config.autoAttack() || hunllef == null) return false;
        if (!isBowEquipped() && !isStaffEquipped()) return false;
        if (!weaponReady() || gameTickCount - lastAttackTick < TORNADO_ATTACK_INTERVAL_TICKS) return false;
        // From here on the weapon is ready: count why a shot didn't go out (once per tick) for the phase summary.
        if (inTransition) return shotSkipped("lane transition");
        if (nearestTornado <= 1) return shotSkipped("tornado 1 tile away");
        if (isWarningTile(px, py) || isOnDangerousTile(px, py)) return shotSkipped("on warning/hazard tile");
        if (isUnderBoss(px, py, bossX, bossY, 0)) return shotSkipped("under the boss");
        // An evade that already takes a hit, or crosses hazards on purpose, has no time to give.
        if (evadeTarget != null && (evadeExpectsHit || evadeCrossesHazard)) return shotSkipped("evade takes a hit/crosses hazard");
        // The attack would cancel a walk clicked this tick before it took us anywhere.
        if (walkClickTick == gameTickCount || hand.isPending("walk")) return shotSkipped("walk clicked this tick");

        int[] target = null;
        String what;
        if (evadeTarget != null) {
            target = evadeTarget;
            what = "evade";
        } else if (currentShuttleTarget != null && isPlayerOnSafeLane(px, py)) {
            target = currentShuttleTarget;
            what = "lane run";
        } else {
            LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
            if (dest != null) target = new int[]{dest.getSceneX(), dest.getSceneY()};
            what = "walk";
        }
        List<int[]> afterPause = tornadoesAfterPause(px, py, tornadoes);
        String result;
        if (target != null && (target[0] != px || target[1] != py)) {
            if (!isTileFullySafe(target[0], target[1], bossX, bossY)) return shotSkipped("target unsafe");
            int[] sim = simulateRun(px, py, target, afterPause);
            if (sim[0] < 1 || sim[1] < 2 || sim[2] > 0) return shotSkipped("run after shot not clean");
            result = String.format("run after shot: pass %d, end gap %d", sim[0], sim[1]);
        } else {
            what = "standstill";
            if (!hasCleanRunFrom(buildSafeGrid(bossX, bossY), new int[]{px, py}, afterPause, 2)) return shotSkipped("no clean run onward");
            result = "clean run onward";
        }
        phaseShots++;
        Microbot.log(String.format("[Attack] Shot during %s (tornado gap %d, %s) %s", what, nearestTornado, result, vitalsTag()));
        attackHunllef();
        timeAttack = now;
        lastAttackTick = gameTickCount;
        midRunAttackTick = gameTickCount;
        setRunAnchor(px, py);
        shotClickTick = gameTickCount;
        shotClickX = px;
        shotClickY = py;
        shotFiredLogged = false;
        shotMoveLogged = false;
        return true;
    }

    /** Counts a skipped tornado shot once per tick (for the phase summary); always false. */
    private boolean shotSkipped(String reason) {
        if (shotSkipTick == gameTickCount) return false;
        shotSkipTick = gameTickCount;
        shotSkipReasons.merge(reason, 1, Integer::sum);
        return false;
    }

    /** End of a tornado phase: what the attack check did, and how the click hand did. */
    private void logTornadoPhaseSummary() {
        StringBuilder sb = new StringBuilder();
        shotSkipReasons.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue()));
        Microbot.log(String.format("[Attack] Tornado phase: %d shot(s); weapon ready but skipped: %s", phaseShots,
                sb.length() == 0 ? "none" : sb));
        String clicks = hand.takePhaseSummary();
        if (clicks != null) Microbot.log("[Click] Tornado phase: " + clicks);
        phaseShots = 0;
        shotSkipReasons.clear();
    }

    /**
     * Measures what a tornado-phase shot really costs: ticks from the attack click to the shot, and to moving again.
     * The dodge assumes {@link #MID_RUN_ATTACK_PAUSE_TICKS} of standing still.
     */
    private void logShotTiming(int px, int py) {
        if (shotClickTick < 0) return;
        int since = gameTickCount - shotClickTick;
        if (!shotFiredLogged && lastAttackFiredTick >= shotClickTick) {
            shotFiredLogged = true;
            Microbot.log(String.format("[Attack] Shot fired %d tick(s) after the click", lastAttackFiredTick - shotClickTick));
        }
        // Only from the next tick on: the drawn position moves within the tick, before the game does.
        if (!shotMoveLogged && since >= 1 && (px != shotClickX || py != shotClickY)) {
            shotMoveLogged = true;
            Microbot.log(String.format("[Attack] Moving again %d tick(s) after the attack click%s", since,
                    lastAttackFiredTick >= shotClickTick ? "" : " (shot not fired)"));
        }
        if (since > 8) {
            if (!shotFiredLogged) Microbot.log("[Attack] Shot never fired within 8 ticks of the click");
            shotClickTick = -1;
            shotFiredLogged = shotMoveLogged = true;
        }
    }

    /** Tornado positions after they chase (px,py) for the ticks an attack click stands us still. */
    private static List<int[]> tornadoesAfterPause(int px, int py, List<int[]> tornadoes) {
        return tornadoesAfter(px, py, tornadoes, MID_RUN_ATTACK_PAUSE_TICKS);
    }

    /** Tornado positions after they chase (px,py) for {@code ticks} ticks while we stand still. */
    private static List<int[]> tornadoesAfter(int px, int py, List<int[]> tornadoes, int ticks) {
        List<int[]> afterPause = new ArrayList<>();
        for (int[] t : tornadoes) {
            int[] moved = {t[0], t[1]};
            for (int i = 0; i < ticks; i++) stepToward(moved, px, py);
            afterPause.add(moved);
        }
        return afterPause;
    }

    public void projMagicSeen() {
        nextPrayer = Rs2PrayerEnum.PROTECT_MAGIC;
        eventLog.add(String.format("[Boss] Switch animation: magic (counter at %d/%d %s)", bossStyleAttacks, BOSS_ATTACKS_PER_STYLE, styleName(bossStyle)));
    }

    public void projRangeSeen() {
        nextPrayer = Rs2PrayerEnum.PROTECT_RANGE;
        eventLog.add(String.format("[Boss] Switch animation: ranged (counter at %d/%d %s)", bossStyleAttacks, BOSS_ATTACKS_PER_STYLE, styleName(bossStyle)));
    }

    /** Client thread (ProjectileMoved, fires many times per projectile): a Hunllef attack projectile. No logging here. */
    public void onBossProjectile(Projectile p, Rs2PrayerEnum style, String what) {
        if (p == null || !countedBossProjectiles.add(p)) return;
        int landsInTicks = Math.max(0, (p.getEndCycle() - Microbot.getClient().getGameCycle()) * 20 / 600);
        countBossAttack(style, what, p.getEndCycle(), landsInTicks);
    }

    /** Client thread (NpcSpawned): tornadoes appearing = the Hunllef's tornado attack, one count per summon. */
    public void onNpcSpawned(NPC npc) {
        if (npc == null || !TORNADO_IDS.contains(npc.getId())) return;
        if (gameTickCount - lastTornadoSpawnTick <= 3) return; // the other tornadoes of the same summon
        lastTornadoSpawnTick = gameTickCount;
        tornadoDodgeKick = true;
        // After 4 attacks of a style the summon is the first attack of the next style (counting it against the old
        // style gave "5/4 - MISCOUNT" and switched protection back the wrong way).
        countBossAttack(bossStyleAttacks >= BOSS_ATTACKS_PER_STYLE ? opposite(bossStyle) : bossStyle, "tornadoes", -1, 0);
    }

    private void countBossAttack(Rs2PrayerEnum style, String what, int landsAtCycle, int landsInTicks) {
        if (style != bossStyle) {
            if (bossStyleAttacks != BOSS_ATTACKS_PER_STYLE) {
                eventLog.add(String.format("[Boss] Counter off: %s attack after %d/%d %s - resyncing", styleName(style),
                        bossStyleAttacks, BOSS_ATTACKS_PER_STYLE, styleName(bossStyle)));
            }
            bossStyle = style;
            bossStyleAttacks = 0;
            nextPrayer = style;
        }
        bossStyleAttacks++;
        bossAttacksSinceSummon = "tornadoes".equals(what) ? 0 : bossAttacksSinceSummon + 1;
        lastBossAttack = styleName(style) + " " + what + " @" + gameTickCount;
        String note = "";
        if (bossStyleAttacks == BOSS_ATTACKS_PER_STYLE) {
            // Switch once this attack has landed (switching while it flies may let it through), well before the next.
            bossSwitchAtCycle = landsAtCycle < 0 ? Microbot.getClient().getGameCycle() : landsAtCycle + 1;
            note = " - switching to " + styleName(opposite(style)) + (landsAtCycle < 0 ? " now" : " once it lands");
        } else if (bossStyleAttacks > BOSS_ATTACKS_PER_STYLE) {
            bossSwitchAtCycle = -1;
            nextPrayer = style; // we switched early: the count was off by one, follow the attacks we see
            note = " - MISCOUNT, back to " + styleName(style);
        }
        eventLog.add(String.format("[Boss] %s %s %d/%d (lands in %d tick(s))%s", styleName(style), what, bossStyleAttacks,
                BOSS_ATTACKS_PER_STYLE, landsInTicks, note));
    }

    /** Script loop: makes the predicted protection switch once the 4th attack of a style has landed. */
    private void checkBossStyleSwitch() {
        int at = bossSwitchAtCycle;
        if (at < 0 || Microbot.getClient().getGameCycle() < at) return;
        bossSwitchAtCycle = -1;
        Rs2PrayerEnum next = opposite(bossStyle);
        if (nextPrayer != next) {
            Microbot.log(String.format("[Boss] 4 %s attacks done - switching protection to %s ahead of the switch",
                    styleName(bossStyle), styleName(next)));
            nextPrayer = next;
        }
    }

    private void resetBossCounter() {
        bossStyle = Rs2PrayerEnum.PROTECT_RANGE;
        bossStyleAttacks = 0;
        bossSwitchAtCycle = -1;
        bossAttacksSinceSummon = 0;
        countedBossProjectiles.clear();
        lastBossAttack = "none";
    }

    private static Rs2PrayerEnum opposite(Rs2PrayerEnum style) {
        return style == Rs2PrayerEnum.PROTECT_MAGIC ? Rs2PrayerEnum.PROTECT_RANGE : Rs2PrayerEnum.PROTECT_MAGIC;
    }

    private static String styleName(Rs2PrayerEnum style) {
        return style == Rs2PrayerEnum.PROTECT_MAGIC ? "magic" : "ranged";
    }

    /**
     * Client thread (HitsplatApplied on us): what hit us, for the chip-damage breakdown. Facts plus a best guess at the
     * source: a tornado on our tile, else the floor we stand on, else the Hunllef. No logging here.
     */
    public void onPlayerHitsplat(Hitsplat hit) {
        if (ghState != State.FIGHTING || hit == null) return;
        LocalPoint lp = playerTile(Microbot.getClient().getLocalPlayer());
        if (lp == null) return;
        int px = lp.getSceneX(), py = lp.getSceneY();
        int onTile = 0;
        for (int[] t : tornadoSceneCoords()) if (t[0] == px && t[1] == py) onTile++;
        boolean floor = isOnDangerousTile(px, py);
        String prayer = Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC) ? "magic"
                : Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE) ? "ranged" : "NONE";
        String source = onTile > 0 ? "tornado" + (onTile > 1 ? " x" + onTile : "") : floor ? "floor" : "hunllef";
        boolean unprotected = "hunllef".equals(source) && !prayer.equals(lastBossAttack.split(" ")[0]);
        eventLog.add(String.format("[Hit] %d (%s) tick %d at (%d,%d) | guess %s | protecting %s | last boss attack %s%s",
                hit.getAmount(), hitsplatName(hit.getHitsplatType()), gameTickCount, px, py, source, prayer, lastBossAttack,
                unprotected && hit.getAmount() > 0 ? " | WRONG/NO PRAYER?" : ""));
    }

    private static String hitsplatName(int type) {
        switch (type) {
            case HitsplatID.DAMAGE_ME: return "dmg";
            case HitsplatID.BLOCK_ME: return "block";
            case HitsplatID.POISON: return "poison";
            default: return "type " + type;
        }
    }

    private void checkProtectPrayers() {
        if (nextPrayer == null) {
            logVerbose(nextPrayer + " is null on check prayers");
        }
        if (nextPrayer != null && !Rs2Prayer.isPrayerActive(nextPrayer)) {
            timePrayProtect = now;
            sendPrayerToggle(nextPrayer, true);
        }
    }

    private void checkWeapon() {
        if (bossHeadIcon == null) return;
        // Deliberately NOT gated on movement or tornadoes: wielding a weapon doesn't interrupt running,
        // so swapping while moving is safe and is required to keep DPS up (and to attack the Hunllef
        // on the move — crucial for Corrupted). The Hunllef's prayer can flip at any time, so we must
        // always be able to switch to the counter-weapon regardless of what the player is doing.
        if (bossHeadIcon == HeadIcon.MELEE) {
            handleMeleeHeadIcon();
        }
        if (bossHeadIcon == HeadIcon.RANGED) {
            handleRangedHeadIcon();
        }
        if (bossHeadIcon == HeadIcon.MAGIC) {
            handleMagicHeadIcon();
        }
    }

    public synchronized Rs2PrayerEnum getNextPrayer() {
        return nextPrayer;
    }

    //------------------------------------------------------------------------------------------------------------------
    //---Handlers

    private void handleMeleeHeadIcon() {
        if (!isStaffEquipped() && hasStaffInInventory()) {
            equipStaff();
        } else if (!isBowEquipped() && !isStaffEquipped() && hasBowInInventory()) {
            equipBow();
        }
    }

    private void handleRangedHeadIcon() {
        if (!isStaffEquipped() && hasStaffInInventory()) {
            equipStaff();
        } else if (!isHalberdEquipped() && !isStaffEquipped() && hasHalberdInInventory()) {
            equipHalberd();
        }
    }

    private void handleMagicHeadIcon() {
        if (!isBowEquipped() && hasBowInInventory()) {
            equipBow();
        } else if (!isHalberdEquipped() && !isBowEquipped() && hasHalberdInInventory()) {
            equipHalberd();
        }
    }

    private boolean hasWeaponInInventory(int[] ids) {
        for (int id : ids) {
            if (Rs2Inventory.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWeaponEquipped(int[] ids) {
        for (int id : ids) {
            if (Rs2Equipment.isWearing(id)) {
                return true;
            }
        }
        return false;
    }

    private void equipBestAvailable(int[] ids) {
        // A second equip click before the first swap shows up can land on the weapon that swap just put in the slot.
        if (gameTickCount - lastEquipTick < EQUIP_CONFIRM_TICKS) return;
        for (int id : ids) {
            if (Rs2Inventory.contains(id)) {
                Microbot.log(String.format("[Action] Switch gear: equipping weapon ID %d", id));
                final int itemId = id;
                hand.submit(ClickHand.Kind.EQUIP, "equip", ClickHand.Tab.INVENTORY, () -> Rs2Inventory.equip(itemId),
                        () -> Rs2Inventory.contains(itemId), "equip " + itemId, () -> itemCenter(null, itemId));
                lastEquipTick = gameTickCount;
                if (!tornadoThreat && (tornado == null || tornado.isEmpty()) && hasArrived(lastWalkTargetX, lastWalkTargetY)) {
                    attackNeeded.set(true);
                }
                break;
            }
        }
    }

    private boolean hasBowInInventory() {
        return hasWeaponInInventory(BOW_IDS);
    }

    private boolean hasStaffInInventory() {
        return hasWeaponInInventory(STAFF_IDS);
    }

    private boolean hasHalberdInInventory() {
        return hasWeaponInInventory(HALBERD_IDS);
    }

    private boolean isBowEquipped() {
        return isWeaponEquipped(BOW_IDS);
    }

    private boolean isStaffEquipped() {
        return isWeaponEquipped(STAFF_IDS);
    }

    private boolean isHalberdEquipped() {
        return isWeaponEquipped(HALBERD_IDS);
    }

    private void equipBow() {
        equipBestAvailable(BOW_IDS);
        timeWeapon = now;
    }

    private void equipStaff() {
        equipBestAvailable(STAFF_IDS);
        timeWeapon = now;
    }

    private void equipHalberd() {
        equipBestAvailable(HALBERD_IDS);
        timeWeapon = now;
    }

    ///  --------------------------------------------------------------------------------------------------------------
    ///  --- Prayers --------------------------------------------------------------------------------------------------
    /// ---------------------------------------------------------------------------------------------------------------

    private void checkAttackPrayers() {
        if (isBowEquipped() && (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.RIGOUR) && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.EAGLE_EYE) && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.DEAD_EYE))) {
            toggleRangeAttackPrayer();
        }
        if (isStaffEquipped() && (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.AUGURY) && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.MYSTIC_MIGHT) && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.MYSTIC_VIGOUR))) {
            toggleMagicAttackPrayer();
        }
        if ((isHalberdEquipped()) && (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PIETY)) && !(Rs2Prayer.isPrayerActive(Rs2PrayerEnum.INCREDIBLE_REFLEXES) && Rs2Prayer.isPrayerActive(Rs2PrayerEnum.ULTIMATE_STRENGTH))) {
            toggleMeleeAttackPrayer();
        }
    }

    private void toggleRangeAttackPrayer() {
        if (config.higherPrayers()) {
            sendPrayerToggle(Rs2PrayerEnum.RIGOUR, true);
        } else if (config.titansPrayers()) {
            sendPrayerToggle(Rs2PrayerEnum.DEAD_EYE, true);
        } else {
            sendPrayerToggle(Rs2PrayerEnum.EAGLE_EYE, true);
        }
        requestReattack();
    }

    private void toggleMagicAttackPrayer() {
        if (config.higherPrayers()) {
            sendPrayerToggle(Rs2PrayerEnum.AUGURY, true);
        } else if (config.titansPrayers()) {
            sendPrayerToggle(Rs2PrayerEnum.MYSTIC_VIGOUR, true);
        } else {
            sendPrayerToggle(Rs2PrayerEnum.MYSTIC_MIGHT, true);
        }
        requestReattack();
    }

    private void toggleMeleeAttackPrayer() {
        sendPrayerToggle(Rs2PrayerEnum.PIETY, true);
        requestReattack();
    }

    /**
     * Flags that an attack click is needed so we re-engage the Hunllef after a
     * gear or offensive-prayer switch instead of idling and losing DPS.
     * Skipped while moving (e.g. dodging a tornado); checkAttack() also guards on movement.
     */
    private void requestReattack() {
        if (tornadoThreat || (tornado != null && !tornado.isEmpty())) return; // NEVER attack during tornadoes
        if (hasArrived(lastWalkTargetX, lastWalkTargetY)) {
            attackNeeded.set(true);
        }
    }

    /** True if the player is currently on a safe floor tile (not on fire, not under boss, inside arena, clear of tornadoes). */
    private boolean isPlayerOnSafeTile() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            LocalPoint playerLp = playerTile(Microbot.getClient().getLocalPlayer());
            if (playerLp == null) return false;
            int px = playerLp.getSceneX(), py = playerLp.getSceneY();
            if (!inArena(px, py)) return false;
            if (!isSafeFloor(px, py)) return false;
            if (isOnDangerousTile(px, py)) return false;
            if (hunllef != null) {
                LocalPoint bossLp = hunllef.getLocalLocation();
                if (bossLp != null && isUnderBoss(px, py, bossLp.getSceneX(), bossLp.getSceneY(), 0)) {
                    return false;
                }
            }
            List<int[]> torn = tornadoSceneCoords();
            if (!torn.isEmpty() && minTornadoDistance(px, py, torn) <= 2) {
                return false;
            }
            return true;
        }).orElse(false);
    }

    /** True if the player is currently actively running/moving towards a destination. */
    private boolean isPlayerInFlight() {
        LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
        if (dest == null) return false;
        Player local = Microbot.getClient().getLocalPlayer();
        if (local == null) return false;
        LocalPoint lp = playerTile(local);
        if (lp == null) return false;
        return dest.getSceneX() != lp.getSceneX() || dest.getSceneY() != lp.getSceneY();
    }

    private void checkFood() {
        if (!config.enableFood()) return;
        if (config.eatDuringTornadoes()) {
            checkFoodTornadoPhases();
            return;
        }
        boolean tornadoesActive = tornado != null && !tornado.isEmpty();

        if (tornadoesActive) {
            // Eating doesn't stop a run, and the click hand can't drop the walk for it any more: while we're running
            // somewhere (no new walk waiting), eat below the normal threshold - earlier still if the evade we're on
            // already expects hits (~15 HP a hit, capped a fish below max so it isn't wasted).
            int hpNow = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            int runEatAt = config.lowHpEatValue();
            if (evadeTarget != null && evadeExpectsHit) {
                runEatAt = Math.min(Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS) - TOPUP_HP_MARGIN,
                        runEatAt + EAT_PER_EXPECTED_HIT * evadeExpectedHits);
            }
            if (hpNow < runEatAt && isPlayerInFlight() && !hand.isPending("walk")) {
                eatFood();
                return;
            }
            // A flee/evade or a close tornado needs the next click to be a walk; an eat now delays it 600ms. Below
            // CRITICAL_HP eat regardless - standing still or mid-flee included (the old in-flight-only rule meant a
            // player pinned at a lane end never ate and died from 59 HP without a single bite).
            // When a hit is already certain (evade/flee says so, or a hazard with a tornado at 2), the walk click we'd
            // protect saves nothing: eat at the normal threshold.
            if (Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) >= CRITICAL_HP && movementCritical
                    && now >= hitExpectedUntil) return;
        } else {
            // Outside tornadoes: normal safe tile check (below CRITICAL_HP eat on the hazard too, while fleeing it)
            if (!isPlayerOnSafeTile() && Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) >= CRITICAL_HP) return;
        }

        int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        if (currentHp <= 0) return;

        if (currentHp < config.lowHpEatValue() || currentHp < CRITICAL_HP) {
            eatFood();
        } else if (!tornadoesActive && isPlayerOnSafeTile()
                && currentHp <= Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS) - TOPUP_HP_MARGIN
                && Rs2Inventory.count("Paddlefish") > TOPUP_MIN_FISH) {
            // Top up between tornado phases: a phase that opens on 60-66 HP left a 4-tornado stack at 1 HP.
            eatFood();
        }
    }

    /**
     * "Eat/drink only during tornadoes": calm phases are for attacking. When tornadoes spawn we eat up to the top-up HP,
     * then below the eat threshold (with the same on-the-run rules as normal eating); when they despawn we top up once
     * more on a safe tile and after that only eat below CRITICAL_HP until the next tornadoes.
     */
    private void checkFoodTornadoPhases() {
        boolean tornadoesActive = tornado != null && !tornado.isEmpty();
        int hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        if (hp <= 0) return;
        int target = topUpTarget();
        if (hpTopUpPending && (hp >= target || !Rs2Inventory.contains("Paddlefish"))) {
            hpTopUpPending = false;
            Microbot.log(String.format("[Food] Topped up (hp=%d, target %d, %s)", hp, target, tornadoesActive ? "tornadoes" : "calm"));
        }
        if (tornadoesActive) {
            int eatAt = hpTopUpPending ? target : config.lowHpEatValue();
            if (!hpTopUpPending && evadeTarget != null && evadeExpectsHit) {
                eatAt = Math.min(Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS) - TOPUP_HP_MARGIN,
                        eatAt + EAT_PER_EXPECTED_HIT * evadeExpectedHits);
            }
            if (hp < eatAt && isPlayerInFlight() && !hand.isPending("walk")) {
                eatFood();
                return;
            }
            // As normal eating: don't hold a needed walk click back, unless low or a hit is coming anyway.
            if (hp >= CRITICAL_HP && movementCritical && now >= hitExpectedUntil) return;
            if (hp < eatAt || hp < CRITICAL_HP) eatFood();
        } else {
            if (hp < CRITICAL_HP) {
                eatFood(); // emergency: on a hazard too, while fleeing it
                return;
            }
            if (hpTopUpPending && isPlayerOnSafeTile()) eatFood();
        }
    }

    /** Tiles from our true tile to the nearest tornado (MAX_VALUE without tornadoes). */
    private int nearestTornadoGap() {
        if (tornado == null || tornado.isEmpty()) return Integer.MAX_VALUE;
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            LocalPoint lp = playerTile(Microbot.getClient().getLocalPlayer());
            List<int[]> torn = tornadoSceneCoords();
            return lp == null || torn.isEmpty() ? Integer.MAX_VALUE : minTornadoDistance(lp.getSceneX(), lp.getSceneY(), torn);
        }).orElse(Integer.MAX_VALUE);
    }

    private int topUpTarget() {
        return Math.min(config.tornadoTopUpHp(), Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS));
    }

    private void eatFood() {
        if (!config.enableFood()) return;
        if (now - timeEatAttempted < CD_RECENTLY) return;
        int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        // A tornado this close while we stand (or a walk is waiting) needs the next click to be the dodge walk: an eat
        // went first and we stood 2 ticks while a 4-tornado stack walked onto us. Eating on the run is fine (it doesn't
        // stop us), and below CRITICAL_HP the eat always goes.
        if (currentHp >= CRITICAL_HP && (!isPlayerInFlight() || hand.isPending("walk"))
                && nearestTornadoGap() <= CLOSE_TORNADO_TILES) return;
        // Out of food: every eat click still held walk clicks back 600ms, which slowed the final dodges.
        int fish = Rs2Inventory.count("Paddlefish");
        if (fish <= 0) {
            if (!outOfFoodLogged) Microbot.log(String.format("[Action] Out of food (Paddlefish) | hp=%d", currentHp));
            outOfFoodLogged = true;
            return;
        }
        outOfFoodLogged = false;
        // The last eat hasn't healed yet: a second click may eat a second fish for the same hit. Wait for the heal
        // (HP rises) or EAT_CONFIRM_TICKS, unless a big hit landed since.
        // An eat still waiting on the hand (or being clicked) hasn't been eaten yet: don't queue a second fish.
        if (hand.isBusyWith("eat")) return;
        // Count the confirm window from when the eat click landed (the hand clicks ~0.5-0.9s after the decision;
        // counting from the decision ate two fish at 78 and at 76 HP).
        int eatRef = Math.max(lastEatTick, hand.lastLandedTick("eat"));
        if (eatRef >= 0 && gameTickCount - eatRef < EAT_CONFIRM_TICKS
                && currentHp <= lastEatHp && currentHp > lastEatHp - EAT_BIG_HIT) return;
        Microbot.log(String.format("[Action] Eat food: Paddlefish | hp=%d/%d | fish left=%d", currentHp, config.lowHpEatValue(), fish - 1));
        hand.submit(ClickHand.Kind.EAT, "eat", ClickHand.Tab.INVENTORY, () -> Rs2Inventory.interact("Paddlefish", "Eat"),
                () -> Rs2Inventory.contains("Paddlefish"), "eat Paddlefish", () -> itemCenter("Paddlefish", -1));
        lastEatTick = gameTickCount;
        lastEatHp = currentHp;
        timeEatAttempted = now;
        lastEatClickAt = now;
        // Don't queue an attack off the back of eating while kiting — it would path us toward the boss
        // and into the tornadoes (the re-attack is handled by the dodge when it parks on a safe tile).
        if (!tornadoThreat && (tornado == null || tornado.isEmpty()) && hasArrived(lastWalkTargetX, lastWalkTargetY)) {
            attackNeeded.set(true);
        }
    }

    private void checkPrayerPotions() {
        if (!config.enableDrink()) return;
        if (config.eatDuringTornadoes()) {
            checkPrayerTornadoPhases();
            return;
        }
        boolean tornadoesActive = tornado != null && !tornado.isEmpty();

        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        if (tornadoesActive) {
            // During tornadoes: drink while in flight; when nearly out, whenever no dodge walk is waiting (an empty
            // prayer lets the Hunllef hit unprotected, far worse than a short delay).
            if (!(currentPrayer < PRAYER_EMERGENCY && !hand.isPending("walk"))
                    && (!isPlayerInFlight() || movementCritical)) return;
        } else {
            if (!isPlayerOnSafeTile() && currentPrayer >= PRAYER_EMERGENCY) return;
        }

        // Same as eating: one drink at a time, and wait for it to land and show before another.
        if (hand.isBusyWith("drink") || gameTickCount - hand.lastLandedTick("drink") < EAT_CONFIRM_TICKS) return;
        if (currentPrayer < config.prayerPotionValue() && !warnIfNoPotions()) return;
        if (currentPrayer < config.prayerPotionValue()) {
            Microbot.log(String.format("[Action] Drink potion: Egniol potion | prayer=%d/%d%s", currentPrayer, config.prayerPotionValue(),
                    currentPrayer < PRAYER_EMERGENCY ? " (emergency)" : ""));
            hand.submit(ClickHand.Kind.DRINK, "drink", ClickHand.Tab.INVENTORY, () -> Rs2Inventory.interact("Egniol potion", "Drink"),
                    () -> Rs2Inventory.contains(false, "Egniol potion"), "drink Egniol potion", () -> itemCenter("Egniol potion", -1));
            timeDrink = now;
            lastEatClickAt = now;
        }
    }

    /** True if an Egniol dose is left; logs once when they've run out. */
    private boolean warnIfNoPotions() {
        if (Rs2Inventory.contains(false, "Egniol potion")) {
            outOfPotionsLogged = false;
            return true;
        }
        if (!outOfPotionsLogged) Microbot.log(String.format("[Action] Out of Egniol potions | prayer=%d",
                Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER)));
        outOfPotionsLogged = true;
        return false;
    }

    /**
     * "Eat/drink only during tornadoes": restore prayer once per tornado phase (as soon as a dose isn't wasted) and
     * below the drink threshold, after health (no drink while still topping up or below the eat threshold). Outside
     * tornadoes only below PRAYER_EMERGENCY.
     */
    private void checkPrayerTornadoPhases() {
        boolean tornadoesActive = tornado != null && !tornado.isEmpty();
        int prayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = Microbot.getClient().getRealSkillLevel(Skill.PRAYER);
        boolean emergency = prayer < PRAYER_EMERGENCY;
        // A dose restores about 7 + 25% (like a prayer potion): drink once it all fits.
        boolean doseFits = prayer <= maxPrayer - (7 + maxPrayer / 4);
        boolean want;
        if (tornadoesActive) {
            // Nearly out: drink whenever no dodge walk is waiting, health first or not.
            if (!(emergency && !hand.isPending("walk")) && (!isPlayerInFlight() || movementCritical)) return;
            int hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            boolean foodLeft = Rs2Inventory.contains("Paddlefish");
            if (!emergency && foodLeft && (hpTopUpPending || hp < config.lowHpEatValue() || hand.isBusyWith("eat"))) return;
            want = emergency || prayer < config.prayerPotionValue() || (prayerTopUpPending && doseFits);
        } else {
            // Calm phases are for attacking, but never at the cost of running dry: drink below the normal threshold
            // (only an emergency drink before; prayer reached 9/73).
            if (!isPlayerOnSafeTile() && !emergency) return;
            want = prayer < config.prayerPotionValue();
        }
        if (!want) return;
        if (hand.isBusyWith("drink") || gameTickCount - hand.lastLandedTick("drink") < EAT_CONFIRM_TICKS) return;
        if (!warnIfNoPotions()) return;
        Microbot.log(String.format("[Action] Drink potion: Egniol potion | prayer=%d/%d%s", prayer, maxPrayer,
                emergency ? " (emergency)" : prayerTopUpPending ? " (tornado phase restore)" : ""));
        hand.submit(ClickHand.Kind.DRINK, "drink", ClickHand.Tab.INVENTORY, () -> Rs2Inventory.interact("Egniol potion", "Drink"),
                () -> Rs2Inventory.contains(false, "Egniol potion"), "drink Egniol potion", () -> itemCenter("Egniol potion", -1));
        prayerTopUpPending = false;
        timeDrink = now;
        lastEatClickAt = now;
    }

    /**
     * Fires an attack at the Hunllef partway through a lane run, to keep DPS up during the tornado phase. Only on
     * lanes longer than 10 tiles (short lanes leave no slack for the 1-tick pause), once we've run at least
     * {@link #MID_RUN_ATTACK_TILES} since the run started or we last attacked, with the weapon ready, the nearest
     * tornado more than 3 tiles away and the endpoint more than 2 tiles off (the endpoint attack covers that).
     * Ranged/magic only - the halberd would have to walk to the boss.
     *
     * @return true if the attack was clicked (the run resumes next tick)
     */
    private boolean tryMidRunAttack(int px, int py, int bossX, int bossY, int nearestTornado, int distToTarget,
                                    List<int[]> tornadoes) {
        if (!config.autoAttack() || hunllef == null || inTransition) return false;
        if (!isBowEquipped() && !isStaffEquipped()) return false;
        int laneTiles = Math.max(Math.abs(safeLaneTileA[0] - safeLaneTileB[0]), Math.abs(safeLaneTileA[1] - safeLaneTileB[1])) + 1;
        if (laneTiles < MID_RUN_ATTACK_MIN_LANE) return false;
        if (!isPlayerOnSafeLane(px, py)) return false;
        if (runAnchorX < 0) setRunAnchor(px, py);
        int ran = Math.max(Math.abs(px - runAnchorX), Math.abs(py - runAnchorY));
        if (ran < MID_RUN_ATTACK_TILES) return false;
        if (!weaponReady()) return false;
        if (nearestTornado <= 3 || distToTarget <= 2) return false;
        // The attack click stops us for about 2 ticks while the tornadoes keep chasing. A gap-5 attack once let
        // them close to gap 1 by the lane end, so only attack if the rest of the run still keeps 2 tiles clear.
        if (simulateRun(px, py, currentShuttleTarget, tornadoesAfterPause(px, py, tornadoes))[0] < 2) return false;

        Microbot.log(String.format("[Action] Mid-run attack after %d tiles (lane %d, tornado gap %d)", ran, laneTiles, nearestTornado));
        attackHunllef();
        timeAttack = now;
        lastAttackTick = gameTickCount;
        midRunAttackTick = gameTickCount;
        setRunAnchor(px, py);
        return true;
    }

    private void setRunAnchor(int x, int y) {
        runAnchorX = x;
        runAnchorY = y;
    }

    /** Queues the attack click on the hand (an attack waiting there is dropped by a newer walk). */
    private void attackHunllef() {
        Rs2NpcModel boss = hunllef;
        if (boss == null) return;
        final boolean queuedDuringTornadoes = tornadoesUpNow();
        hand.submit(ClickHand.Kind.ATTACK, "attack", ClickHand.Tab.NONE, () -> {
            // Move onto the boss first, then look again: tornadoes spawning (or an escape walk queued) while the mouse
            // travelled means the walk must go first - an attack click landing then delayed the escape by a tick.
            net.runelite.api.Point at = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                java.awt.Shape hull = boss.getConvexHull();
                if (hull == null) return null;
                Rectangle b = hull.getBounds();
                return new net.runelite.api.Point((int) b.getCenterX(), (int) b.getCenterY());
            }).orElse(null);
            if (at != null && Microbot.naturalMouse != null) Microbot.naturalMouse.moveTo(at.getX(), at.getY());
            if (hand.isPending("walk") || (!queuedDuringTornadoes && tornadoesUpNow()) || !isPlayerOnSafeTile()) {
                hand.abortCurrent("aborted before clicking (escape walk goes first)");
                return;
            }
            boss.click("attack");
        }, () -> hunllef != null, "attack Hunllef");
    }

    private boolean tornadoesUpNow() {
        return tornadoThreat || (tornado != null && !tornado.isEmpty());
    }

    /** True if the player is already attacking (interacting with) the Hunllef. */
    private boolean isAttackingBoss() {
        if (hunllef == null) return false;
        Actor interacting = Rs2Player.getInteracting();
        return interacting instanceof NPC && ((NPC) interacting).getIndex() == hunllef.getIndex();
    }

    private void checkAttack() {
        if (tornadoThreat || (tornado != null && !tornado.isEmpty())) return; // NEVER attack during tornadoes!
        if (!config.autoAttack()) return;
        if (hunllef == null) return;
        if (isAttackingBoss()) return; // already attacking it — the client auto-continues, don't re-click
        // Never from a hazard (or a tile about to be one): the attack stops us, and the flee must go first.
        if (!isPlayerOnSafeTile()) return;
        // We're stationary, not under a tornado, and NOT currently attacking the boss — i.e. the engagement
        // dropped (just arrived from a dodge, or finished eating/switching). Re-engage immediately; there's
        // no reason to wait out a timer. Re-clicking never resets the weapon cooldown (the game fires the hit
        // on the next available tick), and the isAttackingBoss() guard above stops us spamming mid-combat.
        // The 1-tick CD_REATTACK is just anti-spam for ticks where getInteracting() briefly reads null.
        boolean reengageReady = now - timeAttack >= CD_REATTACK;
        if (attackNeeded.compareAndSet(true, false) || reengageReady) {
            Microbot.log("[Action] Attack Hunllef");
            attackHunllef();
            timeAttack = now;
            lastAttackTick = gameTickCount;
        }
    }

    public void sendPrayerToggle(Rs2PrayerEnum prayer, boolean enable) {
        if (prayer == null) return;
        Microbot.log(String.format("[Action] Prayer toggle: %s -> %s", prayer.name(), enable ? "ON" : "OFF"));
        lastPrayerClickAt = System.currentTimeMillis();
        boolean protection = prayer.name().startsWith("PROTECT_");
        // A direct menu action (Rs2Prayer, no mouse or tab switch), still through the hand: it shares Microbot's one
        // pending-click slot with the walks, and the hand's settle window stops a second toggle switching it off.
        hand.submit(protection ? ClickHand.Kind.PROTECT_PRAYER : ClickHand.Kind.OFFENSIVE_PRAYER, "prayer:" + prayer.name(),
                ClickHand.Tab.NONE, () -> Rs2Prayer.toggle(prayer),
                () -> !Rs2Prayer.isOutOfPrayer() && Rs2Prayer.isPrayerActive(prayer) != enable,
                (enable ? "pray " : "unpray ") + prayer.name());
    }

    /** Centre of an inventory item's slot (by name, partial match, or by id), or null; for the hand to aim at. */
    private static java.awt.Point itemCenter(String name, int id) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Rs2ItemModel item = name != null ? Rs2Inventory.get(name, false) : Rs2Inventory.get(id);
            Rectangle b = item == null ? null : Rs2Inventory.itemBounds(item);
            return b == null ? null : new java.awt.Point((int) b.getCenterX(), (int) b.getCenterY());
        }).orElse(null);
    }



    private void logVerbose(String msg) {
        if (config.verboseLog()) {
            Microbot.log(msg);
        }
    }

    public void startup2() {
        shutdownRequested = false;
    }

    @Override
    public void shutdown() {
        shutdownRequested = true;
        hand.stop();
        super.shutdown();
        if (mainScheduledFuture != null) {
            mainScheduledFuture.cancel(true);
        }
        Rs2Prayer.disableAllPrayers();
        Rs2Walker.setTarget(null);
    }
} // End of Script

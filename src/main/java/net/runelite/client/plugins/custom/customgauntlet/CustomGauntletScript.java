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
    private static final long CLICK_SPACING_MS = 600;
    private static final int SIM_TICKS_AFTER_ARRIVAL = 2; // keep simulating the tornado chase this long after a run ends
    private static final int CRITICAL_HP = 45;            // below this, eat even while a dodge move is in progress
    private static final int FLEE_MAX_TILES = 4;
    private static final long HIT_EXPECTED_MS = 1800;     // ~3 ticks: how long a predicted unavoidable hit unlocks normal eating          // farther than this, cross the hazards via escapeAcrossDanger instead // prefer escaping to a lane at least this long (room to dodge)
    private static final int MID_RUN_ATTACK_MIN_LANE = 11; // only attack mid-run on lanes longer than 10 tiles
    private static final int MID_RUN_ATTACK_TILES = 6;     // tiles run since the last attack / run start before attacking again
    private static final int MID_RUN_ATTACK_PAUSE_TICKS = 2; // ticks an attack click stands us still mid-run
    private static final int MID_RUN_ATTACK_MIN_TICKS = 4; // bow/staff attack speed: don't pause for an attack that can't fire yet
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
    private HeadIcon bossHeadIcon = null;
    private Rs2NpcModel hunllef = null;
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
    private int[] fleeTarget = null;      // committed "get off this hazard" tile; clicked once, kept until reached
    private long fleeClickAt = -1;
    private int fleePickTick = -1;        // tick the current flee target was picked (one re-pick per tick)
    private long lastWalkClickAt = -1;    // loop timestamp (now) of the last walk click
    private long lastEatClickAt = -1;     // loop timestamp (now) of the last eat/drink click
    private volatile long hitExpectedUntil = -1;  // a dodge move predicted an unavoidable hit: eat at the normal threshold until then
    private volatile long lastPrayerClickAt = -1; // diagnostics: wall-clock time of the last prayer click
    private int lastDiagTick = -1;
    private int runSpeed = 2;                     // tiles per tick this dodge pass: 2 running, 1 walking (run off / no energy)
    private volatile boolean movementCritical = false; // dodge is mid flee/evade or a tornado is within 3: don't eat
    private int[] evadeTarget = null;     // committed tornado-evasion tile (both lane directions were cut off)
    private int runAnchorX = -1, runAnchorY = -1; // where the current run started / we last attacked (scene coords)
    private int midRunAttackTick = -1;            // tick a mid-run attack was clicked; the run resumes the tick after
    private int lastWalkTargetX = -1;
    private int lastWalkTargetY = -1;

    /**
     * Coordinate-based arrival check: compares player's scene coordinates with desired tile,
     * confirming whether the player has physically reached the tile that was clicked.
     */
    private boolean hasArrived(int targetX, int targetY) {
        if (targetX < 0 || targetY < 0) return true;
        LocalPoint lp = Rs2Player.getLocalLocation();
        if (lp == null) return true;
        return lp.getSceneX() == targetX && lp.getSceneY() == targetY;
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
                        if (!tickHappened.get()) break;
                        loopCount++;
                        if (loopCount > LOOPS_PER_TICK) {
                            loopCount = 0;
                            tickHappened.set(false);
                            checkProtectPrayers();
                            break;
                        }

                        if (now - timeNpc > CD_NPC) {
                            checkNpc();
                        }

                        if (now - timeHunllefLog > CD_HUNLLEF_LOG) {
                            logHunllefLocation();
                            logArenaInfo();
                        }

                        //Fast Section
                        checkProtectPrayers();
                        if (now - magicBlastEnd < BUFFER_MAGICBLAST)
                            checkProtectPrayers(); //turbo check after magic blast happens

                        boolean tornadoesActive = tornado != null && !tornado.isEmpty();

                        // Maintain offensive prayers during combat
                        if (now - timePrayOffence > CD_PRAYOFFENCE) checkAttackPrayers();

                        // Only simulate sleep on prayer changes when NOT kiting tornadoes!
                        if (!tornadoesActive && ((now - timePrayProtect < CD_RECENTLY) || (now - timePrayOffence < CD_RECENTLY) || (now - timeSteel < CD_RECENTLY)))
                            break;

                        //Slow Section

                        // Tornado phase over? Drop the kite state NOW.
                        if (!tornadoesActive && (tornadoThreat || dodgeActive)) {
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

                        // Tornado dodging has TOP priority: keep moving, and eat/drink on the run.
                        boolean dodging = config.dodgeTornadoes() && tornadoesActive && handleTornadoDodge();

                        // A second click in the same pass as a walk click replaces it (the walk is dropped and we stop
                        // dead mid-run), so eat/drink only on passes that didn't just click a walk.
                        boolean walkClickedThisPass = now - lastWalkClickAt < CLICK_SPACING_MS;
                        // ...unless HP is critical: a delayed walk costs a tile, not eating costs the run.
                        boolean hpCritical = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) < CRITICAL_HP;
                        if ((!walkClickedThisPass || hpCritical) && now - timeEatAttempted > CD_EAT) checkFood();
                        if (!walkClickedThisPass && now - timeDrink > CD_DRINK) checkPrayerPotions(); //Non-Blocking

                        if (dodging || tornadoesActive || tornadoThreat) break; // NEVER attack during tornadoes!

                        // When no tornado is up the dodge is dormant, so this is our only line of defence
                        // against a damage field. handleSafespot ALWAYS flees a damaging tile (standing in
                        // fire is fatal regardless of the runToSafespot opt-out); it only does optional
                        // repositioning when runToSafespot is enabled.
                        if ((tornado == null || tornado.isEmpty()) && handleSafespot())
                            break; //running to safety takes priority over gear/attack this loop

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
            if (local == null || local.getLocalLocation() == null) {
                return bounds + " | player loc unknown";
            }
            int px = local.getLocalLocation().getSceneX();
            int py = local.getLocalLocation().getSceneY();
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
            LocalPoint meLp = local.getLocalLocation();
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
            LocalPoint meLp = local.getLocalLocation();
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
            LocalPoint playerLp = local.getLocalLocation();
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
            if (!onDanger && !config.runToSafespot()) {
                return false;
            }

            // already safe: inside the arena, on a safe floor tile, not on danger, and clear of boss footprint
            if (inArena(playerX, playerY) && isSafeFloor(playerX, playerY) && !onDanger
                    && !isUnderBoss(playerX, playerY, bossX, bossY, 0)) {
                fleeTarget = null; // safe again: the next hazard picks a fresh nearest tile
                return false;
            }

            if (!onDanger && now - timeSafespot < CD_SAFESPOT) {
                return false;
            }

            boolean fled = fleeToNearestSafeTile(playerX, playerY, bossX, bossY, tornadoSceneCoords());
            if (fled) timeSafespot = now;
            return fled;
        }).orElse(false);
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
            LocalPoint plp = local.getLocalLocation();
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
            LocalPoint playerLp = local.getLocalLocation();
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
                Microbot.log(String.format("[Dodge] tick %d pos=(%d,%d) dest=%s tornado=%d hazard=%b %s | walk %dms, eat %dms, prayer %dms ago",
                        tick, px, py, dest == null ? "none" : "(" + dest.getSceneX() + "," + dest.getSceneY() + ")",
                        nearestNow, isOnDangerousTile(px, py), vitalsTag(),
                        lastWalkClickAt < 0 ? -1 : now - lastWalkClickAt, lastEatClickAt < 0 ? -1 : now - lastEatClickAt,
                        lastPrayerClickAt < 0 ? -1 : wall - lastPrayerClickAt));
            }

            movementCritical = nearestNow <= 3 || evadeTarget != null
                    || isOnDangerousTile(px, py);

            // Standing on a hazard (or under the boss) trumps the lane: get off it in one committed click.
            if (isOnDangerousTile(px, py) || isUnderBoss(px, py, bossX, bossY, 0)) {
                if (fleeToNearestSafeTile(px, py, bossX, bossY, tornadoes)) return true;
            } else {
                fleeTarget = null;
            }

            // Committed tornado evasion (both lane directions were cut off): finish it before picking a new lane,
            // otherwise the lane logic would immediately route us back toward the tornadoes.
            if (evadeTarget != null) {
                if ((px == evadeTarget[0] && py == evadeTarget[1]) || !isTileFullySafe(evadeTarget[0], evadeTarget[1], bossX, bossY)
                        || minTornadoDistance(evadeTarget[0], evadeTarget[1], tornadoes) <= 1) {
                    evadeTarget = null;
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
                    return walkSafeRoute(px, py, transitionEntryTile[0], transitionEntryTile[1], bossX, bossY)
                            || escapeAcrossDanger(px, py, bossX, bossY, tornadoes);
                }
                inTransition = false;
                transitionEntryTile = null;
                transitionWaypoints = new ArrayList<>();
                currentShuttleTarget = pickRunEnd(px, py, tornadoes);
                setRunAnchor(px, py);
                Microbot.log(String.format("[Action] Reached safe lane at (%d,%d) - shuttling toward (%d,%d)",
                        px, py, currentShuttleTarget[0], currentShuttleTarget[1]));
            }

            // Determine initial sprint target endpoint (furthest from tornadoes) if not set
            if (currentShuttleTarget == null) {
                currentShuttleTarget = pickRunEnd(px, py, tornadoes);
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

            if (atTarget) {
                if (nearest > 3) {
                    // Standing clear at endpoint: Attack boss & pre-hover next move
                    boolean canAttackBoss = config.autoAttack() && hunllef != null
                            && (isBowEquipped() || isStaffEquipped() || (isHalberdEquipped() && Math.max(Math.abs(px - bossX), Math.abs(py - bossY)) <= 2));
                    boolean passed5Ticks = (gameTickCount - lastAttackTick >= 5) || (now - timeAttack >= 3000);

                    if (passed5Ticks && canAttackBoss && !isAttackingBoss()) {
                        Microbot.log(String.format("[Action] Standing clear at endpoint (gap %d > 3) - attacking Hunllef", nearest));
                        hunllef.click("attack");
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
                if (midRunAttackTick == gameTickCount && !walkingElsewhere) return true;
                if (tryMidRunAttack(px, py, bossX, bossY, nearest, distToTarget, tornadoes)) return true;
                if (walkSafeRoute(px, py, currentShuttleTarget[0], currentShuttleTarget[1], bossX, bossY)) {
                    return true;
                }
                // No safe route to the endpoint: snap back onto the lane, but only along a safe leg.
                int[] snap = findClosestSafeLaneTile(px, py, bossX, bossY);
                if (snap != null && isStraightPathFullySafe(px, py, snap[0], snap[1], bossX, bossY)) {
                    if (!isAlreadyWalkingTo(snap[0], snap[1])) {
                        lastWalkTargetX = snap[0];
                        lastWalkTargetY = snap[1];
                        Microbot.log(String.format("[Action] Snap-walking to safe lane tile (%d,%d)", snap[0], snap[1]));
                        walkExactTile(snap[0], snap[1]);
                    }
                    return true;
                }
                return escapeAcrossDanger(px, py, bossX, bossY, tornadoes);
            }
        }).orElse(false);

        return result;
    }

    /**
     * Walks toward (tx,ty) without crossing an unsafe tile (damage/warning floor, Hunllef footprint, off-arena).
     * If the direct walk is safe we go straight there; otherwise we follow the cardinal safe BFS path and walk to
     * the farthest tile on it still reachable by one safe leg - i.e. the next corner waypoint. Called every loop,
     * so each leg is re-planned from where we stand and re-validated as the floors change.
     *
     * @return true if a safe walk toward the target is under way; false if no safe route exists
     */
    private boolean walkSafeRoute(int px, int py, int tx, int ty, int bossX, int bossY) {
        int[] leg = null;
        if (isStraightPathFullySafe(px, py, tx, ty, bossX, bossY)) {
            leg = new int[]{tx, ty};
        } else {
            List<int[]> path = findCardinalSafePath(px, py, tx, ty, bossX, bossY);
            if (path.size() <= 1) return false;
            for (int i = path.size() - 1; i >= 1; i--) {
                int[] candidate = path.get(i);
                if (isStraightPathFullySafe(px, py, candidate[0], candidate[1], bossX, bossY)) {
                    leg = candidate;
                    break;
                }
            }
            if (leg == null) leg = path.get(1); // adjacent cardinal step on a safe tile
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
     * The lane end to run to: a clean run (never caught) first, then the most room from the tornadoes on the way,
     * then the fewest hits, then the most room at the end. Picking the end merely farthest from the tornadoes once
     * sent us down a lane that passed one at gap 1.
     */
    private int[] pickRunEnd(int px, int py, List<int[]> tornadoes) {
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
     * @return {min end-of-tick gap over the whole run (0 = hit), gap at the end of the simulation, ticks hit}
     */
    private int[] simulateRun(int px, int py, int[] end, List<int[]> tornadoes) {
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
        int ticks = (line.size() + runSpeed - 1) / runSpeed + SIM_TICKS_AFTER_ARRIVAL;
        for (int tick = 0; tick < ticks; tick++) {
            for (int k = 0; k < runSpeed && idx < line.size(); k++, idx++) {
                cx = line.get(idx)[0];
                cy = line.get(idx)[1];
            }
            for (int[] t : tz) stepToward(t, cx, cy);
            gap = minTornadoDistance(cx, cy, tz);
            margin = Math.min(margin, gap);
            if (gap == 0) hits++;
        }
        return new int[]{margin, gap, hits};
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
        int[] best = null;
        int bestMargin = -1, bestGap = -1, bestDist = Integer.MAX_VALUE, bestHits = Integer.MAX_VALUE;
        boolean bestNearBoss = true;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!isTileFullySafe(x, y, bossX, bossY)) continue;
                if (!isStraightPathFullySafe(px, py, x, y, bossX, bossY)) continue;
                int[] tile = new int[]{x, y};
                int[] sim = simulateRun(px, py, tile, tornadoes);
                int dist = Math.max(Math.abs(x - px), Math.abs(y - py));
                boolean nearBoss = isUnderBoss(x, y, bossX, bossY, 1);
                boolean better;
                if (best == null) {
                    better = true;
                } else if ((sim[0] >= 1) != (bestMargin >= 1)) {
                    better = sim[0] >= 1;
                } else if (sim[0] >= 1) {
                    // Clean escapes: most room on the way, then at the end, then away from the boss, then shorter.
                    better = sim[0] != bestMargin ? sim[0] > bestMargin
                            : sim[1] != bestGap ? sim[1] > bestGap
                            : nearBoss != bestNearBoss ? !nearBoss
                            : dist < bestDist;
                } else {
                    // A hit is unavoidable: take the fewest, not next to the boss, over the shortest run. Ranking by
                    // end gap here once sent us 10 tiles back through the tornadoes to a tile beside the Hunllef.
                    better = sim[2] != bestHits ? sim[2] < bestHits
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
                    bestDist = dist;
                }
            }
        }
        if (best == null) return false;
        if (bestMargin < 1) hitExpectedUntil = now + HIT_EXPECTED_MS;
        dropLane();
        evadeTarget = best;
        lastWalkTargetX = best[0];
        lastWalkTargetY = best[1];
        Microbot.log(String.format("[Evade] Both lane directions cut off by tornadoes - running to (%d,%d) (closest pass %d, end gap %d%s) %s",
                best[0], best[1], bestMargin, bestGap,
                bestMargin >= 1 ? "" : String.format(", WILL BE HIT x%d", bestHits), vitalsTag()));
        walkExactTile(best[0], best[1]);
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
                && (tornadoes.isEmpty() || fleePickTick == gameTickCount
                    || minTornadoDistance(fleeTarget[0], fleeTarget[1], tornadoes) > 1)) {
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
                int hazards = hazardsOnWalkLine(px, py, x, y, bossX, bossY);
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
        int[] best = null;
        int bestDist = Integer.MAX_VALUE, bestOffAxis = Integer.MAX_VALUE, bestHazards = Integer.MAX_VALUE;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (x == px && y == py) continue;
                if (!isTileFullySafe(x, y, bossX, bossY)) continue;
                if (avoidTornadoes && !tornadoes.isEmpty() && minTornadoDistance(x, y, tornadoes) <= 1) continue;
                // The game can't walk through the Hunllef: a straight line across it is really a long detour.
                if (walkLineCrossesBoss(px, py, x, y, bossX, bossY)) continue;
                int adx = Math.abs(x - px), ady = Math.abs(y - py);
                int dist = Math.max(adx, ady);
                int offAxis = Math.min(adx, ady);
                if (dist > bestDist || (dist == bestDist && offAxis > bestOffAxis)) continue;
                // The nearest safe tile is worthless if the run there ends a tick on a tornado: a flee once
                // headed 2 tiles straight at one and the player stood in it on the hazard (65 damage in 3 ticks).
                if (avoidTornadoes && !tornadoes.isEmpty() && simulateRun(px, py, new int[]{x, y}, tornadoes)[0] < 1) continue;
                int hazards = hazardsOnWalkLine(px, py, x, y, bossX, bossY);
                if (dist == bestDist && offAxis == bestOffAxis && hazards >= bestHazards) continue;
                best = new int[]{x, y};
                bestDist = dist;
                bestOffAxis = offAxis;
                bestHazards = hazards;
            }
        }
        return best;
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
        if (local == null || local.getLocalLocation() == null) return;
        LocalPoint bossLp = hunllef.getLocalLocation();
        if (bossLp == null) return;

        int px = local.getLocalLocation().getSceneX();
        int py = local.getLocalLocation().getSceneY();
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
        if (now - lastEatClickAt < CLICK_SPACING_MS) return; // would replace the eat click (and vice versa); next pass re-clicks
        lastWalkClickAt = now;
        LocalPoint lp = LocalPoint.fromScene(sceneX, sceneY);
        if (lp == null) return;
        Client client = Microbot.getClient();

        // 1. Scene polygon centroid click
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly != null && poly.npoints == 4) {
            int cx = (poly.xpoints[0] + poly.xpoints[1] + poly.xpoints[2] + poly.xpoints[3]) / 4;
            int cy = (poly.ypoints[0] + poly.ypoints[1] + poly.ypoints[2] + poly.ypoints[3]) / 4;

            Rectangle viewport = new Rectangle(
                    client.getViewportXOffset(),
                    client.getViewportYOffset(),
                    client.getViewportWidth(),
                    client.getViewportHeight()
            );

            if (viewport.contains(cx, cy) && poly.contains(cx, cy)) {
                NewMenuEntry entry = new NewMenuEntry()
                        .param0(cx)
                        .param1(cy)
                        .type(MenuAction.WALK)
                        .identifier(0)
                        .itemId(-1)
                        .option("Walk here");
                // Quick click (no natural mouse travel), like the prayer flicks: the curved mouse path took hundreds
                // of ms to land, and on a hazard with a tornado closing in that was 1-2 ticks of standing still.
                Microbot.doInvokeQuick(entry, new Rectangle(cx - 2, cy - 2, 4, 4));
                return;
            }
        }

        // 2. Minimap fallback: 100% exact top-down orthogonal projection without 3D perspective distortion
        Point minimapPoint = Rs2MiniMap.localToMinimap(lp);
        if (minimapPoint != null && Rs2MiniMap.isPointInsideMinimap(minimapPoint)) {
            Microbot.getMouse().click(minimapPoint);
            return;
        }

        // 3. Fallback to walkFastLocal
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
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            LocalPoint lp = LocalPoint.fromScene(sceneX, sceneY);
            if (lp == null) return null;
            Polygon poly = Perspective.getCanvasTilePoly(Microbot.getClient(), lp);
            net.runelite.api.Point canvas;
            if (poly != null && poly.npoints == 4) {
                int cx = (poly.xpoints[0] + poly.xpoints[1] + poly.xpoints[2] + poly.xpoints[3]) / 4;
                int cy = (poly.ypoints[0] + poly.ypoints[1] + poly.ypoints[2] + poly.ypoints[3]) / 4;
                canvas = new net.runelite.api.Point(cx, cy);
            } else {
                canvas = Perspective.localToCanvas(Microbot.getClient(), lp, Microbot.getClient().getPlane());
            }
            if (canvas == null) return null;
            if (Microbot.naturalMouse != null && Rs2AntibanSettings.naturalMouse) {
                Microbot.naturalMouse.moveTo(canvas.getX(), canvas.getY());
            } else {
                Microbot.getMouse().move(canvas);
            }
            return null;
        });
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

    public void projMagicSeen() {
        nextPrayer = Rs2PrayerEnum.PROTECT_MAGIC;
    }

    public void projRangeSeen() {
        nextPrayer = Rs2PrayerEnum.PROTECT_RANGE;
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
        for (int id : ids) {
            if (Rs2Inventory.contains(id)) {
                Microbot.log(String.format("[Action] Switch gear: equipping weapon ID %d", id));
                Rs2Inventory.equip(id);
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
            LocalPoint playerLp = Rs2Player.getLocalLocation();
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
        LocalPoint lp = local.getLocalLocation();
        if (lp == null) return false;
        return dest.getSceneX() != lp.getSceneX() || dest.getSceneY() != lp.getSceneY();
    }

    private void checkFood() {
        if (!config.enableFood()) return;
        boolean tornadoesActive = tornado != null && !tornado.isEmpty();

        if (tornadoesActive) {
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
        }
    }

    private void eatFood() {
        if (!config.enableFood()) return;
        if (now - timeEatAttempted < CD_RECENTLY) return;
        int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        Microbot.log(String.format("[Action] Eat food: Paddlefish | hp=%d/%d", currentHp, config.lowHpEatValue()));
        Rs2Inventory.interact("Paddlefish", "Eat");
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
        boolean tornadoesActive = tornado != null && !tornado.isEmpty();

        if (tornadoesActive) {
            // During tornadoes: ONLY drink while in flight to destination!
            if (!isPlayerInFlight()) return;
            if (movementCritical) return;
        } else {
            if (!isPlayerOnSafeTile()) return;
        }

        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        if (currentPrayer < config.prayerPotionValue()) {
            Microbot.log(String.format("[Action] Drink potion: Egniol potion | prayer=%d/%d", currentPrayer, config.prayerPotionValue()));
            Rs2Inventory.interact("Egniol potion", "Drink");
            timeDrink = now;
            lastEatClickAt = now;
        }
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
        if (gameTickCount - lastAttackTick < MID_RUN_ATTACK_MIN_TICKS) return false;
        if (nearestTornado <= 3 || distToTarget <= 2) return false;
        // The attack click stops us for about 2 ticks while the tornadoes keep chasing. A gap-5 attack once let
        // them close to gap 1 by the lane end, so only attack if the rest of the run still keeps 2 tiles clear.
        List<int[]> afterPause = new ArrayList<>();
        for (int[] t : tornadoes) {
            int[] moved = {t[0], t[1]};
            for (int i = 0; i < MID_RUN_ATTACK_PAUSE_TICKS; i++) stepToward(moved, px, py);
            afterPause.add(moved);
        }
        if (simulateRun(px, py, currentShuttleTarget, afterPause)[0] < 2) return false;

        Microbot.log(String.format("[Action] Mid-run attack after %d tiles (lane %d, tornado gap %d)", ran, laneTiles, nearestTornado));
        hunllef.click("attack");
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
        if (!isPlayerOnSafeTile() && !hasArrived(lastWalkTargetX, lastWalkTargetY)) return; // Only wait if running to flee unsafe tiles!
        // We're stationary, not under a tornado, and NOT currently attacking the boss — i.e. the engagement
        // dropped (just arrived from a dodge, or finished eating/switching). Re-engage immediately; there's
        // no reason to wait out a timer. Re-clicking never resets the weapon cooldown (the game fires the hit
        // on the next available tick), and the isAttackingBoss() guard above stops us spamming mid-combat.
        // The 1-tick CD_REATTACK is just anti-spam for ticks where getInteracting() briefly reads null.
        boolean reengageReady = now - timeAttack >= CD_REATTACK;
        if (attackNeeded.compareAndSet(true, false) || reengageReady) {
            Microbot.log("[Action] Attack Hunllef");
            hunllef.click("attack");
            timeAttack = now;
            lastAttackTick = gameTickCount;
        }
    }

    public void sendPrayerToggle(Rs2PrayerEnum prayer, boolean enable) {
        if (prayer == null) return;
        Microbot.log(String.format("[Action] Prayer toggle: %s -> %s", prayer.name(), enable ? "ON" : "OFF"));
        lastPrayerClickAt = System.currentTimeMillis();
        Rs2Prayer.toggle(prayer, enable, false);
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
        super.shutdown();
        if (mainScheduledFuture != null) {
            mainScheduledFuture.cancel(true);
        }
        Rs2Prayer.disableAllPrayers();
        Rs2Walker.setTarget(null);
    }
} // End of Script

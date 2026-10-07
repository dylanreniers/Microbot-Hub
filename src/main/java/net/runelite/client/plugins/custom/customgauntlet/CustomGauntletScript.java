package net.runelite.client.plugins.custom.customgauntlet;

import com.google.inject.Inject;

import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import lombok.Getter;
import net.runelite.api.*;
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
    private static final int TORNADO_TRIGGER_DIST = 4;  // start kiting once a tornado is this close (tiles)
    // Re-evaluate/issue the dodge walk at most once per game tick (~600ms). Clicking faster than this
    // restarts the client's pathfinder every loop, so the player stutters ~1-2 tiles and never commits
    // to a run — which gets it caught by the tornadoes.
    private static final int CD_TORNADO_DODGE = 600;
    private static final int CD_DODGE_LOG = 400;        // steady-state dodge log cadence (ms); transitions log instantly
    private static final int CD_CAPTURE = 150;          // capture-mode sampling cadence (ms); de-duped to one line per decision
    private static final int DODGE_BOSS_MARGIN = 0;     // extra ring to keep clear of the Hunllef beyond its footprint (0 = stand right next to it for max dodge room; raise to 1 if it melees us)
    private static final int WEAPON_SPEED_MS = 3000;    // Gauntlet weapon speed: 5 ticks
    private static final int ATTACK_WEAVE_RANGE = 9;    // only weave an attack mid-dodge if the boss is this close (so we shoot, not walk)
    private static final int ATTACK_SUPPRESS_TORNADO_DIST = 2; // don't weave an attack if a tornado is this close — focus on the dodge
    private static final int SCENE_SIZE = 104;          // RuneLite scene is 104x104 tiles (for the BFS escape)
    private static final int MAINTAIN_GAP = 6;          // keep moving while any tornado is within this; attack only once the gap is clear
    private static final int SIP_SAFE_GAP = 3;          // only yield a movement tick to drink/eat when the nearest tornado is at least this far (safe to stand one tick)
    private static final int STUCK_TICKS = 2;           // require this many re-plan ticks of no movement before forcing an UNSTUCK step (a fresh walk needs ~1 tick to start)
    // --- Rim-running (circle the perimeter in a committed direction, like manual play) ---
    // Commit to the far corner: manual-play capture showed clicks running the FULL wall (up to ~11 tiles),
    // which keeps the tornadoes trailing in a line instead of re-planning every few tiles and oscillating.
    private static final int RIM_LOOKAHEAD = 12;        // tiles ahead along the rim we target per commit (~full arena span)
    private static final int MIN_TARGET_CLEARANCE = 2;  // keep circling with at least 2 tiles clearance from any tornado
    private static final int MIN_DODGE_TRAVEL = 3;      // never pick a dodge destination closer than 3 tiles (never adjacent)
    private static final int FLIP_HYSTERESIS = 2;       // only reverse circling direction if the other way is this much safer (stops thrashing)
    private static final int DANGER_FLIP_MARGIN = 4;    // reverse toward the open side if the other way is this much farther from the danger field

    private static final int HUNLLEF_RADIUS = 2; // 5x5 footprint -> 2 tiles from its centre to each edge
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

    private static final int STAT_HP = -1;
    private static final int STAT_PRAYER = -1;

    private static final Set<Integer> TORNADO_IDS = Set.of(
            CG_TORNADO,
            REGULAR_HUNLEFF_TORNADO
    );

    private static final Set<Integer> HUNLLEF_IDS = Set.of(
            9035, 9036, 9037, 9038, // Corrupted Hunllef variants
            9021, 9022, 9023, 9024  // Crystalline Hunllef variants
    );

    private static final Set<Integer> DANGEROUS_TILES = Set.of(
            36047, 36048, // Corrupted tiles (Ground object)
            36150, 36151 // Gauntlet tiles (Ground object)
    );

    private static final Set<Integer> FLOOR_TILES = Set.of(
            36149, 36046 // CG, G Floor Tiles
    );

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

    private Rs2PrayerEnum nextPrayer = Rs2PrayerEnum.PROTECT_RANGE;
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
    // Committed circling direction for rim-running: +1 = CCW, -1 = CW, 0 = uncommitted (pick on next dodge).
    // Persisting it across ticks is what stops the greedy per-tick oscillation that got us caught.
    private int dodgeRotation = 0;
    // True on loop iterations where the dodge just issued a walk/attack click. Eating/drinking share the
    // same synthetic-click pipeline, so clicking food in the SAME iteration clobbers the pending walk and
    // we stutter one tile — gate eat/drink on this so they only fire on the ~2 free iterations per tick.
    private volatile boolean dodgeClickedThisLoop = false;
    // Player scene pos when we last ISSUED a dodge walk. If the next re-plan finds us on the same tile, the
    // walk didn't execute (target unreachable in practice — the boss body blocks the route, or our path
    // model disagrees with the game's collision), so we unstick with a guaranteed 1-tile step. -1 = none.
    private int lastWalkPx = -1, lastWalkPy = -1;
    // Consecutive re-plan ticks with no position change since the last walk. A fresh walk click needs ~1
    // tick to register before the player moves, so we only treat it as a real pin (fire UNSTUCK) after
    // STUCK_TICKS of no movement — otherwise we'd cancel every committed run on its startup tick and oscillate.
    private int dodgeStuckTicks = 0;
    // The destination we're committed to running to. Re-picking a fresh target every tick makes it flip
    // between sides (rotation changes), reversing us into the trailing tornadoes and restarting the walk
    // (1-tick startup delay) every tick — so we never build a gap. We hold this dest until it's reached or
    // becomes invalid (unreachable / unsafe / a tornado closed on it). -1 = none committed.
    private int committedDestX = -1, committedDestY = -1;
    // Waypoint scene pos last clicked by the dodge walk. Only re-click if our destination/waypoint
    // changes or we stop moving, allowing committed runs to continue without per-tick click spam.
    private int lastWpX = -1, lastWpY = -1;
    // True while a tornado is within trigger range (we're actively kiting). Suppresses attacking, which
    // would otherwise path us toward the Hunllef and drag us back into the tornadoes.
    private volatile boolean tornadoThreat = false;
    private int barriersTotal = -1; // barrier tiles matched anywhere in the scene (diagnostic)
    private int barriersNear = -1;  // barrier tiles within ARENA_BARRIER_RADIUS of the Hunllef (diagnostic)
    // The safe tile we're currently dodging toward, in scene coords, for the overlay marker (-1 = none).
    // Static so the overlay reads the live value regardless of Guice instance scoping (same pattern as ghState).
    private static volatile int renderTargetX = -1, renderTargetY = -1;

    /** Current dodge destination in scene coords for the overlay, or null when not dodging anywhere. */
    public static int[] getDodgeRenderTarget() {
        return renderTargetX < 0 ? null : new int[]{renderTargetX, renderTargetY};
    }

    // Planned route to the dodge target, for the overlay, packed as (sceneX << 8 | sceneY). Rebuilt each
    // dodge evaluation on the client thread; static so the overlay reads it directly.
    //   pathTiles = every tile along the safe route we'd run (blue),
    //   hopTiles  = the intermediate waypoints we'd click on the way (yellow); the final target is green.
    private static volatile int[] pathTiles = new int[0];
    private static volatile int[] hopTiles = new int[0];

    /** Packed (sceneX<<8|sceneY) tiles of the planned run path for the overlay (blue). */
    public static int[] getPathTiles() {
        return pathTiles;
    }

    /** Packed (sceneX<<8|sceneY) intermediate hop/waypoint tiles for the overlay (yellow). */
    public static int[] getHopTiles() {
        return hopTiles;
    }
    // Dodge diagnostics: built on the client thread inside handleTornadoDodge, emitted on the script
    // thread by emitDodgeLog() (logging on the client thread can deadlock via the GameChatAppender).
    private String dodgeDebug = null;
    private String dodgeLogKey = null;      // action+target key; a change forces an immediate log line
    private String lastDodgeLogKey = null;
    private long timeDodgeLog = -1;

    // Arena bounds in scene coords, derived once per fight from the 4 barriers. The floor tile id is
    // not unique to the arena, so these bounds are what actually keep retreats inside the room.
    private volatile boolean arenaBoundsKnown = false;
    private int arenaMinX, arenaMaxX, arenaMinY, arenaMaxY;

    private final AtomicBoolean tickHappened = new AtomicBoolean(false);
    // Monotonic game-tick counter (bumped in eventGameTick on the client thread, read on the script thread).
    // The tornado dodge re-plans once per NEW tick rather than on a wall-clock timer, so planning stays in
    // phase with when tornadoes actually move. lastDodgeTick is the tick we last planned on.
    private volatile int gameTickCount = 0;
    private int lastDodgeTick = -1;
    private int[] currentDodgeWp = null;
    private long timeLastWalkLog = -1;
    private int lastDodgeWalkTick = -2;
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

    private CustomGauntletConfig config;

    @Inject
    public CustomGauntletScript(CustomGauntletPlugin plugin, CustomGauntletConfig config) {
        this.config = config;
    }

    public boolean run() {

        now = System.currentTimeMillis();
        ghState = config.startState();
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

                        // Only toggle offensive prayers when tornadoes are NOT active
                        if (!tornadoesActive && now - timePrayOffence > CD_PRAYOFFENCE) checkAttackPrayers();

                        // Only simulate sleep on prayer changes when NOT kiting tornadoes!
                        if (!tornadoesActive && ((now - timePrayProtect < CD_RECENTLY) || (now - timePrayOffence < CD_RECENTLY) || (now - timeSteel < CD_RECENTLY)))
                            break;

                        //Slow Section

                        // Tornado phase over? Drop the kite state NOW. tornadoThreat is cleared only inside
                        // handleTornadoDodge, which the loop stops calling once tornadoes are gone — so without
                        // this it stays stuck true between phases and checkAttack refuses to re-engage the boss.
                        if (!tornadoesActive && (tornadoThreat || dodgeActive)) {
                            clearDodge();
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

                        if (now - timeEatAttempted > CD_EAT) checkFood();
                        if (now - timeDrink > CD_DRINK) checkPrayerPotions(); //Non-Blocking

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
     * A scene tile (bossSceneX/Y is the Hunllef's centre) is "under" the boss when it falls within its
     * 5x5 footprint (HUNLLEF_RADIUS from the centre), optionally expanded by {@code margin}.
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
                return false;
            }

            if (!onDanger && now - timeSafespot < CD_SAFESPOT) {
                return false;
            }

            LocalPoint best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Tile tile : Rs2GameObject.getTiles()) {
                GroundObject ground = tile.getGroundObject();
                if (ground == null || !FLOOR_TILES.contains(ground.getId())) {
                    continue; // accept both normal (36149) and corrupted (36046) arena floor
                }
                LocalPoint lp = tile.getLocalLocation();
                if (lp == null) {
                    continue;
                }
                int tx = lp.getSceneX(), ty = lp.getSceneY();
                if (!inArena(tx, ty)) {
                    continue; // only ever retreat to tiles inside the 12x12 arena
                }
                if (isUnderBoss(tx, ty, bossX, bossY, 0)) {
                    continue;
                }
                if (isOnDangerousTile(tx, ty)) {
                    continue;
                }
                int distance = Math.max(Math.abs(tx - playerX), Math.abs(ty - playerY));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = lp;
                }
            }
            if (best == null) {
                return false;
            }
            timeSafespot = now;
            lastWalkTargetX = best.getSceneX();
            lastWalkTargetY = best.getSceneY();
            Microbot.log(String.format("[Action] Safespot walk to (%d,%d) | from=(%d,%d)",
                    best.getSceneX(), best.getSceneY(), playerX, playerY));
            Rs2Walker.walkFastLocal(best);
            return true;
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
    private boolean handleTornadoDodge() {
        // Re-plan once per GAME TICK (right after tornadoes move), not on a wall-clock timer. A wall-clock
        // 600ms throttle drifts out of phase with the tick and, with the loop's execution time, actually
        // spaced re-plans ~1.25 ticks apart — leaving a window where we ran on a stale plan while the
        // tornadoes had already moved. Keying off the tick count keeps planning in lockstep with the tick.
        int tick = gameTickCount;
        if (tick == lastDodgeTick && currentDodgeWp != null) {
            // Intra-tick iteration: spam-click the active waypoint so the character NEVER stalls or pauses
            Rs2Walker.walkFastLocal(LocalPoint.fromScene(currentDodgeWp[0], currentDodgeWp[1]));
            return true;
        }
        lastDodgeTick = tick;
        timeTornadoDodge = now;
        dodgeClickedThisLoop = false;

        boolean result = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player local = Microbot.getClient().getLocalPlayer();
            if (local == null || hunllef == null) { clearDodge(); setDodgeLog("IDLE", "no player/boss"); return false; }
            LocalPoint playerLp = local.getLocalLocation();
            LocalPoint bossLp = hunllef.getLocalLocation();
            if (playerLp == null || bossLp == null) { clearDodge(); setDodgeLog("IDLE", "no local point"); return false; }

            computeArenaBounds();
            if (!arenaBoundsKnown) { tornadoThreat = false; renderTargetX = -1; clearPathViz(); setDodgeLog("WAIT", "arena bounds unknown"); return false; }

            List<int[]> tornadoes = tornadoSceneCoords();
            if (tornadoes.isEmpty()) { clearDodge(); setDodgeLog("CLEAR", "no tornadoes"); return false; }

            int px = playerLp.getSceneX(), py = playerLp.getSceneY();
            int bossX = bossLp.getSceneX(), bossY = bossLp.getSceneY();

            int nearest = minTornadoDistance(px, py, tornadoes);
            tornadoThreat = true; // Constantly kiting while tornadoes exist - never drop threat to stop and attack
            String base = "p=(" + px + "," + py + ") moving=" + Rs2Player.isMoving()
                    + " near=" + nearest + " torn=" + formatTornadoes(tornadoes);

            if (!Rs2Player.isRunEnabled()) {
                Rs2Player.toggleRunEnergy(true);
            }

            int plane = Microbot.getClient().getPlane();
            Tile[][] tiles = Microbot.getClient().getScene().getTiles()[plane];

            // Constantly on the move while tornadoes are active — never stop or wait for tornadoes to get closer.
            dodgeActive = true;
            clearPathViz(); // only the MOVE branch republishes a path; escape/stuck/unstuck show none

            // Stuck detection: we issued a walk last tick but we're on the exact same tile, so that target is
            // unreachable in practice. If genuinely stuck for 3 ticks, flip circling rotation and re-plan
            // in the opposite direction to find open space rather than stepping to an adjacent dead-end tile.
            if (lastWalkPx == px && lastWalkPy == py) {
                dodgeStuckTicks++;
            } else {
                dodgeStuckTicks = 0;
            }
            if (dodgeStuckTicks >= 3) {
                dodgeRotation = -dodgeRotation;
                committedDestX = -1;
                committedDestY = -1;
                lastWpX = -1;
                lastWpY = -1;
                dodgeStuckTicks = 0;
                setDodgeLog("FLIP", base + " stuck for 3 ticks, flipping rotation to " + dodgeRotation);
            }

            // 1) BFS the tiles reachable from the player over SAFE tiles only (floor, not damaging, clear
            //    of the boss's melee range). This routes AROUND the boss and never through danger.
            int[][] prev = bfsReachable(tiles, px, py, bossX, bossY, false);

            // Arena centre (scene coords) — the pivot we circle. Rim targets are chosen by angular progress
            // around this point, not raw distance-from-tornado (which flips side each tick and oscillates).
            double cx = (arenaMinX + arenaMaxX) / 2.0;
            double cy = (arenaMinY + arenaMaxY) / 2.0;

            // Danger-field centroid. Manual-play capture showed the human lives on the side OPPOSITE the
            // damage field ~83% of the time, so we steer the circling toward the open side (null = no field).
            double[] dc = dangerCentroid(tiles);

            // Re-evaluate safe destination:
            // Coordinate-based arrival: we reached destination when within 1 tile!
            int distToCommitted = (committedDestX >= 0) ? Math.max(Math.abs(committedDestX - px), Math.abs(committedDestY - py)) : -1;
            boolean reachedCommitted = distToCommitted >= 0 && distToCommitted <= 1;

            boolean committedSolid = committedDestX >= 0
                    && !reachedCommitted
                    && isDestinationSolid(committedDestX, committedDestY, bossX, bossY, tornadoes);

            boolean keepCommitted = committedSolid
                    && lineIsSafe(tiles, px, py, committedDestX, committedDestY, bossX, bossY)
                    && lineClearOfTornadoes(px, py, committedDestX, committedDestY, tornadoes, 1);

            int[] dest;
            if (keepCommitted) {
                // Safe tile is still solid and path is completely safe: keep running to it!
                dest = new int[]{committedDestX, committedDestY};
            } else {
                // Reached destination, or path/tile became unsafe:
                // Calculate a new safe destination candidate!
                dest = chooseNewSafeTile(prev, px, py, cx, cy, dc, tornadoes, tiles, bossX, bossY);
                if (dest != null) {
                    committedDestX = dest[0];
                    committedDestY = dest[1];
                }
            }

            renderTargetX = dest[0];
            renderTargetY = dest[1];
            Route route = computeRoute(tiles, prev, px, py, dest, bossX, bossY, tornadoes);
            pathTiles = route.pathTiles;
            hopTiles = route.hopTiles;
            int tScore = clearanceOf(dest, tornadoes);

            int[] wp = route.firstStep;
            currentDodgeWp = wp;
            lastWalkTargetX = wp[0];
            lastWalkTargetY = wp[1];
            lastWalkPx = px;
            lastWalkPy = py;
            lastDodgeWalkTick = gameTickCount;
            dodgeClickedThisLoop = true;

            // Only spam-click once we are near/at the destination (dist <= 2), or when destination changed
            boolean shouldClick = (wp[0] != lastWpX || wp[1] != lastWpY)
                    || !keepCommitted
                    || distToCommitted <= 2
                    || (lastWalkPx == px && lastWalkPy == py);

            if (shouldClick) {
                if (wp[0] != lastWpX || wp[1] != lastWpY || now - timeLastWalkLog > 1000) {
                    Microbot.log(String.format("[Action] Dodge walk to (%d,%d) | dest=(%d,%d) | from=(%d,%d)",
                            wp[0], wp[1], dest[0], dest[1], px, py));
                    timeLastWalkLog = now;
                }
                lastWpX = wp[0];
                lastWpY = wp[1];
                Rs2Walker.walkFastLocal(LocalPoint.fromScene(wp[0], wp[1]));
            } else {
                hoverSceneTile(dest[0], dest[1]);
            }

            setDodgeLog("MOVE:" + dest[0] + "," + dest[1],
                    base + " rot=" + dodgeRotation + " target=(" + dest[0] + "," + dest[1] + ") clr=" + tScore
                            + " wp=(" + wp[0] + "," + wp[1] + ") act=MOVE");
            return true;
        }).orElse(false);

        emitDodgeLog();
        return result;
    }

    /** Stash a dodge diagnostic (called on the client thread); emitDodgeLog() logs it on the script thread. */
    private void setDodgeLog(String key, String msg) {
        dodgeLogKey = key;
        dodgeDebug = "[Dodge] " + msg;
    }

    /** Emit the stashed dodge log (script thread). Logs on every action/target change, else every CD_DODGE_LOG. */
    private void emitDodgeLog() {
        if (dodgeDebug == null) return;
        boolean keyChanged = dodgeLogKey != null && !dodgeLogKey.equals(lastDodgeLogKey);
        if (keyChanged || now - timeDodgeLog > CD_DODGE_LOG) {
            logVerbose(dodgeDebug);
            timeDodgeLog = now;
            lastDodgeLogKey = dodgeLogKey;
        }
        dodgeDebug = null;
        dodgeLogKey = null;
    }

    /** Pre-hovers the mouse over a scene tile so the next click lands with 0 travel delay. */
    private void hoverSceneTile(int sceneX, int sceneY) {
        if (sceneX < 0 || sceneY < 0 || sceneX >= SCENE_SIZE || sceneY >= SCENE_SIZE) return;
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            LocalPoint lp = LocalPoint.fromScene(sceneX, sceneY);
            if (lp == null) return null;
            net.runelite.api.Point canvas = Perspective.localToCanvas(Microbot.getClient(), lp, Microbot.getClient().getPlane());
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

    /**
     * A scene tile we may stand on / path through: inside the arena, on arena floor, not a damaging
     * ground/game object, and not under the Hunllef footprint. Must run on the client thread.
     */
    private boolean isStandableSafe(Tile[][] tiles, int x, int y, int bossX, int bossY) {
        if (!inArena(x, y)) return false;
        if (x < 0 || y < 0 || x >= tiles.length || y >= tiles[x].length) return false;
        Tile tile = tiles[x][y];
        if (tile == null) return false;
        GroundObject ground = tile.getGroundObject();
        if (ground == null || !FLOOR_TILES.contains(ground.getId())) return false;
        if (DANGEROUS_TILES.contains(ground.getId())) return false;
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject go : gameObjects) {
                if (go != null && DANGEROUS_TILES.contains(go.getId())) return false;
            }
        }
        // Margin of 1 past the footprint: the ring immediately outside the Hunllef is still within melee
        // range, so keep dodge targets AND straight-line routes a tile clear of it or we run under the boss.
        return !isUnderBoss(x, y, bossX, bossY, DODGE_BOSS_MARGIN);
    }

    /**
     * The exact tiles the character walks through from (x0,y0) to (x1,y1) under the game's movement model
     * (same for players and tornadoes): each tick it closes the MAJOR axis with straight steps until the
     * remaining major gap equals the minor gap, then moves DIAGONALLY to finish — i.e. one straight run then
     * one diagonal run, never a staircase. Excludes the start tile, includes the endpoint.
     */
    private List<int[]> movementPathTiles(int x0, int y0, int x1, int y1) {
        List<int[]> out = new ArrayList<>();
        int cx = x0, cy = y0, guard = 0;
        while ((cx != x1 || cy != y1) && guard++ < SCENE_SIZE * 2) {
            int dx = x1 - cx, dy = y1 - cy;
            int adx = Math.abs(dx), ady = Math.abs(dy);
            if (adx > ady) {
                cx += Integer.signum(dx);          // straight along the major (X) axis
            } else if (ady > adx) {
                cy += Integer.signum(dy);          // straight along the major (Y) axis
            } else {
                cx += Integer.signum(dx);          // gaps equal -> diagonal to finish
                cy += Integer.signum(dy);
            }
            out.add(new int[]{cx, cy});
        }
        return out;
    }

    /**
     * True if EVERY tile the character actually walks through from (x0,y0) to (x1,y1) is safe to stand on
     * (endpoint included), per {@link #movementPathTiles}. This matches where walkFastLocal will actually
     * take us, so a leg that fails here means we must stop at an intermediate waypoint (the last safe tile).
     */
    private boolean lineIsSafe(Tile[][] tiles, int x0, int y0, int x1, int y1, int bossX, int bossY) {
        for (int[] t : movementPathTiles(x0, y0, x1, y1)) {
            if (!isStandableSafe(tiles, t[0], t[1], bossX, bossY)) return false;
        }
        return true;
    }

    /** True if every tile stepped through from (x0,y0) to (x1,y1) maintains at least minClearance from every tornado. */
    private boolean lineClearOfTornadoes(int x0, int y0, int x1, int y1, List<int[]> tornadoes, int minClearance) {
        for (int[] t : movementPathTiles(x0, y0, x1, y1)) {
            if (minTornadoDistance(t[0], t[1], tornadoes) < minClearance) {
                return false;
            }
        }
        return true;
    }

    /** A tile we can walk ACROSS during an escape: arena floor, whether safe or damaging (we accept a hit
     *  or two to punch through a ring of danger), but never a wall or the Hunllef's own footprint. */
    private boolean isFloorTraversable(Tile[][] tiles, int x, int y, int bossX, int bossY) {
        if (!inArena(x, y)) return false;
        if (x < 0 || y < 0 || x >= tiles.length || y >= tiles[x].length) return false;
        Tile tile = tiles[x][y];
        if (tile == null) return false;
        GroundObject ground = tile.getGroundObject();
        if (ground == null) return false;
        int gid = ground.getId();
        if (!FLOOR_TILES.contains(gid) && !DANGEROUS_TILES.contains(gid)) return false; // wall / non-floor
        return !isUnderBoss(x, y, bossX, bossY, 0); // can't walk through the boss itself (margin 0)
    }

    /** A tile is traversable for the BFS: when {@code allowDanger} is false only truly safe tiles count
     *  (normal kiting — never route through danger); when true, damaging floor tiles are also walkable
     *  (boxed-in escape, accepting a hit or two to punch out). Walls and the boss always block. */
    private boolean canTraverse(Tile[][] tiles, int x, int y, int bossX, int bossY, boolean allowDanger) {
        return allowDanger ? isFloorTraversable(tiles, x, y, bossX, bossY)
                           : isStandableSafe(tiles, x, y, bossX, bossY);
    }

    /**
     * Breadth-first flood-fill of tiles reachable from (sx,sy), 8-directional, never cutting a diagonal
     * across a blocked corner. {@code allowDanger} selects the traversal rule (see {@link #canTraverse}).
     * Returns a predecessor grid ({@code -1} = unvisited, otherwise the encoded parent {@code x*SCENE_SIZE+y};
     * the start stores {@link Integer#MAX_VALUE}).
     */
    private int[][] bfsReachable(Tile[][] tiles, int sx, int sy, int bossX, int bossY, boolean allowDanger) {
        int[][] prev = new int[SCENE_SIZE][SCENE_SIZE];
        for (int[] row : prev) Arrays.fill(row, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        prev[sx][sy] = Integer.MAX_VALUE;
        queue.add(new int[]{sx, sy});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int cx = c[0], cy = c[1];
            for (int[] d : dirs) {
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 0 || ny < 0 || nx >= SCENE_SIZE || ny >= SCENE_SIZE) continue;
                if (prev[nx][ny] != -1) continue;
                if (!canTraverse(tiles, nx, ny, bossX, bossY, allowDanger)) continue;
                if (d[0] != 0 && d[1] != 0
                        && (!canTraverse(tiles, cx + d[0], cy, bossX, bossY, allowDanger)
                        || !canTraverse(tiles, cx, cy + d[1], bossX, bossY, allowDanger))) {
                    continue; // no diagonal corner-cut past a blocked corner
                }
                prev[nx][ny] = cx * SCENE_SIZE + cy;
                queue.add(new int[]{nx, ny});
            }
        }
        return prev;
    }

    /** Min Chebyshev distance from a candidate tile to the nearest tornado (the binding threat). */
    private int clearanceOf(int[] tile, List<int[]> tornadoes) {
        return minTornadoDistance(tile[0], tile[1], tornadoes);
    }

    /** Centroid (scene coords) of all damaging tiles in the arena, or null if there's no field. Client thread. */
    private double[] dangerCentroid(Tile[][] tiles) {
        long sx = 0, sy = 0;
        int n = 0;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (isOnDangerousTile(x, y)) { sx += x; sy += y; n++; }
            }
        }
        return n == 0 ? null : new double[]{(double) sx / n, (double) sy / n};
    }

    /** Chebyshev distance from a tile to the danger-field centroid (bigger = farther from the fire). */
    private int distToDanger(int[] tile, double[] dc) {
        return (int) Math.round(Math.max(Math.abs(tile[0] - dc[0]), Math.abs(tile[1] - dc[1])));
    }

    /**
     * Pick the rotational direction (+1 = CCW, -1 = CW) to start circling when uncommitted. Prefer the way
     * whose rim target sits decisively farther from the danger field (manual play lives on the open side);
     * otherwise the way that keeps us further from the tornadoes. Defaults to +1 if neither side has a target.
     */
    private int pickInitialRotation(int[] targetCCW, int[] targetCW, List<int[]> tornadoes, double[] dc) {
        if (targetCCW == null && targetCW == null) return 1;
        if (targetCCW == null) return -1;
        if (targetCW == null) return 1;
        if (dc != null) {
            int dccw = distToDanger(targetCCW, dc), dcw = distToDanger(targetCW, dc);
            if (Math.abs(dccw - dcw) >= 2) return dccw > dcw ? 1 : -1; // clearly one side is away from the fire
        }
        return clearanceOf(targetCCW, tornadoes) >= clearanceOf(targetCW, tornadoes) ? 1 : -1;
    }

    /**
     * Checks if a rim route from (px, py) to (tx, ty) is 100% safe. Either directly along the same wall,
     * or as a safe two-leg L-shape around the intermediate corner. Both legs and the corner must be safe.
     */
    private boolean isRimRouteSafe(Tile[][] tiles, int px, int py, int tx, int ty, int bossX, int bossY) {
        if (lineIsSafe(tiles, px, py, tx, ty, bossX, bossY)) {
            return true;
        }
        int cornerX, cornerY;
        boolean pOnSN = Math.abs(py - arenaMinY) <= 1 || Math.abs(py - arenaMaxY) <= 1;
        boolean tOnWE = Math.abs(tx - arenaMinX) <= 1 || Math.abs(tx - arenaMaxX) <= 1;
        if (pOnSN && tOnWE) {
            cornerX = tx;
            cornerY = py;
        } else {
            cornerX = px;
            cornerY = ty;
        }
        return isStandableSafe(tiles, cornerX, cornerY, bossX, bossY)
                && lineIsSafe(tiles, px, py, cornerX, cornerY, bossX, bossY)
                && lineIsSafe(tiles, cornerX, cornerY, tx, ty, bossX, bossY);
    }

    /** Find the closest standable safe tile to (px, py) to get off damaging floor immediately. */
    private int[] findNearestSafeTile(Tile[][] tiles, int px, int py, List<int[]> tornadoes, int bossX, int bossY) {
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestClearance = -1;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (!isStandableSafe(tiles, x, y, bossX, bossY)) continue;
                int dist = Math.max(Math.abs(x - px), Math.abs(y - py));
                int clr = minTornadoDistance(x, y, tornadoes);
                if (clr < 1) continue;
                if (dist < bestDist || (dist == bestDist && clr > bestClearance)) {
                    bestDist = dist;
                    bestClearance = clr;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /** True if a destination tile is valid and safe to stand on (in arena, safe floor, not fire, not boss, clear of tornadoes). */
    private boolean isDestinationSolid(int destX, int destY, int bossX, int bossY, List<int[]> tornadoes) {
        if (destX < 0 || destY < 0) return false;
        if (!inArena(destX, destY)) return false;
        if (!isSafeFloor(destX, destY)) return false;
        if (isOnDangerousTile(destX, destY)) return false;
        if (isUnderBoss(destX, destY, bossX, bossY, 0)) return false;
        if (minTornadoDistance(destX, destY, tornadoes) < MIN_TARGET_CLEARANCE) return false;
        return true;
    }

    /** True if the route from (px, py) to (destX, destY) is completely clear of dangerous ground and Hunllef. */
    private boolean isRouteToDestinationSafe(Tile[][] tiles, int[][] prev, int px, int py, int destX, int destY, int bossX, int bossY, List<int[]> tornadoes) {
        if (destX < 0 || destY < 0 || destX >= SCENE_SIZE || destY >= SCENE_SIZE) return false;
        if (prev[destX][destY] == -1) return false; // not reachable over safe floor
        Route route = computeRoute(tiles, prev, px, py, new int[]{destX, destY}, bossX, bossY, tornadoes);
        if (route == null || route.firstStep == null) return false;
        if (!lineIsSafe(tiles, px, py, route.firstStep[0], route.firstStep[1], bossX, bossY)) return false;
        if (!lineClearOfTornadoes(px, py, route.firstStep[0], route.firstStep[1], tornadoes, 2)) return false;
        return true;
    }

    /** Calculates a new safe destination tile candidate when the previous one is reached or unsafe. */
    private int[] chooseNewSafeTile(int[][] prev, int px, int py, double cx, double cy, double[] dc,
                                    List<int[]> tornadoes, Tile[][] tiles, int bossX, int bossY) {
        int[] targetCCW = chooseRimTarget(prev, px, py, cx, cy, dc, tornadoes, tiles, bossX, bossY, +1);
        int[] targetCW  = chooseRimTarget(prev, px, py, cx, cy, dc, tornadoes, tiles, bossX, bossY, -1);

        if (dodgeRotation == 0) {
            dodgeRotation = pickInitialRotation(targetCCW, targetCW, tornadoes, dc);
        }
        int[] curTarget = dodgeRotation > 0 ? targetCCW : targetCW;
        int[] altTarget = dodgeRotation > 0 ? targetCW : targetCCW;

        if (curTarget == null && altTarget != null) {
            dodgeRotation = -dodgeRotation;
        } else if (curTarget != null && altTarget != null
                && clearanceOf(altTarget, tornadoes) > clearanceOf(curTarget, tornadoes) + FLIP_HYSTERESIS) {
            dodgeRotation = -dodgeRotation;
        } else if (dc != null && curTarget != null && altTarget != null
                && distToDanger(altTarget, dc) > distToDanger(curTarget, dc) + DANGER_FLIP_MARGIN) {
            dodgeRotation = -dodgeRotation;
        }
        int[] dest = dodgeRotation > 0 ? targetCCW : targetCW;

        if (dest == null) {
            dest = chooseBestReachableBfs(prev, px, py, tornadoes, tiles, bossX, bossY);
        }
        if (dest == null) {
            dest = findNearestSafeTile(tiles, px, py, tornadoes, bossX, bossY);
        }
        return dest;
    }

    /**
     * Next waypoint when circling the perimeter in rotational direction {@code rot} (+1 = CCW, -1 = CW
     * around the arena centre (cx,cy)). Among safe tiles reachable by a CLEAR straight safe line from the
     * player — so the walk hugs the wall and commits the full multi-tile run instead of clipping the boss
     * in the middle — that angularly ADVANCE in {@code rot} and stay clear of every tornado, pick the
     * farthest (longest committed run), then the most tornado clearance, then the tile nearest the wall.
     * Returns null if nothing ahead qualifies (caller flips direction or crosses the room).
     */
    private int[] chooseRimTarget(int[][] prev, int px, int py, double cx, double cy, double[] dc,
                                  List<int[]> tornadoes, Tile[][] tiles, int bossX, int bossY, int rot) {
        int[] best = null;
        int bestTravel = -1, bestDanger = -1, bestClear = -1, bestEdge = Integer.MAX_VALUE;
        for (int x = arenaMinX; x <= arenaMaxX; x++) {
            for (int y = arenaMinY; y <= arenaMaxY; y++) {
                if (prev[x][y] == -1) continue;             // not reachable over safe ground
                if (!isStandableSafe(tiles, x, y, bossX, bossY)) continue;
                if (!isRimRouteSafe(tiles, px, py, x, y, bossX, bossY)) continue; // entire route (direct or around corner) must be 100% safe
                int travel = Math.max(Math.abs(x - px), Math.abs(y - py));
                if (travel < MIN_DODGE_TRAVEL) continue;    // NEVER pick an adjacent tile! Must be a real run!
                if (travel > RIM_LOOKAHEAD) continue;       // a committed run, capped at ~the arena span

                // Only consider tiles on the outer rim
                int edge = Math.min(Math.min(x - arenaMinX, arenaMaxX - x),
                                    Math.min(y - arenaMinY, arenaMaxY - y));
                if (edge > 1) continue;

                // Angular progress around the centre: cross-product sign is the rotation direction.
                double cross = (px - cx) * (y - cy) - (py - cy) * (x - cx);
                if (cross * rot <= 0) continue;             // not advancing the way we're circling

                int clear = minTornadoDistance(x, y, tornadoes);
                if (clear < MIN_TARGET_CLEARANCE) continue; // don't stop right next to a tornado

                // Reconstruct the path and ensure the initial steps don't step into a tornado
                List<int[]> path = reconstructPath(prev, px, py, new int[]{x, y});
                if (path.isEmpty()) continue;
                boolean pathSafe = true;
                for (int i = 0; i < Math.min(3, path.size()); i++) {
                    int[] step = path.get(i);
                    if (minTornadoDistance(step[0], step[1], tornadoes) < 2) {
                        pathSafe = false;
                        break;
                    }
                }
                if (!pathSafe) continue;

                // Prefer the far end of the run (long commit), then — bucketed so small differences don't
                // override the commit — the end farther from the danger field, then clearance, then the rim.
                int dangerBucket = dc == null ? 0 : distToDanger(new int[]{x, y}, dc) / 2;
                boolean better = travel > bestTravel
                        || (travel == bestTravel && dangerBucket > bestDanger)       // lean to the open side
                        || (travel == bestTravel && dangerBucket == bestDanger && clear > bestClear)
                        || (travel == bestTravel && dangerBucket == bestDanger && clear == bestClear && edge < bestEdge);
                if (better) {
                    bestTravel = travel;
                    bestDanger = dangerBucket;
                    bestClear = clear;
                    bestEdge = edge;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /** Among reachable tiles, the furthest-from-tornado SAFE tile, preferring the rim/corner (so we make a
     *  big committed move to open space), then the longer run. Only genuinely safe tiles are destinations. */
    private int[] chooseBestReachableBfs(int[][] prev, int px, int py, List<int[]> tornadoes,
                                         Tile[][] tiles, int bossX, int bossY) {
        int[] best = null;
        int bestDist = -1, bestEdge = Integer.MAX_VALUE, bestTravel = -1;
        int[] fallback = null;
        int fallbackDist = -1;
        for (int x = 0; x < SCENE_SIZE; x++) {
            for (int y = 0; y < SCENE_SIZE; y++) {
                if (prev[x][y] == -1) continue;
                if (!isStandableSafe(tiles, x, y, bossX, bossY)) continue; // only flee to a truly safe tile
                int travel = Math.max(Math.abs(x - px), Math.abs(y - py));
                if (travel <= 1) continue; // NEVER pick an adjacent tile under any circumstances!

                int dist = minTornadoDistance(x, y, tornadoes);
                int edge = Math.min(Math.min(x - arenaMinX, arenaMaxX - x),
                                    Math.min(y - arenaMinY, arenaMaxY - y));

                if (dist > fallbackDist && travel >= 2) {
                    fallbackDist = dist;
                    fallback = new int[]{x, y};
                }

                if (travel < MIN_DODGE_TRAVEL) continue; // Prefer at least MIN_DODGE_TRAVEL tiles away!

                boolean better = dist > bestDist
                        || (dist == bestDist && edge < bestEdge)               // prefer the rim/corner
                        || (dist == bestDist && edge == bestEdge && travel > bestTravel); // then the longer run
                if (better) {
                    bestDist = dist;
                    bestEdge = edge;
                    bestTravel = travel;
                    best = new int[]{x, y};
                }
            }
        }
        return best != null ? best : fallback;
    }

    /** Rebuild the BFS path from the player (exclusive) to {@code target} (inclusive), first step first. */
    private List<int[]> reconstructPath(int[][] prev, int px, int py, int[] target) {
        java.util.LinkedList<int[]> path = new java.util.LinkedList<>();
        int cx = target[0], cy = target[1];
        int guard = 0;
        while (!(cx == px && cy == py) && guard++ < SCENE_SIZE * SCENE_SIZE) {
            path.addFirst(new int[]{cx, cy});
            int p = prev[cx][cy];
            if (p == Integer.MAX_VALUE || p < 0) break;
            cx = p / SCENE_SIZE;
            cy = p % SCENE_SIZE;
        }
        return path;
    }

    /** A planned route: the tile to click THIS tick ({@code firstStep}), plus the overlay tiles (blue path
     *  we'll actually walk, yellow intermediate hop waypoints). All three come from one computation. */
    private static final class Route {
        final int[] firstStep;
        final int[] pathTiles; // packed (sceneX<<8|sceneY), blue
        final int[] hopTiles;  // packed (sceneX<<8|sceneY), yellow

        Route(int[] firstStep, int[] pathTiles, int[] hopTiles) {
            this.firstStep = firstStep;
            this.pathTiles = pathTiles;
            this.hopTiles = hopTiles;
        }
    }

    /**
     * The single source of truth for a dodge route to {@code dest}. Decomposes the safe BFS route into the
     * waypoints we'd actually CLICK — from each position, the farthest route node whose real movement path
     * (straight-then-diagonal, {@link #lineIsSafe}) stays safe — and returns:
     *   firstStep = the waypoint to click this tick (the farthest reachable by one safe leg, or an intermediate
     *               corner when the target needs a direction change the model can't make safely in one leg),
     *   pathTiles = the tiles actually WALKED (movement model between consecutive waypoints, not the BFS staircase),
     *   hopTiles  = the intermediate corner waypoints (every waypoint except the final target).
     * The walk and the overlay both consume this, so what's drawn is exactly what's walked. Client thread.
     */
    private Route computeRoute(Tile[][] tiles, int[][] prev, int px, int py, int[] dest, int bossX, int bossY, List<int[]> tornadoes) {
        List<int[]> path = reconstructPath(prev, px, py, dest); // safe BFS route — only used to find the hops

        List<int[]> waypoints = new ArrayList<>();
        waypoints.add(new int[]{px, py});
        List<Integer> hops = new ArrayList<>();
        int ci = px, cj = py, idx = 0, guard = 0;
        while (guard++ < path.size() + 2) {
            int fi = -1;
            int[] far = null;
            for (int k = idx; k < path.size(); k++) {
                int[] n = path.get(k);
                if (!lineIsSafe(tiles, ci, cj, n[0], n[1], bossX, bossY)) break;
                far = n;
                fi = k;
            }
            if (far == null) break;                                   // can't progress any further
            waypoints.add(far);
            if (far[0] == dest[0] && far[1] == dest[1]) break;        // reached the final target (green, not a hop)
            hops.add((far[0] << 8) | far[1]);                         // an intermediate corner (yellow)
            ci = far[0];
            cj = far[1];
            idx = fi + 1;
        }

        // Blue = the tiles actually WALKED: straight-then-diagonal movement between consecutive waypoints.
        java.util.LinkedHashSet<Integer> blue = new java.util.LinkedHashSet<>();
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            int[] a = waypoints.get(i), b = waypoints.get(i + 1);
            for (int[] t : movementPathTiles(a[0], a[1], b[0], b[1])) {
                blue.add((t[0] << 8) | t[1]);
            }
        }
        int[] blueArr = new int[blue.size()];
        int bi = 0;
        for (int v : blue) blueArr[bi++] = v;
        int[] yellow = new int[hops.size()];
        for (int i = 0; i < hops.size(); i++) yellow[i] = hops.get(i);

        // ALWAYS ensure the clicked waypoint has a 100% safe direct line from (px, py)!
        // Shortcut logic: If at ANY point we have a clear safe line to the final destination 'dest'
        // or a farther waypoint, jump straight to it instead of forcing through the intermediate hops!
        int[] firstStep = null;
        if (lineIsSafe(tiles, px, py, dest[0], dest[1], bossX, bossY)
                && (tornadoes == null || lineClearOfTornadoes(px, py, dest[0], dest[1], tornadoes, 1))) {
            firstStep = dest;
        } else if (waypoints.size() > 1) {
            // Scan waypoints backwards from farthest to nearest to take the biggest safe leap towards dest
            for (int i = waypoints.size() - 1; i >= 1; i--) {
                int[] wp = waypoints.get(i);
                if (lineIsSafe(tiles, px, py, wp[0], wp[1], bossX, bossY)
                        && (tornadoes == null || lineClearOfTornadoes(px, py, wp[0], wp[1], tornadoes, 1))) {
                    firstStep = wp;
                    break;
                }
            }
        }
        if (firstStep == null) {
            firstStep = waypoints.size() > 1 ? waypoints.get(1) : dest;
        }
        return new Route(firstStep, blueArr, yellow);
    }

    private void clearDodge() {
        dodgeActive = false;
        tornadoThreat = false;
        dodgeRotation = 0;
        currentDodgeWp = null;
        lastDodgeTick = -1; // next tornado phase re-plans on its first tick instead of waiting for a tick change
        lastWalkPx = -1;
        lastWalkPy = -1;
        lastWpX = -1;
        lastWpY = -1;
        lastDodgeWalkTick = -2;
        dodgeStuckTicks = 0;
        committedDestX = -1;
        committedDestY = -1;
        renderTargetX = -1;
        renderTargetY = -1;
        clearPathViz();
    }

    /** Drop the overlay's planned-path tiles (blue) and hop waypoints (yellow). */
    private void clearPathViz() {
        pathTiles = new int[0];
        hopTiles = new int[0];
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

    /** True if the scene tile carries the safe floor ground object. Must run on the client thread. */
    private boolean isSafeFloor(int sceneX, int sceneY) {
        int plane = Microbot.getClient().getPlane();
        Tile tile = Microbot.getClient().getScene().getTiles()[plane][sceneX][sceneY];
        if (tile == null) return false;
        GroundObject ground = tile.getGroundObject();
        return ground != null && FLOOR_TILES.contains(ground.getId());
    }

    /** True if the scene tile carries a damaging ground/game object (the Hunllef's floor attack). Client thread. */
    private boolean isOnDangerousTile(int sceneX, int sceneY) {
        int plane = Microbot.getClient().getPlane();
        if (sceneX < 0 || sceneY < 0 || sceneX >= SCENE_SIZE || sceneY >= SCENE_SIZE) return false;
        Tile tile = Microbot.getClient().getScene().getTiles()[plane][sceneX][sceneY];
        if (tile == null) return false;
        GroundObject ground = tile.getGroundObject();
        if (ground != null && DANGEROUS_TILES.contains(ground.getId())) return true;
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject go : gameObjects) {
                if (go != null && DANGEROUS_TILES.contains(go.getId())) return true;
            }
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
        if (loopCount > LOOPS_PER_TICK * 2) {
            tickHappened.set(true);
        } //failsafe
    }


    //The below three methods are triggered by subscribed events
    public void animMagicSeen() {
        if (nextPrayer == null) nextPrayer = Rs2PrayerEnum.PROTECT_MAGIC;
    }

    public void animRangeSeen() {
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

    /** True if the player is currently on a safe floor tile (not on fire, not under boss, inside arena). */
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
            return true;
        }).orElse(false);
    }

    private void checkFood() {
        if (!config.enableFood()) return;
        if (!isPlayerOnSafeTile()) return; // ONLY eat on tiles that are safe to stand on!

        int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        if (currentHp <= 0) return;

        if (currentHp < config.lowHpEatValue()) {
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
        // Don't queue an attack off the back of eating while kiting — it would path us toward the boss
        // and into the tornadoes (the re-attack is handled by the dodge when it parks on a safe tile).
        if (!tornadoThreat && (tornado == null || tornado.isEmpty()) && hasArrived(lastWalkTargetX, lastWalkTargetY)) {
            attackNeeded.set(true);
        }
    }

    private void checkPrayerPotions() {
        if (!config.enableDrink()) return;
        if (!isPlayerOnSafeTile()) return; // ONLY drink on tiles that are safe to stand on!

        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        if (currentPrayer < config.prayerPotionValue()) {
            Microbot.log(String.format("[Action] Drink potion: Egniol potion | prayer=%d/%d", currentPrayer, config.prayerPotionValue()));
            Rs2Inventory.interact("Egniol potion", "Drink");
            timeDrink = now;
        }
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
        if (!hasArrived(lastWalkTargetX, lastWalkTargetY)) return; // Still moving to the clicked tile, don't interrupt!
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
        }
    }

    public void sendPrayerToggle(Rs2PrayerEnum prayer, boolean enable) {
        if (prayer == null) return;
        Microbot.log(String.format("[Action] Prayer toggle: %s -> %s", prayer.name(), enable ? "ON" : "OFF"));
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

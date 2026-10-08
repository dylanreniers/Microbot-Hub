package net.runelite.client.plugins.custom.customgauntlet;

import com.google.inject.Inject;

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

    // --- 12-Tile Safe Lane Shuttle System ---
    private static volatile int[] safeLaneTileA = null;
    private static volatile int[] safeLaneTileB = null;
    private static volatile int[] currentShuttleTarget = null;
    private static volatile List<int[]> currentLaneTiles = new ArrayList<>();
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
     * A scene tile is "under" the boss when it falls within its 5x5 footprint, optionally expanded by {@code margin}.
     * Uses the NPC's WorldArea in scene space for exact tile boundaries when available, with LocalPoint center fallback.
     */
    private boolean isUnderBoss(int sceneX, int sceneY, int bossSceneX, int bossSceneY, int margin) {
        if (hunllef != null) {
            WorldArea area = hunllef.getWorldArea();
            if (area != null) {
                WorldView wv = Microbot.getClient().getTopLevelWorldView();
                LocalPoint lp = LocalPoint.fromWorld(wv, area.getX(), area.getY());
                if (lp != null) {
                    int swX = lp.getSceneX();
                    int swY = lp.getSceneY();
                    int minX = swX - margin;
                    int maxX = swX + area.getWidth() - 1 + margin;
                    int minY = swY - margin;
                    int maxY = swY + area.getHeight() - 1 + margin;
                    return sceneX >= minX && sceneX <= maxX && sceneY >= minY && sceneY <= maxY;
                }
            }
        }
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
    /**
     * 12-Tile Safe Lane Shuttle System:
     * - Shuttles back and forth between two safe endpoints of a full-length 12-tile lane (outer rim prioritized).
     * - Only initiates movement when tornadoes close to gap <= 3.
     * - Otherwise stands still at the endpoint attacking Hunllef, eating food, drinking potions, and switching prayers.
     * - If the floor pattern changes mid-sprint: continues if distance <= 6 (arrives in <= 3 ticks before damage),
     *   or adapts to the new safe lane if farther away.
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

            // Continuously update/verify the 12-tile safe lane
            updateSafeLaneContinuous();
            if (safeLaneTileA == null || safeLaneTileB == null) {
                return false;
            }

            int nearest = minTornadoDistance(px, py, tornadoes);

            // Anchor target if not set
            if (currentShuttleTarget == null) {
                int distA = Math.max(Math.abs(safeLaneTileA[0] - px), Math.abs(safeLaneTileA[1] - py));
                int distB = Math.max(Math.abs(safeLaneTileB[0] - px), Math.abs(safeLaneTileB[1] - py));
                currentShuttleTarget = distA <= distB ? safeLaneTileA : safeLaneTileB;
            }

            int distToTarget = Math.max(Math.abs(currentShuttleTarget[0] - px), Math.abs(currentShuttleTarget[1] - py));
            boolean atTarget = (distToTarget <= 1) || hasArrived(currentShuttleTarget[0], currentShuttleTarget[1]);

            if (atTarget) {
                // Standing at the safe lane endpoint
                if (nearest > 3) {
                    // Tornadoes are at gap > 3: STAND STILL AND ATTACK HUNLLEF!
                    boolean canAttackBoss = config.autoAttack() && hunllef != null
                            && (isBowEquipped() || isStaffEquipped() || (isHalberdEquipped() && Math.max(Math.abs(px - bossX), Math.abs(py - bossY)) <= 2));
                    boolean passed5Ticks = (gameTickCount - lastAttackTick >= 5) || (now - timeAttack >= 3000);

                    if (passed5Ticks && canAttackBoss && !isAttackingBoss()) {
                        Microbot.log(String.format("[Action] Standing clear at endpoint (gap %d > 3) - attacking Hunllef", nearest));
                        hunllef.click("attack");
                        timeAttack = now;
                        lastAttackTick = gameTickCount;
                    }

                    // Pre-hover over the opposite endpoint so the next sprint click lands instantly!
                    int[] opposite = (currentShuttleTarget[0] == safeLaneTileA[0] && currentShuttleTarget[1] == safeLaneTileA[1])
                            ? safeLaneTileB : safeLaneTileA;
                    hoverSceneTile(opposite[0], opposite[1]);
                    // Return false so checkFood(), checkPrayerPotions() execute freely!
                    return false;
                } else {
                    // Tornadoes reached gap <= 3: SPRINT TO THE OPPOSITE ENDPOINT!
                    int[] opposite = (currentShuttleTarget[0] == safeLaneTileA[0] && currentShuttleTarget[1] == safeLaneTileA[1])
                            ? safeLaneTileB : safeLaneTileA;
                    currentShuttleTarget = opposite;
                    lastWalkTargetX = currentShuttleTarget[0];
                    lastWalkTargetY = currentShuttleTarget[1];
                    Microbot.log(String.format("[Action] Tornado gap %d <= 3 - sprinting to opposite endpoint (%d,%d)",
                            nearest, currentShuttleTarget[0], currentShuttleTarget[1]));
                    Rs2Walker.walkFastLocal(LocalPoint.fromScene(currentShuttleTarget[0], currentShuttleTarget[1]));
                    return true;
                }
            } else {
                // En route toward currentShuttleTarget:
                // Check if current segment is still fully safe
                boolean segmentStillSafe = isSegmentFullySafe(safeLaneTileA, safeLaneTileB, bossX, bossY);
                if (!segmentStillSafe) {
                    // If within 6 tiles of target, we will arrive in <= 3 ticks (before damage activates)! Keep running!
                    if (distToTarget <= 6) {
                        Rs2Walker.walkFastLocal(LocalPoint.fromScene(currentShuttleTarget[0], currentShuttleTarget[1]));
                        return true;
                    } else {
                        // More than 6 tiles away: immediately switch to new safe segment
                        safeLaneTileA = null;
                        safeLaneTileB = null;
                        currentShuttleTarget = null;
                        updateSafeLaneContinuous();
                        if (currentShuttleTarget != null) {
                            lastWalkTargetX = currentShuttleTarget[0];
                            lastWalkTargetY = currentShuttleTarget[1];
                            Rs2Walker.walkFastLocal(LocalPoint.fromScene(currentShuttleTarget[0], currentShuttleTarget[1]));
                            return true;
                        }
                    }
                }

                // Continue sprint to current target
                lastWalkTargetX = currentShuttleTarget[0];
                lastWalkTargetY = currentShuttleTarget[1];
                Rs2Walker.walkFastLocal(LocalPoint.fromScene(currentShuttleTarget[0], currentShuttleTarget[1]));
                return true;
            }
        }).orElse(false);

        return result;
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

    /**
     * Continuously evaluates and discovers the best safe straight segment across the arena:
     * - Every tile in the segment must be 100% safe (in arena, safe floor, not fire, not under Hunllef 5x5).
     * - The 2 endpoints have the highest distance between them while having the same X or same Y.
     * - Prioritizes outer rim segments when available.
     * - Retains current segment if still 100% safe and near max distance (hysteresis).
     */
    public void updateSafeLaneContinuous() {
        if (!arenaBoundsKnown || hunllef == null) return;
        Player local = Microbot.getClient().getLocalPlayer();
        if (local == null || local.getLocalLocation() == null) return;
        int px = local.getLocalLocation().getSceneX();
        int py = local.getLocalLocation().getSceneY();
        LocalPoint bossLp = hunllef.getLocalLocation();
        if (bossLp == null) return;
        int bossX = bossLp.getSceneX();
        int bossY = bossLp.getSceneY();

        List<SafeSegment> segments = new ArrayList<>();
        int maxDistFound = 0;

        // 1. Scan all horizontal rows (same Y)
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
                            SafeSegment seg = new SafeSegment(new int[]{segStart, y}, new int[]{segEnd, y}, rimDist, true);
                            segments.add(seg);
                            maxDistFound = Math.max(maxDistFound, seg.dist);
                        }
                        segStart = -1;
                    }
                }
            }
            if (segStart != -1) {
                int segEnd = arenaMaxX;
                if (segEnd > segStart) {
                    SafeSegment seg = new SafeSegment(new int[]{segStart, y}, new int[]{segEnd, y}, rimDist, true);
                    segments.add(seg);
                    maxDistFound = Math.max(maxDistFound, seg.dist);
                }
            }
        }

        // 2. Scan all vertical columns (same X)
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
                            SafeSegment seg = new SafeSegment(new int[]{x, segStart}, new int[]{x, segEnd}, rimDist, false);
                            segments.add(seg);
                            maxDistFound = Math.max(maxDistFound, seg.dist);
                        }
                        segStart = -1;
                    }
                }
            }
            if (segStart != -1) {
                int segEnd = arenaMaxY;
                if (segEnd > segStart) {
                    SafeSegment seg = new SafeSegment(new int[]{x, segStart}, new int[]{x, segEnd}, rimDist, false);
                    segments.add(seg);
                    maxDistFound = Math.max(maxDistFound, seg.dist);
                }
            }
        }

        if (segments.isEmpty()) return;

        // 3. Hysteresis: If current segment is still 100% safe and has the maximum distance, KEEP IT!
        if (safeLaneTileA != null && safeLaneTileB != null) {
            int currentDist = Math.max(Math.abs(safeLaneTileB[0] - safeLaneTileA[0]), Math.abs(safeLaneTileB[1] - safeLaneTileA[1]));
            if (currentDist >= maxDistFound && isSegmentFullySafe(safeLaneTileA, safeLaneTileB, bossX, bossY)) {
                return; // Keep current safe segment, no jitter!
            }
        }

        // 4. Filter strictly to segments with the highest distance
        int finalMaxDist = maxDistFound;
        List<SafeSegment> bestCandidates = segments.stream()
                .filter(s -> s.dist == finalMaxDist)
                .collect(Collectors.toList());

        // 5. Select the best segment:
        //    High distance is paramount: seg.dist * 100
        //    Outer rim priority: (12 - seg.rimDist) * 20
        //    Proximity to player: -perpDist
        SafeSegment best = null;
        int bestScore = Integer.MIN_VALUE;

        for (SafeSegment seg : bestCandidates) {
            int perpDist = seg.horizontal ? Math.abs(py - seg.p1[1]) : Math.abs(px - seg.p1[0]);
            int score = (seg.dist * 100) + ((12 - seg.rimDist) * 20) - perpDist;
            if (score > bestScore) {
                bestScore = score;
                best = seg;
            }
        }

        if (best != null) {
            safeLaneTileA = best.p1;
            safeLaneTileB = best.p2;

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

            // Anchor currentShuttleTarget to the endpoint closest to player if not set or if current target is invalid
            if (currentShuttleTarget == null || (!Arrays.equals(currentShuttleTarget, safeLaneTileA) && !Arrays.equals(currentShuttleTarget, safeLaneTileB))) {
                int distA = Math.max(Math.abs(safeLaneTileA[0] - px), Math.abs(safeLaneTileA[1] - py));
                int distB = Math.max(Math.abs(safeLaneTileB[0] - px), Math.abs(safeLaneTileB[1] - py));
                currentShuttleTarget = distA <= distB ? safeLaneTileA : safeLaneTileB;
            }
        }
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

    private void clearDodge() {
        dodgeActive = false;
        tornadoThreat = false;
        lastDodgeTick = -1;
        currentShuttleTarget = null;
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
            if (ghState == State.FIGHTING && arenaBoundsKnown && hunllef != null) {
                updateSafeLaneContinuous();
            }
        }
    }

    public void onGroundObjectDespawned(GroundObjectDespawned event) {
        if (event == null || event.getGroundObject() == null) return;
        int id = event.getGroundObject().getId();
        if (DANGEROUS_TILES.contains(id)) {
            if (ghState == State.FIGHTING && arenaBoundsKnown && hunllef != null) {
                updateSafeLaneContinuous();
            }
        }
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

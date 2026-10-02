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
    private static final int REGULAR_HUNLEFF_TORNADO = 9039;
    private static final int PADDLEFISH_HEAL_VALUE = 20;

    private static final int CD_NPC = 450;
    private static final int CD_PRAYOFFENCE = 100;
    private static final int CD_EAT = 600;
    private static final int CD_DRINK = 600;
    private static final int CD_WEAPON = 600;
    private static final int CD_RECENTLY = 25;
    private static final int CD_STEEL = 300;
    private static final int CD_HUNLLEF_LOG = 2000;
    private static final int CD_SAFESPOT = 600;
    private static final int BUFFER_MAGICBLAST = 400;
    private static final int LOOPS_PER_TICK = 5;

    private static final int SAFE_TILE_GROUND_ID = 36149; // CG floor tile that is safe to stand on (but present across the whole gauntlet, not just the arena)
    private static final int HUNLLEF_RADIUS = 2; // 5x5 footprint -> 2 tiles from its centre to each edge
    private static final int BARRIER_ID = 37339; // the 4 barriers that sit just outside the 12x12 Hunllef arena

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
    private Rs2NpcModel tornado = null;
    private final AtomicBoolean attackNeeded = new AtomicBoolean(false);

    private int loopCount = 0;

    private boolean hpWentUp = false;
    private boolean prayWentUp = false;
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
    private long magicBlastEnd = -1;

    // Arena bounds in scene coords, derived once per fight from the 4 barriers. The floor tile id is
    // not unique to the arena, so these bounds are what actually keep retreats inside the room.
    private boolean arenaBoundsKnown = false;
    private int arenaMinX, arenaMaxX, arenaMinY, arenaMaxY;

    private final AtomicBoolean tickHappened = new AtomicBoolean(false);

    private CustomGauntletConfig config;
    private CustomGauntletPlugin plugin;

    @Inject
    public CustomGauntletScript(CustomGauntletPlugin plugin, CustomGauntletConfig config) {
        this.config = config;
        this.plugin = plugin;
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
                        checkVitalsStart();
                        if (now - timeHunllefLog > CD_HUNLLEF_LOG) {
                            logHunllefLocation();
                        }

                        //Fast Section
                        checkProtectPrayers();
                        //if (now - timePrayProtect > CD_PRAYPROTECT) checkProtectPrayers();
                        if (now - magicBlastEnd < BUFFER_MAGICBLAST)
                            checkProtectPrayers(); //turbo check after magic blast happens
                        if (now - timePrayOffence > CD_PRAYOFFENCE) checkAttackPrayers();
                        if (now - timeSteel > CD_STEEL) checkSteelSkin();
                        if ((now - timePrayProtect < CD_RECENTLY) || (now - timePrayOffence < CD_RECENTLY) || (now - timeSteel < CD_RECENTLY))
                            break; //Prayers changed, return simulates small sleep

                        //Slow Section
                        if (now - timeEatAttempted > CD_EAT) checkFood();
                        if (now - timeEatAttempted < CD_RECENTLY) {
                            checkPrayerPotions();
                            break;
                        } //Eating action occurred, return simulates sleep. Combo drink attempted
                        if (now - timeDrink > CD_DRINK) checkPrayerPotions(); //Non-Blocking

                        if (config.runToSafespot() && tornado == null && handleSafespot())
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
        // getDistanceFromPlayer() is instance-safe: it compares LocalPoint-vs-LocalPoint (same scene
        // space) and returns tiles. Do NOT use getWorldLocation().distanceTo(Rs2Player.getWorldLocation())
        // here - that mixes raw-scene (NPC) with template (player) and returns a bogus constant.
        Microbot.log("Hunllef " + hunllef.getDistanceFromPlayer() + " tiles to center"
                + " | under=" + isPlayerUnderHunllef(0)
                + " | underOrAdjacent=" + isPlayerUnderHunllef(1));
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
        if (Rs2Player.isMoving()) return false; // let the current path (ours or manual) finish
        if (now - timeSafespot < CD_SAFESPOT) return false;
        timeSafespot = now;
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

            if (!arenaBoundsKnown) {
                computeArenaBounds();
            }
            if (!arenaBoundsKnown) {
                return false; // can't guarantee staying in the room without the barriers; do nothing
            }

            int bossX = bossLp.getSceneX(), bossY = bossLp.getSceneY();
            int playerX = playerLp.getSceneX(), playerY = playerLp.getSceneY();

            // already safe: inside the arena, on a floor tile and clear of the footprint
            if (inArena(playerX, playerY) && isSafeFloor(playerX, playerY)
                    && !isUnderBoss(playerX, playerY, bossX, bossY, 0)) {
                return false;
            }

            LocalPoint best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Tile tile : Rs2GameObject.getTiles()) {
                GroundObject ground = tile.getGroundObject();
                if (ground == null || ground.getId() != SAFE_TILE_GROUND_ID) {
                    continue;
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
                int distance = Math.max(Math.abs(tx - playerX), Math.abs(ty - playerY));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = lp;
                }
            }
            if (best == null) {
                return false;
            }
            Rs2Walker.walkFastLocal(best);
            logVerbose("Running to safespot at scene " + best.getSceneX() + "," + best.getSceneY());
            return true;
        }).orElse(false);
    }

    /** True if the scene tile carries the safe floor ground object. Must run on the client thread. */
    private boolean isSafeFloor(int sceneX, int sceneY) {
        int plane = Microbot.getClient().getPlane();
        Tile tile = Microbot.getClient().getScene().getTiles()[plane][sceneX][sceneY];
        if (tile == null) return false;
        GroundObject ground = tile.getGroundObject();
        return ground != null && ground.getId() == SAFE_TILE_GROUND_ID;
    }

    /**
     * Derives the 12x12 arena from the 4 barriers (id {@link #BARRIER_ID}) that sit just outside it:
     * the arena is their scene bounding box shrunk by one tile on every side. Cached once per fight
     * since the barriers never move. Must run on the client thread.
     */
    private void computeArenaBounds() {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int found = 0;
        for (Tile tile : Rs2GameObject.getTiles()) {
            GameObject[] gameObjects = tile.getGameObjects();
            if (gameObjects == null) continue;
            boolean barrier = false;
            for (GameObject go : gameObjects) {
                if (go != null && go.getId() == BARRIER_ID) {
                    barrier = true;
                    break;
                }
            }
            if (!barrier) continue;
            LocalPoint lp = tile.getLocalLocation();
            if (lp == null) continue;
            int sx = lp.getSceneX(), sy = lp.getSceneY();
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
            found++;
        }
        if (found < 4) return; // need all 4 barriers loaded before we trust the bounds
        arenaMinX = minX + 1;
        arenaMaxX = maxX - 1;
        arenaMinY = minY + 1;
        arenaMaxY = maxY - 1;
        arenaBoundsKnown = true;
        logVerbose("Arena bounds scene x[" + arenaMinX + "," + arenaMaxX + "] y[" + arenaMinY + "," + arenaMaxY + "]");
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
        tornado = Microbot.getRs2NpcCache().query().where(npc -> TORNADO_IDS.contains(npc.getId())).nearestOnClientThread();
        hunllef = Microbot.getRs2NpcCache().query().where(npc -> HUNLLEF_IDS.contains(npc.getId())).nearestOnClientThread();

        if (hunllef == null && ghState == State.FIGHTING) {
            ghState = State.IDLE;
            Rs2Prayer.disableAllPrayers();
            nextPrayer = null;
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
        if (Rs2Player.isMoving()) return; // don't swap gear mid-dodge; it interferes with pathing
        if (tornado != null) return; // don't swap gear while tornados are active; keep pathing free
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
                Rs2Inventory.equip(id);
                logVerbose("weapon change attempted");
                if (!Rs2Player.isMoving()) {
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

    private void checkSteelSkin() {
        if (config.higherPrayers()) {
            return;
        }
        if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.STEEL_SKIN) && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PIETY)) {
            sendPrayerToggle(Rs2PrayerEnum.STEEL_SKIN, true);
            timeSteel = now;
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
        if (!Rs2Player.isMoving()) {
            attackNeeded.set(true);
        }
    }

    //------------------------------------------------------------------------------------------------------------------
    //---Stats

    private void checkVitalsStart() {
        int hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        if (hp > STAT_HP) {
            hpWentUp = true;
        }
        int pray = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        if (pray > STAT_PRAYER) {
            prayWentUp = true;
        }

    }

    private void checkFood() {
        int currentHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
        int missingHp = maxHp - currentHp;
        if (currentHp <= 0) return;
        if (missingHp < (PADDLEFISH_HEAL_VALUE - config.eatOverhealValue())) return;
        if (config.eatFoodChain()) {
            eatFood();
        } //Continue eating once started
        if (currentHp < config.lowHpEatValue()) {
            eatFood();
        }
        if (config.tornadoCheck() && (tornado == null)) {
            return;
        }
        if (config.eatFoodMoving() && Rs2Player.isMoving()) {
            eatFood();
        }
    }

    private void eatFood() {
        if (!config.enableFood()) return;
        if (now - timeEatAttempted < CD_RECENTLY) return;
        Rs2Inventory.interact("Paddlefish", "Eat");
        logVerbose("Eat attempted");
        timeEatAttempted = now;
        if (!Rs2Player.isMoving()) {
            attackNeeded.set(true);
        }
    }

    private void checkPrayerPotions() {
        if (!config.enableDrink()) return;
        if (Rs2Player.isMoving()) return; // don't drink mid-dodge; it interferes with pathing
        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        if (currentPrayer < config.prayerPotionValue()) {
            Rs2Inventory.interact("Egniol potion", "Drink");
            timeDrink = now;
        }
    }

    private void checkAttack() {
        if (!config.autoAttack()) return;
        if (hunllef == null) return;
        if (Rs2Player.isMoving()) return;
        if (tornado != null) return; // hold attacks while tornados are active so the re-attack flag survives for when they clear
        if (attackNeeded.compareAndSet(true, false)) {
            if (now - timeEatAttempted > (CD_EAT * 3)) {
                logVerbose("Attempting attack");
                hunllef.click("attack");
                timeAttack = now;
            }
        }
    }

    public void sendPrayerToggle(Rs2PrayerEnum prayer, boolean enable) {
        if (prayer == null) return;
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

package net.runelite.client.plugins.custom.customgauntlet.prep;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.custom.customgauntlet.CustomGauntletConfig;
import net.runelite.client.plugins.custom.customgauntlet.CustomGauntletPlugin;
import net.runelite.client.plugins.custom.customgauntlet.CustomGauntletScript;
import net.runelite.client.plugins.custom.customgauntlet.State;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Handles full Tier 2 automated preparation for The Gauntlet:
 * - Phase 1: Resource gathering (7 ore, 7 bark, 7 linum, 3 leaves, 16 fish, 1 weapon frame), tool drop optimization.
 * - Phase 2: Dropping fish at base, crafting T1 armor + T2 staff + teleport crystal, hunting Demibosses (Dragon & Dark Beast, or 2 Bears).
 * - Phase 3: Shard accumulation (550), crafting T3 weapons + T2 armor, potion brewing, cooking 24 fish, and entering boss arena.
 */
@Getter
@Setter
public class CustomGauntletPrepScript extends Script {

    public enum PrepPhase {
        PHASE_1_RESOURCES,
        PHASE_2_WEAPONS_AND_ARMOR,
        PHASE_3_BOSS_PREP,
        COMPLETE
    }

    // Object IDs
    public static final Set<Integer> SINGING_BOWL_IDS = Set.of(35966, 36063);
    public static final Set<Integer> WATER_PUMP_IDS = Set.of(35981, 36078);
    public static final Set<Integer> RANGE_STOVE_IDS = Set.of(35980, 36077);
    public static final Set<Integer> TOOL_STORAGE_IDS = Set.of(35977, 36074);
    public static final Set<Integer> HUNLLEF_BARRIER_IDS = Set.of(35982, 36079);
    public static final Set<Integer> ROOM_DOOR_IDS = Set.of(37339);
    public static final Set<Integer> GAUNTLET_ENTRY_IDS = Set.of(37340); // "The Gauntlet" lobby entry (enter to start)
    private static final int NODE_SEARCH_TILES = 12; // only light nodes within the current room (reachable without crossing walls)
    private static final int GATHER_SEARCH_TILES = 48; // reach resources/mobs several rooms out; game click-to-walk paths there
    public static final Set<Integer> NODE_IDS = Set.of(35998, 35999, 36101, 36102);
    public static final Set<Integer> LIT_NODE_IDS = Set.of(35992, 36095);

    public static final Set<Integer> ORE_DEPOSIT_IDS = Set.of(35967, 36064);
    public static final Set<Integer> PHREN_ROOTS_IDS = Set.of(35969, 36066);
    public static final Set<Integer> LINUM_TIRINUM_IDS = Set.of(35975, 36072);
    public static final Set<Integer> GRYM_ROOT_IDS = Set.of(35973, 36070);
    public static final Set<Integer> FISHING_SPOT_IDS = Set.of(35971, 36068);

    // NPC IDs
    public static final Set<Integer> WEAK_MOB_IDS = Set.of(9026, 9027, 9028, 9040, 9041, 9042); // Rat, Bat, Spider
    public static final Set<Integer> MEDIUM_MOB_IDS = Set.of(9029, 9030, 9031, 9043, 9044, 9045); // Scorpion, Unicorn, Wolf
    public static final Set<Integer> DRAGON_IDS = Set.of(9033, 9047);
    public static final Set<Integer> DARK_BEAST_IDS = Set.of(9034, 9048);
    public static final Set<Integer> BEAR_IDS = Set.of(9032, 9046);

    // Tools
    public static final Set<Integer> PICKAXE_IDS = Set.of(23863);
    public static final Set<Integer> AXE_IDS = Set.of(23862);
    public static final Set<Integer> HARPOON_IDS = Set.of(23864);
    public static final Set<Integer> PESTLE_IDS = Set.of(23865);

    private final CustomGauntletPlugin plugin;
    private final CustomGauntletConfig config;

    private GauntletGrid grid = new GauntletGrid();
    private GauntletChecklist checklist = new GauntletChecklist();
    private PrepPhase currentPhase = PrepPhase.PHASE_1_RESOURCES;

    private boolean initialized = false;

    @Inject
    public CustomGauntletPrepScript(CustomGauntletPlugin plugin, CustomGauntletConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean run() {
        // Guard against stacking loops: the plugin calls run() on startUp AND on the autoPrep config change,
        // and each call would schedule another concurrent loop (we saw PrepScript-1..5 all running at once).
        if (mainScheduledFuture != null && !mainScheduledFuture.isDone()) {
            return true;
        }
        // The client's "can't reach target" recovery walks to object.getWorldLocation(), which is broken in
        // the Gauntlet instance (world coords don't map), so it exhausts its retries and PAUSES all scripts
        // ("tried to interact N times"). The game's own click-to-walk reaches objects fine inside the
        // instance, so disable that recovery here and clear any pause it already left behind.
        Microbot.isCantReachTargetDetectionEnabled = false;
        Microbot.cantReachTarget = false;
        Microbot.cantReachTargetRetries = 0;
        Microbot.pauseAllScripts.set(false);
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                // Sync state with autoPrep config
                if (config.autoPrep() && CustomGauntletScript.ghState != State.FIGHTING && CustomGauntletScript.ghState != State.BOSS_DEAD) {
                    CustomGauntletScript.ghState = State.PREP;
                }

                // Only run during PREP state
                if (CustomGauntletScript.ghState != State.PREP) return;

                // If we're still in the Gauntlet LOBBY, enter the Gauntlet first — prep runs inside the maze,
                // and the lobby has no nodes/resources (so otherwise the script just idles here).
                TileObject lobbyEntry = findTileObject(GAUNTLET_ENTRY_IDS, 10);
                if (lobbyEntry != null) {
                    if (!Rs2Player.isMoving()) {
                        Microbot.log("[Prep] In Gauntlet lobby - entering to begin prep");
                        if (!Rs2GameObject.interact(lobbyEntry, "Enter-corrupted")
                                && !Rs2GameObject.interact(lobbyEntry, "Enter")) {
                            Rs2GameObject.interact(lobbyEntry);
                        }
                        sleep(3000);
                    }
                    return; // wait until we're inside before initializing the base
                }

                // Initialize base location once inside dungeon
                if (!initialized) {
                    WorldPoint playerLocation = Rs2Player.getWorldLocation();
                    if (playerLocation != null) {
                        grid.setBaseCenterPoint(playerLocation);
                        initialized = true;
                        Microbot.log("[Prep] Initialized starting base at " + playerLocation);
                    } else {
                        return;
                    }
                }

                switch (currentPhase) {
                    case PHASE_1_RESOURCES:
                        executePhase1Resources();
                        break;
                    case PHASE_2_WEAPONS_AND_ARMOR:
                        executePhase2WeaponsAndArmor();
                        break;
                    case PHASE_3_BOSS_PREP:
                        executePhase3BossPrep();
                        break;
                    case COMPLETE:
                        handoverToBossFight();
                        break;
                }

            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        Microbot.isCantReachTargetDetectionEnabled = true; // restore the global default we disabled for the instance
        super.shutdown();
    }

    /**
     * Phase 1: Resource Gathering
     * - Collects 7 ore, 7 bark, 7 linum, 3 leaves, 16 paddlefish, 1 weapon frame.
     * - Drops pickaxe and axe once respective thresholds are reached.
     * - Teleports back to base upon completion.
     */
    private void executePhase1Resources() {
        // 1. Tool drop checks to preserve inventory space
        if (checklist.getOreCount() >= GauntletChecklist.TARGET_ORE && PICKAXE_IDS.stream().anyMatch(Rs2Inventory::hasItem)) {
            Microbot.log("[Prep] 7 Ore reached -> Dropping pickaxe");
            Rs2Inventory.drop(PICKAXE_IDS::contains);
            return;
        }
        if (checklist.getBarkCount() >= GauntletChecklist.TARGET_BARK && AXE_IDS.stream().anyMatch(Rs2Inventory::hasItem)) {
            Microbot.log("[Prep] 7 Bark reached -> Dropping axe");
            Rs2Inventory.drop(AXE_IDS::contains);
            return;
        }

        // 2. Phase 1 checklist complete? Head to base and move on.
        if (checklist.isPhase1MaterialsComplete()) {
            Microbot.log("[Prep] Phase 1 checklist complete! Teleporting to starting base.");
            teleportToBase();
            currentPhase = PrepPhase.PHASE_2_WEAPONS_AND_ARMOR;
            return;
        }

        // 3. Loot ground items in current room if available
        if (Rs2GroundItem.loot("Weapon frame", 15)
                || Rs2GroundItem.loot("Crystal shards", 15)
                || Rs2GroundItem.loot("Corrupted shards", 15)
                || Rs2GroundItem.loot("Teleport crystal", 15)) {
            return;
        }

        // 4. Weapon frames (need 2): kill a NEARBY weak mob (bounded so we don't walk off toward an
        //    unreachable one). Frames drop from the kill, looted above.
        if (checklist.getWeaponFrameCount() == 0) {
            Rs2NpcModel weakMob = Microbot.getRs2NpcCache().query()
                    .where(npc -> WEAK_MOB_IDS.contains(npc.getId())).nearest();
            if (weakMob != null && !weakMob.isDead() && npcTileDist(weakMob) <= GATHER_SEARCH_TILES) {
                if (!Rs2Player.isInteracting()) {
                    Microbot.log("[Prep] Attacking weak mob for weapon frame");
                    weakMob.click("Attack");
                }
                return;
            }
        }

        // 5. Gather any still-needed resource that is REACHABLE in the current accessible area.
        if (checklist.oreRemaining() > 0 && gatherNearestObject(ORE_DEPOSIT_IDS, "Mine")) return;
        if (checklist.barkRemaining() > 0 && gatherNearestObject(PHREN_ROOTS_IDS, "Chop")) return;
        if (checklist.linumRemaining() > 0 && gatherNearestObject(LINUM_TIRINUM_IDS, "Pick")) return;
        if (checklist.leafRemaining() > 0 && gatherNearestObject(GRYM_ROOT_IDS, "Pick")) return;
        if (checklist.phase1FishRemaining() > 0 && gatherNearestObject(FISHING_SPOT_IDS, "Harpoon")) return;

        // 6. Nothing needed is reachable here -> EXPAND the accessible area: light the nearest reachable
        //    unlit node (opens a room), else pass the nearest barrier into a new room. This replaces the
        //    world-coordinate grid navigation, which is unreliable in the instance.
        if (scoutBaseRooms()) return;
        if (passNearestBarrier()) return;

        Microbot.log("[Prep] Phase 1: nothing reachable to gather/light/pass right now - waiting for a path to open");
    }

    /** Pass through the nearest barrier (37339 etc., not the Hunllef barrier) into an adjacent room. */
    private boolean passNearestBarrier() {
        TileObject door = findNearestDoorTowardsRoom(null); // room param is unused; returns nearest barrier
        if (door == null) return false;
        if (!Rs2Player.isMoving() && !Rs2Player.isInteracting()) {
            Microbot.log("[Prep] Passing barrier " + door.getId() + " into a new room");
            if (!Rs2GameObject.interact(door, "Quick-pass")
                    && !Rs2GameObject.interact(door, "Pass")
                    && !Rs2GameObject.interact(door, "Open")) {
                Rs2GameObject.interact(door);
            }
        }
        return true;
    }

    /** Instance-safe Chebyshev tile distance from the player to an NPC (local/scene coords). */
    private int npcTileDist(Rs2NpcModel npc) {
        if (npc == null) return Integer.MAX_VALUE;
        LocalPoint o = npc.getLocalLocation();
        Player p = Microbot.getClient().getLocalPlayer();
        LocalPoint pl = p == null ? null : p.getLocalLocation();
        if (o == null || pl == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(pl.getSceneX() - o.getSceneX()), Math.abs(pl.getSceneY() - o.getSceneY()));
    }

    /**
     * Phase 2: Armour Crafting & Demiboss Hunting
     * - Drops 16 paddlefish on base floor.
     * - Crafts T1 armour, T2 staff, 1 teleport crystal at Singing Bowl.
     * - Hunts Dragon (Pray Mage) and Dark Beast (Pray Range) on outer rim.
     * - Incorporates 2-Bear fallback if needed.
     */
    private void executePhase2WeaponsAndArmor() {
        // Step 1: Drop 16 paddlefish on floor at starting base
        if (GauntletChecklist.RAW_FISH_IDS.stream().anyMatch(Rs2Inventory::hasItem)) {
            Microbot.log("[Prep] Dropping paddlefish at starting base floor to free inventory");
            int fishCount = Rs2Inventory.count(GauntletChecklist.RAW_FISH_IDS::contains);
            Rs2Inventory.dropAll(GauntletChecklist.RAW_FISH_IDS::contains);
            checklist.setDroppedFishAtBase(checklist.getDroppedFishAtBase() + fishCount);
            return;
        }

        // Step 2: Craft T1 Armour + T2 Staff + Teleport crystal at Singing Bowl if not yet crafted
        if (!hasT1ArmourEquipped() || !hasT2StaffEquipped()) {
            TileObject bowl = findTileObject(SINGING_BOWL_IDS);
            if (bowl != null) {
                craftStartingGear(bowl);
                return;
            }
        }

        // Step 3: Check if both weapon components acquired
        if (checklist.hasBothWeaponComponents()) {
            Microbot.log("[Prep] Both weapon components acquired! Transitioning to Phase 3.");
            currentPhase = PrepPhase.PHASE_3_BOSS_PREP;
            return;
        }

        // Step 4: Hunt Demibosses
        Rs2NpcModel dragon = Microbot.getRs2NpcCache().query().where(npc -> DRAGON_IDS.contains(npc.getId())).nearest();
        if (dragon != null && !dragon.isDead() && !checklist.hasOrb()) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
            if (!Rs2Player.isInteracting()) {
                Microbot.log("[Prep] Engaging Corrupted Dragon (Protect from Magic)");
                dragon.click("Attack");
            }
            return;
        }

        Rs2NpcModel darkBeast = Microbot.getRs2NpcCache().query().where(npc -> DARK_BEAST_IDS.contains(npc.getId())).nearest();
        if (darkBeast != null && !darkBeast.isDead() && !checklist.hasBowstring()) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, true);
            if (!Rs2Player.isInteracting()) {
                Microbot.log("[Prep] Engaging Corrupted Dark Beast (Protect from Range)");
                darkBeast.click("Attack");
            }
            return;
        }

        // 2-Bear Fallback: If 1 component acquired and 2 bears known/nearby
        if ((checklist.hasOrb() ^ checklist.hasBowstring()) && grid.getKnownBearCount() >= 2) {
            Rs2NpcModel bear = Microbot.getRs2NpcCache().query().where(npc -> BEAR_IDS.contains(npc.getId())).nearest();
            if (bear != null && !bear.isDead()) {
                Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
                if (!Rs2Player.isInteracting()) {
                    Microbot.log("[Prep] 2-Bear fallback active: engaging Bear for missing component");
                    bear.click("Attack");
                }
                return;
            }
        }

        // Step 5: Scout unexplored outer rim rooms
        scoutOuterRim();
    }

    /**
     * Phase 3: Boss Fight Consolidation
     * - Accumulates 550 total shards.
     * - Sings T3 weapons + T2 armour upgrades.
     * - Crushes 30 dust & brews 3 Egniol potions.
     * - Picks up and cooks all 24 paddlefish.
     * - Toggles Protect from Range and enters the Hunllef arena.
     */
    private void executePhase3BossPrep() {
        // Step 1: Ensure 550 total shards accumulated
        if (checklist.getTotalShardsEarned() < GauntletChecklist.TARGET_TOTAL_SHARDS) {
            Rs2NpcModel mob = Microbot.getRs2NpcCache().query().where(npc -> MEDIUM_MOB_IDS.contains(npc.getId())).nearest();
            if (mob != null && !mob.isDead()) {
                if (!Rs2Player.isInteracting()) {
                    Microbot.log(String.format("[Prep] Farming shards (%d/%d): attacking %s",
                            checklist.getTotalShardsEarned(), GauntletChecklist.TARGET_TOTAL_SHARDS, mob.getName()));
                    mob.click("Attack");
                }
                return;
            }
        }

        // Step 2: Teleport to base
        TileObject bowl = findTileObject(SINGING_BOWL_IDS);
        if (bowl == null) {
            teleportToBase();
            return;
        }

        // Step 3: Crush 30 crystal dust (3x pestle on shards)
        if (Rs2Inventory.count("Crystal dust") < 30 && Rs2Inventory.hasItem("Pestle and mortar")) {
            Microbot.log("[Prep] Crushing crystal shards into dust (3x 10)");
            Rs2Inventory.combine("Pestle and mortar", "Crystal shards");
            return;
        }

        // Step 4: Craft T3 Weapons + T2 Armour at Singing Bowl
        if (!hasPerfectedWeapons() || !hasAttunedArmourEquipped()) {
            upgradeGearAtBowl(bowl);
            return;
        }

        // Step 5: Craft 3 Vials
        if (Rs2Inventory.count("Vial") < 3 && Rs2Inventory.count("Water-filled vial") < 3 && Rs2Inventory.count("Egniol potion (4)") < 3) {
            Microbot.log("[Prep] Crafting 3 vials at singing bowl");
            Rs2GameObject.interact(bowl, "Sing-dialogue");
            return;
        }

        // Step 6: Drop useless tools
        dropUnneededTools();

        // Step 7: Fill vials with water
        if (Rs2Inventory.hasItem("Vial")) {
            TileObject pump = findTileObject(WATER_PUMP_IDS);
            if (pump != null) {
                Microbot.log("[Prep] Filling vials at water pump");
                Rs2Inventory.useItemOnObject(23879, pump.getId());
                return;
            }
        }

        // Step 8: Make unfinished potions (use leaf on water vial)
        if (Rs2Inventory.hasItem("Grym leaf") && Rs2Inventory.hasItem("Water-filled vial")) {
            Microbot.log("[Prep] Adding Grym leaf to water vial");
            Rs2Inventory.combine("Grym leaf", "Water-filled vial");
            return;
        }

        // Step 9: Make Egniol potions (use dust on unf potion)
        if (Rs2Inventory.hasItem("Crystal dust") && Rs2Inventory.hasItem("Grym potion (unf)")) {
            Microbot.log("[Prep] Adding crystal dust to unfinished potion -> Egniol potion (4)");
            Rs2Inventory.combine("Crystal dust", "Grym potion (unf)");
            return;
        }

        // Step 10: Pick up dropped fish from base floor
        if (checklist.getDroppedFishAtBase() > 0 && Rs2Inventory.getEmptySlots() > 0) {
            Microbot.log("[Prep] Picking up dropped paddlefish from floor");
            if (Rs2GroundItem.loot("Raw paddlefish", 15)) {
                checklist.setDroppedFishAtBase(Math.max(0, checklist.getDroppedFishAtBase() - 1));
                return;
            }
        }

        // Step 11: Cook all paddlefish at stove
        if (Rs2Inventory.hasItem("Raw paddlefish")) {
            TileObject stove = findTileObject(RANGE_STOVE_IDS);
            if (stove != null) {
                Microbot.log("[Prep] Cooking paddlefish on range");
                Rs2Inventory.useItemOnObject(23872, stove.getId());
                return;
            }
        }

        // Step 12: Enter boss arena
        currentPhase = PrepPhase.COMPLETE;
    }

    /**
     * Handover to Boss Fight:
     * - Toggles Protect from Range.
     * - Enters Hunllef barrier.
     * - Switches global state to FIGHTING.
     */
    private void handoverToBossFight() {
        Microbot.log("[Prep] Prep COMPLETE! Toggling Protect from Range and entering Hunllef arena.");
        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, true);

        TileObject barrier = findTileObject(HUNLLEF_BARRIER_IDS);
        if (barrier != null) {
            Rs2GameObject.interact(barrier, "Pass-through");
            sleep(1200);
            CustomGauntletScript.ghState = State.FIGHTING;
        }
    }

    /** Finds all tile objects across all layers (GameObject, WallObject, Deco, GroundObject). Client thread safe. */
    private List<TileObject> getAllTileObjects() {
        if (Microbot.getClientThread().isClientThread()) {
            return collectAllTileObjects();
        }
        return Microbot.getClientThread().runOnClientThreadOptional(this::collectAllTileObjects)
                .orElse(Collections.emptyList());
    }

    private List<TileObject> collectAllTileObjects() {
        List<TileObject> list = new ArrayList<>();
        Tile[][][] tiles = Microbot.getClient().getScene().getTiles();
        int plane = Microbot.getClient().getPlane();
        for (int x = 0; x < 104; x++) {
            for (int y = 0; y < 104; y++) {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null) {
                    for (GameObject go : gameObjects) {
                        if (go != null) list.add(go);
                    }
                }
                if (tile.getWallObject() != null) list.add(tile.getWallObject());
                if (tile.getDecorativeObject() != null) list.add(tile.getDecorativeObject());
                if (tile.getGroundObject() != null) list.add(tile.getGroundObject());
            }
        }
        return list;
    }

    /**
     * Instance-safe Chebyshev tile distance from the local player to a tile object, using LOCAL/scene coords.
     * The Gauntlet is instanced, where player and object WorldPoints live in different coordinate systems, so
     * WorldPoint.distanceTo() returns garbage (~13000) and rejects everything. Local coords are consistent for
     * all entities in the loaded scene. MUST run on the client thread.
     */
    private int tileDist(TileObject obj) {
        if (obj == null) return Integer.MAX_VALUE;
        LocalPoint o = obj.getLocalLocation();
        Player p = Microbot.getClient().getLocalPlayer();
        LocalPoint pl = p == null ? null : p.getLocalLocation();
        if (o == null || pl == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(pl.getSceneX() - o.getSceneX()), Math.abs(pl.getSceneY() - o.getSceneY()));
    }

    /** Instance-safe Chebyshev tile distance between two tile objects (local/scene coords). Client thread. */
    private int tileDist(TileObject a, TileObject b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        LocalPoint la = a.getLocalLocation(), lb = b.getLocalLocation();
        if (la == null || lb == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(la.getSceneX() - lb.getSceneX()), Math.abs(la.getSceneY() - lb.getSceneY()));
    }

    /** True if we can actually path to a tile adjacent to this object. Prevents targeting nodes/resources
     *  in rooms we haven't opened yet (otherwise we walk forever toward an unreachable tile). Client thread. */
    private boolean isReachableObj(TileObject obj) {
        return (obj instanceof GameObject) && Rs2GameObject.isReachable((GameObject) obj);
    }

    private TileObject findTileObject(Set<Integer> ids) {
        return findTileObject(ids, Integer.MAX_VALUE);
    }

    /** Nearest tile object with one of {@code ids} within {@code maxTiles} (local/scene distance), or null. */
    private TileObject findTileObject(Set<Integer> ids, int maxTiles) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            TileObject closest = null;
            int closestDist = Integer.MAX_VALUE;
            for (TileObject obj : collectAllTileObjects()) { // already on the client thread here
                if (obj == null || !ids.contains(obj.getId())) continue;
                int dist = tileDist(obj);
                if (dist <= maxTiles && dist < closestDist) {
                    closestDist = dist;
                    closest = obj;
                }
            }
            return closest;
        }).orElse(null);
    }

    private boolean gatherNearestObject(Set<Integer> objectIds, String action) {
        // Don't try to gather with no inventory space — the harvested item has nowhere to go, so we'd just
        // click the resource forever (e.g. "still fishing with a full inventory"). Let the phase advance.
        if (Rs2Inventory.isFull()) return false;
        // Search several rooms out (CG rooms connect via open doorways), and let the game's click-to-walk
        // path us there — instance-safe, unlike a manual WorldPoint walk.
        TileObject obj = findTileObject(objectIds, GATHER_SEARCH_TILES);
        if (obj == null) return false;
        if (!Rs2Player.isInteracting() && !Rs2Player.isMoving()) {
            Microbot.log("[Prep] Gathering (" + action + ") " + tileDist(obj) + " tiles away");
            Rs2GameObject.interact(obj, action);
        }
        return true;
    }

    private void teleportToBase() {
        if (Rs2Inventory.hasItem("Teleport crystal") || Rs2Inventory.hasItem("Corrupted teleport crystal")) {
            Rs2Inventory.interact("Teleport crystal", "Teleport");
            sleep(1800);
        }
    }

    private void openAndEnterRoom(GauntletRoom room) {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null || room == null) return;

        TileObject door = findNearestDoorTowardsRoom(room);
        if (door != null) {
            Microbot.log(String.format("[Prep] Passing through barrier at %s into room (%d,%d)",
                    door.getWorldLocation(), room.getGridX(), room.getGridY()));
            String[] actions = {"Quick-pass", "Pass", "Open"};
            for (String action : actions) {
                if (Rs2GameObject.interact(door, action)) {
                    sleep(1500);
                    room.setState(GauntletRoom.ExplorationState.EXPLORED);
                    return;
                }
            }
            Rs2GameObject.interact(door);
            sleep(1500);
            room.setState(GauntletRoom.ExplorationState.EXPLORED);
        } else {
            Microbot.log(String.format("[Prep] No door/barrier within range toward room (%d,%d) - nothing to pass",
                    room.getGridX(), room.getGridY()));
        }
    }

    private TileObject findNearestDoorTowardsRoom(GauntletRoom room) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            TileObject bestDoor = null;
            int bestDist = Integer.MAX_VALUE;

            for (TileObject obj : collectAllTileObjects()) {
                if (obj == null) continue;
                int dist = tileDist(obj); // instance-safe local distance
                if (dist > 25) continue;

                int id = obj.getId();
                boolean isDoor = ROOM_DOOR_IDS.contains(id);
                if (!isDoor) {
                    ObjectComposition comp = client.getObjectDefinition(id);
                    if (comp != null) {
                        String name = comp.getName();
                        String[] actions = comp.getActions();
                        if ("Barrier".equalsIgnoreCase(name) || (actions != null && (Arrays.asList(actions).contains("Pass") || Arrays.asList(actions).contains("Quick-pass")))) {
                            if (!HUNLLEF_BARRIER_IDS.contains(id)) {
                                isDoor = true;
                            }
                        }
                    }
                }

                if (isDoor && dist < bestDist) {
                    bestDist = dist;
                    bestDoor = obj;
                }
            }
            return bestDoor;
        }).orElse(null);
    }

    /**
     * Scouts the 3 non-Hunllef rooms surrounding the starting base (3, 3).
     * For each doorway, lights only 1 node on that doorway (max 3 nodes total).
     */
    private boolean scoutBaseRooms() {
        TileObject targetNode = findDoorwayNodeToLight();
        if (targetNode != null) {
            Microbot.log(String.format("[Scout] Lighting doorway node at %s (max 3 doorways)", targetNode.getWorldLocation()));
            Rs2GameObject.interact(targetNode, "Light");
            sleep(1200);

            // Scan all scouted rooms after lighting
            scanBaseAdjacentRooms();
            return true;
        }
        return false;
    }

    private TileObject findDoorwayNodeToLight() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            // Only consider nodes in the CURRENT room (<= NODE_SEARCH_TILES). A node within the current room
            // has no walls between us and it, so it is reachable — avoids chasing nodes across rooms we
            // haven't opened yet (Microbot's reachability check is unreliable inside the instance). Pick the
            // NEAREST such node; once it's lit it drops out and the next-nearest is chosen.
            TileObject nearest = null;
            int nearestDist = Integer.MAX_VALUE;
            for (TileObject obj : collectAllTileObjects()) {
                if (obj == null) continue;
                int dist = tileDist(obj);
                if (dist > NODE_SEARCH_TILES) continue;

                int id = obj.getId();
                boolean isNode = NODE_IDS.contains(id);
                if (!isNode) {
                    ObjectComposition comp = client.getObjectDefinition(id);
                    if (comp != null) {
                        String name = comp.getName();
                        String[] actions = comp.getActions();
                        if ("Node".equalsIgnoreCase(name) || (actions != null && Arrays.asList(actions).contains("Light"))) {
                            isNode = true;
                        }
                    }
                }
                if (isNode && dist < nearestDist) {
                    nearestDist = dist;
                    nearest = obj;
                }
            }
            if (nearest != null) {
                Microbot.log("[Scout] Nearest unlit node " + nearestDist + " tiles away");
            }
            return nearest;
        }).orElse(null);
    }

    private void scanBaseAdjacentRooms() {
        for (GauntletRoom room : grid.getBaseAdjacentRooms()) {
            scanRoomContents(room);
        }
    }

    /** Scans visible resource objects and NPCs inside a room and populates GauntletRoom. */
    private void scanRoomContents(GauntletRoom room) {
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (room == null || grid.getBaseCenterPoint() == null) return null;
            WorldPoint base = grid.getBaseCenterPoint();
            int roomCenterX = base.getX() + (room.getGridX() - GauntletGrid.BASE_X) * GauntletGrid.ROOM_TILE_SIZE;
            int roomCenterY = base.getY() + (room.getGridY() - GauntletGrid.BASE_Y) * GauntletGrid.ROOM_TILE_SIZE;
            WorldPoint center = new WorldPoint(roomCenterX, roomCenterY, base.getPlane());
            room.setCenterPoint(center);

            int minX = roomCenterX - 7;
            int maxX = roomCenterX + 7;
            int minY = roomCenterY - 7;
            int maxY = roomCenterY + 7;

            // Count resource game objects inside room boundaries
            int ore = 0, bark = 0, linum = 0, leaf = 0, fish = 0;
            List<TileObject> roomObjects = Rs2GameObject.getTileObjects(o -> {
                if (o == null || o.getWorldLocation() == null) return false;
                WorldPoint wp = o.getWorldLocation();
                return wp.getX() >= minX && wp.getX() <= maxX && wp.getY() >= minY && wp.getY() <= maxY;
            });

            for (TileObject obj : roomObjects) {
                int id = obj.getId();
                if (ORE_DEPOSIT_IDS.contains(id)) ore++;
                else if (PHREN_ROOTS_IDS.contains(id)) bark++;
                else if (LINUM_TIRINUM_IDS.contains(id)) linum++;
                else if (GRYM_ROOT_IDS.contains(id)) leaf++;
                else if (FISHING_SPOT_IDS.contains(id)) fish++;
            }
            room.setOreNodes(ore);
            room.setPhrenRoots(bark);
            room.setLinumPlants(linum);
            room.setGrymRoots(leaf);
            room.setFishingSpots(fish);

            // Check NPCs inside room boundaries
            for (Rs2NpcModel npc : Microbot.getRs2NpcCache().query().toListOnClientThread()) {
                if (npc == null || npc.getWorldLocation() == null) continue;
                WorldPoint wp = npc.getWorldLocation();
                if (wp.getX() >= minX && wp.getX() <= maxX && wp.getY() >= minY && wp.getY() <= maxY) {
                    int id = npc.getId();
                    if (DRAGON_IDS.contains(id)) room.setDemiboss(GauntletRoom.DemibossType.CORRUPTED_DRAGON);
                    else if (DARK_BEAST_IDS.contains(id)) room.setDemiboss(GauntletRoom.DemibossType.CORRUPTED_DARK_BEAST);
                    else if (BEAR_IDS.contains(id)) room.setDemiboss(GauntletRoom.DemibossType.CORRUPTED_BEAR);
                    else if (WEAK_MOB_IDS.contains(id)) room.setHasWeakMob(true);
                    else if (MEDIUM_MOB_IDS.contains(id)) room.setHasMediumMob(true);
                }
            }

            Microbot.log(String.format("[Scout] Room (%d,%d) scanned: Ore=%d, Bark=%d, Linum=%d, Leaf=%d, Fish=%d, Demiboss=%s, WeakMob=%b",
                    room.getGridX(), room.getGridY(), ore, bark, linum, leaf, fish, room.getDemiboss(), room.isHasWeakMob()));
            return null;
        });
    }

    private void scoutOuterRim() {
        // If an unlit node is on the doorway, light it to scout without entering
        TileObject node = findTileObject(NODE_IDS);
        if (node != null) {
            Microbot.log("[Prep] Lighting outer-rim node to scout demiboss");
            Rs2GameObject.interact(node, "Light");
            sleep(1200);
            return;
        }

        List<GauntletRoom> edgeRooms = new ArrayList<>();
        for (int x = 0; x < GauntletGrid.GRID_SIZE; x++) {
            for (int y = 0; y < GauntletGrid.GRID_SIZE; y++) {
                if (GauntletRoom.isOuterDemibossLocation(x, y) && grid.getRoom(x, y).getState() == GauntletRoom.ExplorationState.UNEXPLORED) {
                    edgeRooms.add(grid.getRoom(x, y));
                }
            }
        }
        if (!edgeRooms.isEmpty()) {
            openAndEnterRoom(edgeRooms.get(0));
        }
    }

    private void dropUnneededTools() {
        Rs2Inventory.drop(HARPOON_IDS::contains);
        Rs2Inventory.drop(PESTLE_IDS::contains);
        Rs2Inventory.drop("Crystal sceptre");
        Rs2Inventory.drop("Corrupted sceptre");
    }

    private boolean hasT1ArmourEquipped() {
        return Rs2Equipment.isWearing("Crystal helm (basic)")
                && Rs2Equipment.isWearing("Crystal body (basic)")
                && Rs2Equipment.isWearing("Crystal legs (basic)");
    }

    private boolean hasAttunedArmourEquipped() {
        return Rs2Equipment.isWearing("Crystal helm (attuned)")
                && Rs2Equipment.isWearing("Crystal body (attuned)")
                && Rs2Equipment.isWearing("Crystal legs (attuned)");
    }

    private boolean hasT2StaffEquipped() {
        return Rs2Equipment.isWearing("Crystal staff (attuned)") || Rs2Equipment.isWearing("Corrupted staff (attuned)");
    }

    private boolean hasPerfectedWeapons() {
        boolean hasBow = Rs2Equipment.isWearing("Crystal bow (perfected)") || Rs2Inventory.hasItem("Crystal bow (perfected)");
        boolean hasStaff = Rs2Equipment.isWearing("Crystal staff (perfected)") || Rs2Inventory.hasItem("Crystal staff (perfected)");
        return hasBow && hasStaff;
    }

    private void craftStartingGear(TileObject bowl) {
        Microbot.log("[Prep] Crafting T1 Armour, T2 Staff & Teleport Crystal at Singing Bowl");
        Rs2GameObject.interact(bowl, "Sing-dialogue");
    }

    private void upgradeGearAtBowl(TileObject bowl) {
        Microbot.log("[Prep] Upgrading to T3 Perfected Weapons & T2 Attuned Armour at Singing Bowl");
        Rs2GameObject.interact(bowl, "Sing-dialogue");
    }
}

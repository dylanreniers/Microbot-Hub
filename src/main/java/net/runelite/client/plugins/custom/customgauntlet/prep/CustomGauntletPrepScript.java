package net.runelite.client.plugins.custom.customgauntlet.prep;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.custom.customgauntlet.CustomGauntletConfig;
import net.runelite.client.plugins.custom.customgauntlet.CustomGauntletPlugin;
import net.runelite.client.plugins.custom.customgauntlet.CustomGauntletScript;
import net.runelite.client.plugins.custom.customgauntlet.State;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.util.Text;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;

/**
 * Automated preparation for The Gauntlet, rushing the weapon upgrades:
 * - Phase 1: a weapon frame from a weak mob and 12-16 fish (other checklist items only when on the way), then run back.
 * - Phase 1 ends with a teleport back. Phase 1b: drop the fish at the base; make the T2 staff, a teleport crystal and
 *   whatever basic armour we can.
 * - Phase 2: out to the outer rim (straight away from the Hunllef room) for the dragon and the dark beast, gathering on
 *   the way; teleport back once the orb and bowstring are in.
 * - Phase 2b: gather what's still missing, scouting the nearest unopened rooms (no ring limit).
 * - Phase 3: dust, T2 armour, weapon upgrades, vials, wear everything, drop the rest, potions, cook all fish, enter.
 */
@Getter
@Setter
public class CustomGauntletPrepScript extends Script {

    public enum PrepPhase {
        PHASE_1_RESOURCES,
        PHASE_1_BASE_CRAFT,
        PHASE_2_WEAPONS_AND_ARMOR,
        PHASE_2_TOP_UP,
        PHASE_3_BOSS_PREP,
        COMPLETE
    }

    // Object IDs
    public static final Set<Integer> SINGING_BOWL_IDS = Set.of(35966, 36063);
    private static final int SINGING_BOWL_GROUP = 270; // the Singing Bowl "What would you like to make?" interface (verified live; NOT a chat dialogue or group 640)
    private static final int SINGING_BOWL_TITLE_CHILD = 5; // "What would you like to make?" text, present whenever the interface is open
    public static final Set<Integer> WATER_PUMP_IDS = Set.of(35981, 36078);
    public static final Set<Integer> RANGE_STOVE_IDS = Set.of(35980, 36077);
    public static final Set<Integer> TOOL_STORAGE_IDS = Set.of(35977, 36074);
    // 37337 is the Corrupted arena barrier (the fight script finds 8 of them every fight); without it the Hunllef room
    // was never located from the base, so phase 2 had no direction to the rim and the open-room checks stayed off.
    public static final Set<Integer> HUNLLEF_BARRIER_IDS = Set.of(35982, 36079, 37337);
    public static final Set<Integer> ROOM_DOOR_IDS = Set.of(37339);
    public static final Set<Integer> GAUNTLET_ENTRY_IDS = Set.of(37340); // "The Gauntlet" lobby entry (enter to start)
    // Set by findDoorwayNodeToLight on the client thread, logged by lightNode on the script thread.
    private volatile String skippedClosedNodes = "", chosenNodeRooms = "";
    private String loggedSkippedNodes = "";
    private static final int PHASE1_FISH_MIN = 12;   // phase 1 is done with this many fish (and a weapon frame)...
    private static final int PHASE1_FISH_MAX = 16;   // ...or this many while a fishing spot is still right here
    private static final int ON_THE_WAY_TILES = 10;  // other checklist items are only gathered this close (no detours)
    private static final int BASE_NEAR_TILES = 6;
    private static final int BASE_FISH_TILES = 24;   // loot reaches 15 tiles: never loot raw fish this close to the bowl    // "at the base" = this close to the Singing Bowl
    private static final int TARGET_DUST = 30;
    private static final int TARGET_VIALS = 3;
    private static final int MAX_BOWL_PRESSES = 4;   // per item: not made after this many presses = give up on it
    private static final long WAIT_LOG_MS = 10000;
    private static final String VIAL_NAME = "Vial", WATER_VIAL_NAME = "Vial of water", UNF_POTION_NAME = "Grym potion (unf)";
    private static final String[] DUST_NAMES = {"Crystal dust", "Corrupted dust"};
    private static final String[] LOOT_NAMES = {"Weapon frame", "Crystal shards", "Corrupted shards", "Teleport crystal",
            "Corrupted orb", "Crystal orb", "Corrupted bowstring", "Crystalline bowstring"};
    // Phase 3: dropped before brewing (tools, spare materials), and the brewing leftovers after it. The inventory has
    // to fit 24 fish, 3 potions and the spare weapon.
    private static final String[] TOOL_AND_MATERIAL_DROPS = {"Corrupted harpoon", "Crystal harpoon", "Corrupted pickaxe",
            "Crystal pickaxe", "Corrupted axe", "Crystal axe", "Crystal sceptre", "Corrupted sceptre",
            "Corrupted teleport crystal", "Teleport crystal", "Corrupted ore", "Crystal ore", "Phren bark", "Linum tirinum",
            "Weapon frame"};
    private static final String[] LEFTOVER_DROPS = {"Pestle and mortar", "Crystal dust", "Corrupted dust", "Grym leaf",
            "Corrupted shards", "Crystal shards"};
    private static final int NODE_SEARCH_TILES = 12; // prefer nodes within the current room (guaranteed reachable, no walls between)
    private static final int FRONTIER_SEARCH_TILES = 48; // fallback when cornered: reach for a far unlit node several rooms out
    private static final int GATHER_SEARCH_TILES = 48; // reach resources/mobs several rooms out; game click-to-walk paths there
    private static final int DEMIBOSS_PRAYER_RANGE = 10;
    private static final int DEMIBOSS_CLOSE_TILES = 2; // with a 2nd demiboss near, fight the target from this close
    private volatile int demibossTargetIndex = -1;    // the demiboss we're going for: its prayer, whatever is nearer
    private static final int CLICK_RANGE = 20;         // farther NPCs/objects are walked towards first, never clicked from afar
    private static final int APPROACH_STEP_TILES = 12; // one walk step towards a far target
    private static final int RIM_RING_BONUS_TILES = 6; // on the rim, a rim room's node may be this much further than an inner one
    private static final int MEDIUM_MOB_PRAYER_RANGE = 3; // Protect from Melee for a scorpion/wolf/unicorn only this close // keep the right protection prayer up within this many tiles of a demiboss
    // The maze is 7x7 rooms of 16 tiles around the HUNLLEF room (3,3); the base is one room beside it. Phase 1 scouts
    // in rings around the Hunllef room: ring N ends N.5 rooms = 16N+8 tiles from its centre, and the outer rim (ring 3,
    // demiboss rooms) is never entered. The 2-tile margin keeps the doorway nodes on a ring's outer edge (which open
    // the next ring) out even if the anchor is slightly off.
    private static final int SCOUT_EDGE_MARGIN = 2;
    private static final int PHASE1_MAX_RING = 2;
    private static final long HUNLLEF_LOCATE_RETRY_MS = 3000;
    private static final int ROOM_SCAN_RANGE = 40; // room centres within this many tiles of us are fully in the 104-tile scene
    private static final long PRAYER_TOGGLE_PENDING_MS = 1200; // ~2 ticks for a prayer click to register
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

    // Tools — matched by name (exact), so they work for both Corrupted and Crystalline variants and don't
    // depend on item ids (which were wrong/fragile here, e.g. axe id 238). Full names avoid "axe" matching
    // "pickaxe".
    public static final String[] PICKAXE_NAMES = {"Corrupted pickaxe", "Crystal pickaxe"};
    public static final String[] AXE_NAMES = {"Corrupted axe", "Crystal axe"};
    public static final String[] HARPOON_NAMES = {"Corrupted harpoon", "Crystal harpoon"};
    public static final String PESTLE_NAME = "Pestle and mortar";

    private final CustomGauntletPlugin plugin;
    private final CustomGauntletConfig config;

    private GauntletGrid grid = new GauntletGrid();
    private GauntletChecklist checklist = new GauntletChecklist();
    private PrepPhase currentPhase = PrepPhase.PHASE_1_RESOURCES;

    private boolean initialized = false;
    private boolean anchorMissingLogged = false;
    private boolean hunllefMissingLogged = false;

    // Remaining-count supplier for the resource we last clicked. Continuous gather actions don't stop on
    // their own, so once this hits 0 we break the action (step off) instead of overshooting the target.
    private IntSupplier activeGatherRemaining = null;

    // Player animations from a fight (block, unarmed and weapon attacks): not a gather/light still running.
    private static final Set<Integer> COMBAT_ANIMS = Set.of(420, 424, 422, 423, 426, 428, 440, 1167);
    private int chosenTargetIndex = -1;   // index of the NPC we clicked Attack on; any other fight was started by the mob
    private int loggedAttackerIndex = -1; // attacker last logged (one line per attacker)
    private int scoutRing = 1;            // phase 1: rings around the Hunllef room we gather/scout in (widens to 2)
    private long lastHunllefLocateAt = 0;

    private final Map<Rs2PrayerEnum, Long> lastPrayerToggle = new EnumMap<>(Rs2PrayerEnum.class);
    private final Set<String> bowlSkipped = new HashSet<>();        // bowl steps we couldn't make this phase
    private final Map<String, Integer> bowlPresses = new HashMap<>();
    private String loggedMissing = "";
    private boolean autoRetaliateWasOn = false; // we switched it off: switch it back on at the handover
    private long lastRetaliateToggleAt = 0;
    private static final long RETALIATE_TOGGLE_RETRY_MS = 5000;
    private long lastWaitLogAt = 0;

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
                    resetForNewRun();
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

                // Anchor the base room once its stations are in view. Doesn't gate the loop: if the plugin was
                // started away from base we keep going, just without the rim filter until the base is seen.
                if (!initialized) {
                    initialized = initBaseAnchor();
                }
                if (initialized && grid.getHunllefCenterPoint() == null
                        && System.currentTimeMillis() - lastHunllefLocateAt > HUNLLEF_LOCATE_RETRY_MS) {
                    lastHunllefLocateAt = System.currentTimeMillis();
                    locateHunllefRoom();
                }

                // Survival: keep the correct protection prayer up near any demiboss, in every phase. They
                // hit from range, so this must run regardless of what the current phase is doing.
                updateDemibossProtectionPrayer();
                syncAutoRetaliate();
                handleUnchosenOpponent();

                switch (currentPhase) {
                    case PHASE_1_RESOURCES:
                        executePhase1Resources();
                        break;
                    case PHASE_1_BASE_CRAFT:
                        executePhase1BaseCraft();
                        break;
                    case PHASE_2_WEAPONS_AND_ARMOR:
                        executePhase2WeaponsAndArmor();
                        break;
                    case PHASE_2_TOP_UP:
                        executePhase2TopUp();
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

    /**
     * Back in the lobby = the previous run is over. Forget everything tied to it: the phase (it ended at
     * COMPLETE), the checklist's spent shards / dropped-fish counts, and the room grid and base anchor (a new
     * run is a new instance). Runs every loop while in the lobby, which is harmless.
     */
    private void resetForNewRun() {
        enterPhase(PrepPhase.PHASE_1_RESOURCES);
        checklist = new GauntletChecklist();
        grid = new GauntletGrid();
        initialized = false;
        anchorMissingLogged = false;
        hunllefMissingLogged = false;
        activeGatherRemaining = null;
        lastPrayerToggle.clear();
        demibossTargetIndex = -1;
        scoutRing = 1;
        lastHunllefLocateAt = 0;
    }

    @Override
    public void shutdown() {
        Microbot.isCantReachTargetDetectionEnabled = true; // restore the global default we disabled for the instance
        super.shutdown();
    }

    /**
     * Phase 1: rush the T2 staff. Kill a weak mob for a weapon frame and fish 12 paddlefish (16 while a fishing spot
     * is still right here); gather other checklist items only when they're on the way. Then teleport back to the bowl.
     */
    private void executePhase1Resources() {
        if (dropFinishedTools()) return;
        if (stopAtGatherTarget()) return;

        int fish = checklist.getRawFishCount();
        boolean frameDone = !staffFrameNeeded();
        if (frameDone && fish >= PHASE1_FISH_MIN) {
            boolean moreFishHere = fish < PHASE1_FISH_MAX && !Rs2Inventory.isFull()
                    && findGatherTarget(FISHING_SPOT_IDS, NODE_SEARCH_TILES) != null;
            if (!moreFishHere) {
                activeGatherRemaining = null;
                if (teleportToBase()) {
                    Microbot.log(String.format("[Prep] Phase 1 done: weapon frame + %d fish - teleported back to the base", fish));
                } else {
                    Microbot.log(String.format("[Prep] Phase 1 done: weapon frame + %d fish - no teleport crystal, running back to the Singing Bowl", fish));
                }
                currentPhase = PrepPhase.PHASE_1_BASE_CRAFT;
                return;
            }
        }

        if (lootUseful()) return;
        if (!frameDone && attackWeakMob(GATHER_SEARCH_TILES)) return;
        if (gatherOnTheWay()) return;
        if (fish < PHASE1_FISH_MAX && gatherNearestObject(FISHING_SPOT_IDS, "Fish",
                () -> Math.max(0, PHASE1_FISH_MAX - checklist.getRawFishCount()), null, GATHER_SEARCH_TILES)) return;
        if (scoutInnerRooms()) return;
        logWaiting("[Prep] Phase 1: nothing reachable to gather/light right now - waiting for a path to open");
    }

    /**
     * Phase 1b: back at the Singing Bowl (teleported, or on foot without a crystal). Drop the fish, make the T2 staff,
     * a teleport crystal for phase 2's way back, plus whatever basic armour the resources allow, and wear it all.
     */
    private void executePhase1BaseCraft() {
        TileObject bowl = findTileObject(SINGING_BOWL_IDS);
        if (!atBase(bowl)) {
            walkToBase(bowl);
            return;
        }
        if (dropFishAtBase()) return;
        if (!runBowlSteps(bowl, Arrays.asList(
                new BowlStep("T2 staff", "staff", '7', () -> hasItemOrWorn("staff (attuned)") || hasItemOrWorn("staff (perfected)"),
                        this::staffNextCost),
                // For the teleport back once the orb and bowstring are in (phase 1's crystal got us here).
                new BowlStep("teleport crystal", "teleport crystal", ' ', () -> hasItemOrWorn("teleport crystal"), () -> TELEPORT_CRYSTAL_COST),
                new BowlStep("helm (basic)", "helm", '3', () -> hasItemOrWorn("helm ("), () -> armourNextCost("helm")),
                new BowlStep("body (basic)", "body", '4', () -> hasItemOrWorn("body ("), () -> armourNextCost("body")),
                new BowlStep("legs (basic)", "legs", '5', () -> hasItemOrWorn("legs ("), () -> armourNextCost("legs"))))) return;
        if (equipGauntletGear()) return;
        if (!hasItemOrWorn("staff (attuned)")) {
            Microbot.log("[Prep] WARNING: no T2 staff after the bowl (not enough shards?) - hunting the demibosses anyway");
        }
        Microbot.log("[Prep] Base craft done - heading out to the outer rim for the dragon and the dark beast");
        enterPhase(PrepPhase.PHASE_2_WEAPONS_AND_ARMOR);
    }

    /**
     * Phase 2: straight out to the outer rim (away from the Hunllef room: south if the base is south of it) and along
     * it for the dragon's orb and the dark beast's bowstring, gathering checklist items on the way. Teleport back
     * once both are in.
     */
    private void executePhase2WeaponsAndArmor() {
        if (checklist.hasBothWeaponComponents()) {
            activeGatherRemaining = null;
            demibossTargetIndex = -1;
            Microbot.log("[Prep] Orb and bowstring in - teleporting back to the base");
            if (!teleportToBase()) Microbot.log("[Prep] No teleport crystal - the next phase walks back instead");
            enterPhase(PrepPhase.PHASE_2_TOP_UP);
            return;
        }
        if (dropFinishedTools()) return;
        if (stopAtGatherTarget()) return;
        if (lootUseful()) return;
        if (attackDemiboss()) return;
        demibossTargetIndex = -1; // nothing to hunt (any more): back to praying against whatever is nearest
        if (gatherOnTheWay()) return;
        if (checklist.totalFishRemaining() > 0 && gatherNearestObject(FISHING_SPOT_IDS, "Fish",
                checklist::totalFishRemaining, null, ON_THE_WAY_TILES)) return;
        if (lightNode(FRONTIER_SEARCH_TILES, "node towards the outer rim", true)) return;
        logWaiting("[Prep] Phase 2: no node left to open towards the rim - waiting");
    }

    /**
     * Phase 2b: gather what the final prep still needs, scouting the nearest unopened rooms as fast as possible - no
     * ring limit any more (the rim's demibosses are done by now).
     */
    private void executePhase2TopUp() {
        if (dropFinishedTools()) return;
        if (stopAtGatherTarget()) return;
        List<String> missing = missingForFinalPrep();
        if (missing.isEmpty()) {
            activeGatherRemaining = null;
            Microbot.log("[Prep] Everything for the final prep is in - back to the Singing Bowl");
            enterPhase(PrepPhase.PHASE_3_BOSS_PREP);
            return;
        }
        String summary = String.join(", ", missing);
        if (!summary.equals(loggedMissing)) {
            loggedMissing = summary;
            Microbot.log("[Prep] Still needed near the base: " + summary);
        }
        if (lootUseful()) return;
        if (gatherNearestNeeded(GATHER_SEARCH_TILES, checklist::totalFishRemaining)) return;
        if (shardsShort() && attackNearest(MEDIUM_MOB_IDS, currentOpenRooms(), "shard mob", GATHER_SEARCH_TILES)) return;
        if (scoutInnerRooms()) return;
        logWaiting("[Prep] Phase 2b: nothing reachable that we still need - waiting");
    }

    /** Light/pass our way into the next room around the base (phase 1 and 2b); widens the ring when this one is done. */
    private boolean scoutInnerRooms() {
        if (scoutBaseRooms()) return true;
        if (passNearestBarrier()) return true;
        // Phase 1 opens every room of the current ring before the next: a further node of this ring beats widening.
        if (scoutFrontierNode()) return true;
        if (innerPhase() && scoutRing < PHASE1_MAX_RING && grid.getHunllefCenterPoint() != null) {
            scoutRing++;
            Microbot.log(String.format("[Prep] Every room of ring %d around the Hunllef room is open - widening the scout ring to %d",
                    scoutRing - 1, scoutRing));
            return true;
        }
        return false;
    }

    private void enterPhase(PrepPhase phase) {
        currentPhase = phase;
        bowlSkipped.clear();
        bowlPresses.clear();
        loggedMissing = "";
    }

    private void logWaiting(String line) {
        long now = System.currentTimeMillis();
        if (now - lastWaitLogAt < WAIT_LOG_MS) return;
        lastWaitLogAt = now;
        Microbot.log(line);
    }

    /** Drop the pickaxe/axe once their resource is complete (frees a slot). */
    private boolean dropFinishedTools() {
        if (checklist.getOreCount() >= GauntletChecklist.TARGET_ORE && Rs2Inventory.hasItem(PICKAXE_NAMES, true)) {
            Microbot.log("[Prep] 7 Ore reached -> Dropping pickaxe");
            dropByName(PICKAXE_NAMES);
            return true;
        }
        if (checklist.getBarkCount() >= GauntletChecklist.TARGET_BARK && Rs2Inventory.hasItem(AXE_NAMES, true)) {
            Microbot.log("[Prep] 7 Bark reached -> Dropping axe");
            dropByName(AXE_NAMES);
            return true;
        }
        return false;
    }

    /**
     * The resource we last clicked keeps gathering on its own: once it's at its target, break the action (step off)
     * instead of gathering strays.
     */
    private boolean stopAtGatherTarget() {
        if (activeGatherRemaining == null || activeGatherRemaining.getAsInt() != 0) return false;
        activeGatherRemaining = null;
        if (busyWithOwnAction() && !Rs2Player.isMoving()) {
            Microbot.log("[Prep] Resource target reached - stepping off to stop gathering");
            stepOffCurrentTile();
            return true;
        }
        return false;
    }

    /**
     * Pick up drops worth having: weapon frames, shards, the demiboss orb/bowstring, and whatever else mobs drop that
     * the checklist still needs (ore, bark, linum, leaves, and raw fish away from the base).
     */
    private boolean lootUseful() {
        for (String name : LOOT_NAMES) {
            if (Rs2GroundItem.loot(name, 15)) return true;
        }
        if (checklist.oreRemaining() > 0 && lootAny(GauntletChecklist.ORE_NAMES)) return true;
        if (checklist.barkRemaining() > 0 && lootAny(GauntletChecklist.BARK_NAMES)) return true;
        if (checklist.linumRemaining() > 0 && lootAny(GauntletChecklist.LINUM_NAMES)) return true;
        if (checklist.leafRemaining() > 0 && lootAny(GauntletChecklist.LEAF_NAMES)) return true;
        // Never near the base: the fish we dropped there are already counted, and phase 2 (starting at the bowl) picked
        // them straight back up as "drops". Not in 2b either, which starts at the base after the teleport.
        int fishWanted = currentPhase == PrepPhase.PHASE_1_RESOURCES ? PHASE1_FISH_MAX - checklist.getRawFishCount()
                : currentPhase == PrepPhase.PHASE_2_WEAPONS_AND_ARMOR ? checklist.totalFishRemaining() : 0;
        return fishWanted > 0 && !nearBase() && lootAny(GauntletChecklist.RAW_FISH_NAMES);
    }

    /** The Singing Bowl is within BASE_FISH_TILES: fish on the floor here are the ones we dropped. */
    private boolean nearBase() {
        return findTileObject(SINGING_BOWL_IDS, BASE_FISH_TILES) != null;
    }

    private boolean lootAny(String[] names) {
        for (String name : names) {
            if (Rs2GroundItem.loot(name, 15)) {
                Microbot.log("[Prep] Picked up a dropped " + name + " (still needed)");
                return true;
            }
        }
        return false;
    }

    /** Ore/bark/linum/leaves still needed, only when they're close (no detours). */
    private boolean gatherOnTheWay() {
        return gatherNearestNeeded(ON_THE_WAY_TILES, null);
    }

    /**
     * Gather whichever still-needed resource is NEAREST (ore, bark, linum, leaves, and fish when {@code fishRemaining}
     * is given). Going by type order instead ran past a grym root to mine ore further away first.
     */
    private boolean gatherNearestNeeded(int maxTiles, IntSupplier fishRemaining) {
        List<GatherOption> options = new ArrayList<>();
        if (checklist.oreRemaining() > 0) options.add(new GatherOption(ORE_DEPOSIT_IDS, "Mine", checklist::oreRemaining, GauntletChecklist.ORE_NAMES));
        if (checklist.barkRemaining() > 0) options.add(new GatherOption(PHREN_ROOTS_IDS, "Chop", checklist::barkRemaining, GauntletChecklist.BARK_NAMES));
        if (checklist.linumRemaining() > 0) options.add(new GatherOption(LINUM_TIRINUM_IDS, "Pick", checklist::linumRemaining, GauntletChecklist.LINUM_NAMES));
        if (checklist.leafRemaining() > 0) options.add(new GatherOption(GRYM_ROOT_IDS, "Pick", checklist::leafRemaining, null));
        if (fishRemaining != null && fishRemaining.getAsInt() > 0) options.add(new GatherOption(FISHING_SPOT_IDS, "Fish", fishRemaining, null));
        for (GatherOption o : options) {
            TileObject target = findGatherTarget(o.ids, maxTiles);
            o.dist = target == null ? Integer.MAX_VALUE : distTo(target);
        }
        options.sort((a, b) -> Integer.compare(a.dist, b.dist));
        // Nearest first; gatherNearestObject declines one that can't go in the inventory, then we try the next.
        for (GatherOption o : options) {
            if (o.dist == Integer.MAX_VALUE) break;
            if (gatherNearestObject(o.ids, o.action, o.remaining, o.stackNames, maxTiles)) return true;
        }
        return false;
    }

    private static final class GatherOption {
        final Set<Integer> ids;
        final String action;
        final IntSupplier remaining;
        final String[] stackNames;
        int dist;

        GatherOption(Set<Integer> ids, String action, IntSupplier remaining, String[] stackNames) {
            this.ids = ids;
            this.action = action;
            this.remaining = remaining;
            this.stackNames = stackNames;
        }
    }

    /**
     * True while we still need the staff's weapon frame (phase 1's weak mob). The bow's frame always drops from a
     * demiboss, so it's never hunted for.
     */
    private boolean staffFrameNeeded() {
        return checklist.getWeaponFrameCount() == 0 && !hasItemOrWorn("staff (");
    }

    private boolean shardsShort() {
        return checklist.getTotalShardsEarned() < GauntletChecklist.TARGET_TOTAL_SHARDS;
    }

    /** What the final prep is still missing, for phase 2b. */
    private List<String> missingForFinalPrep() {
        List<String> missing = new ArrayList<>();
        if (checklist.oreRemaining() > 0) missing.add(checklist.oreRemaining() + " ore");
        if (checklist.barkRemaining() > 0) missing.add(checklist.barkRemaining() + " bark");
        if (checklist.linumRemaining() > 0) missing.add(checklist.linumRemaining() + " linum");
        if (checklist.leafRemaining() > 0) missing.add(checklist.leafRemaining() + " grym leaves");
        if (checklist.totalFishRemaining() > 0) missing.add(checklist.totalFishRemaining() + " fish");
        if (shardsShort()) missing.add((GauntletChecklist.TARGET_TOTAL_SHARDS - checklist.getTotalShardsEarned()) + " shards");
        return missing;
    }

    private boolean attackWeakMob(int maxTiles) {
        return attackNearest(WEAK_MOB_IDS, currentOpenRooms(), "weak mob for a weapon frame", maxTiles);
    }

    /** Dragon (orb) and dark beast (bowstring), or a bear when 2 are known and one component is missing. */
    private boolean attackDemiboss() {
        boolean[][] open = currentOpenRooms();
        if (!checklist.hasOrb() && attackNearest(DRAGON_IDS, open, "Corrupted Dragon (Protect from Magic)", Integer.MAX_VALUE)) return true;
        if (!checklist.hasBowstring() && attackNearest(DARK_BEAST_IDS, open, "Corrupted Dark Beast (Protect from Range)", Integer.MAX_VALUE)) return true;
        return (checklist.hasOrb() ^ checklist.hasBowstring()) && grid.getKnownBearCount() >= 2
                && attackNearest(BEAR_IDS, open, "Bear (2-bear fallback)", Integer.MAX_VALUE);
    }

    /** Attack the nearest living NPC of these ids in a room we can walk into (and the phase's scout area). */
    private boolean attackNearest(Set<Integer> ids, boolean[][] open, String what, int maxTiles) {
        Rs2NpcModel npc = Microbot.getRs2NpcCache().query()
                .where(n -> ids.contains(n.getId()) && inScoutArea(n.getLocalLocation()) && inOpenRoom(open, n.getLocalLocation()))
                .nearest();
        if (npc == null || npc.isDead() || npcTileDist(npc) > maxTiles) return false;
        boolean demiboss = isDemiboss(npc.getId());
        if (demiboss) demibossTargetIndex = npc.getIndex(); // its prayer goes up while we walk to it
        if (!busyWithOwnAction()) {
            if (approachIfFar(npc.getLocalLocation(), what)) return true;
            // Another demiboss in range would hit us from the side (wrong prayer): fight this one from right next to it.
            if (demiboss && otherDemibossNear(npc.getIndex()) && npcTileDist(npc) > DEMIBOSS_CLOSE_TILES) {
                if (!Rs2Player.isMoving() || nearWalkDestination()) {
                    WorldPoint at = Microbot.getClientThread().runOnClientThreadOptional(
                            () -> WorldPoint.fromLocal(Microbot.getClient(), npc.getLocalLocation())).orElse(null);
                    Microbot.log(String.format("[Prep] Another demiboss is near - moving right up to the %s before attacking", what));
                    Rs2Walker.walkFastCanvas(at, true);
                }
                return true;
            }
            Microbot.log("[Prep] Engaging " + what);
            chosenTargetIndex = npc.getIndex();
            npc.click("Attack");
        }
        return true;
    }

    /**
     * Walk a bit closer to a far target (one {@link Rs2Walker#walkFastCanvas} click APPROACH_STEP_TILES along the line
     * to it) instead of clicking it from off-screen, which is a bot tell. Sends the next step just before the current
     * one ends, so the run doesn't stop in between.
     *
     * @return true while still approaching (the caller clicks the target itself once it's within CLICK_RANGE)
     */
    private boolean approachIfFar(LocalPoint target, String what) {
        if (target == null) return false;
        int dist = distToLocal(target);
        if (dist <= CLICK_RANGE) return false;
        if (Rs2Player.isMoving() && !nearWalkDestination()) return true;
        // Room by room through open rooms: a straight-line step pointed into walls in the maze and got nowhere
        // ("46 tiles away" for half a minute).
        Object[] via = Microbot.getClientThread().runOnClientThreadOptional(() -> roomPathWaypoint(target)).orElse(null);
        WorldPoint wp;
        if (via != null) {
            wp = (WorldPoint) via[0];
            Microbot.log(String.format("[Prep] %s is %d tiles away - walking closer via room %s", what, dist, via[1]));
        } else {
            LocalPoint step = stepTowards(target, APPROACH_STEP_TILES);
            wp = Microbot.getClientThread().runOnClientThreadOptional(
                    () -> WorldPoint.fromLocal(Microbot.getClient(), step)).orElse(null);
            Microbot.log(String.format("[Prep] %s is %d tiles away - walking closer first", what, dist));
        }
        Rs2Walker.walkFastCanvas(wp, true);
        return true;
    }

    /**
     * Next waypoint towards {@code target} along the open rooms (breadth-first over the room grid, never through the
     * Hunllef room): the centre of the furthest room on that path within APPROACH_STEP_TILES + 6 of us, as
     * {WorldPoint, "(x,y)"}. Null when we're in or next to the target's room, or no open path is known (then a
     * straight step is fine). Client thread.
     */
    private Object[] roomPathWaypoint(LocalPoint target) {
        WorldPoint hunllef = grid.getHunllefCenterPoint();
        Player me = Microbot.getClient().getLocalPlayer();
        if (hunllef == null || me == null || me.getLocalLocation() == null) return null;
        WorldPoint here = WorldPoint.fromLocal(Microbot.getClient(), me.getLocalLocation());
        int[] from = grid.getGridCoordinates(here);
        int[] to = grid.getGridCoordinates(WorldPoint.fromLocal(Microbot.getClient(), target));
        if (from == null || to == null || Math.abs(from[0] - to[0]) + Math.abs(from[1] - to[1]) <= 1) return null;
        boolean[][] open = openRooms(Microbot.getClient(), collectAllTileObjects());
        int n = GauntletGrid.GRID_SIZE;
        int[][] prev = new int[n * n][];
        boolean[] seen = new boolean[n * n];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        queue.add(from);
        seen[from[0] * n + from[1]] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] r = queue.poll();
            if (r[0] == to[0] && r[1] == to[1]) break;
            for (int[] d : dirs) {
                int x = r[0] + d[0], y = r[1] + d[1];
                if (x < 0 || y < 0 || x >= n || y >= n || seen[x * n + y]) continue;
                if (x == GauntletGrid.HUNLLEF_X && y == GauntletGrid.HUNLLEF_Y) continue;
                if (!open[x][y] && !(x == to[0] && y == to[1])) continue;
                seen[x * n + y] = true;
                prev[x * n + y] = r;
                queue.add(new int[]{x, y});
            }
        }
        if (!seen[to[0] * n + to[1]]) return null;
        List<int[]> path = new ArrayList<>(); // from the target back to the room after ours
        for (int[] r = to; r != null && !(r[0] == from[0] && r[1] == from[1]); r = prev[r[0] * n + r[1]]) path.add(r);
        Collections.reverse(path);
        int[] pick = path.get(0);
        for (int[] r : path) {
            WorldPoint c = grid.getRoomCenter(r[0], r[1]);
            if (Math.max(Math.abs(c.getX() - here.getX()), Math.abs(c.getY() - here.getY())) > APPROACH_STEP_TILES + 6) break;
            pick = r;
        }
        if (pick[0] == to[0] && pick[1] == to[1]) return null;
        return new Object[]{grid.getRoomCenter(pick[0], pick[1]), "(" + pick[0] + "," + pick[1] + ")"};
    }

    /** Our walk ends within 3 tiles: the next run step can go out now, so the run doesn't stop in between. */
    private boolean nearWalkDestination() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            LocalPoint dest = Microbot.getClient().getLocalDestinationLocation();
            Player me = Microbot.getClient().getLocalPlayer();
            LocalPoint from = me == null ? null : me.getLocalLocation();
            return dest == null || from == null
                    || Math.max(Math.abs(from.getSceneX() - dest.getSceneX()), Math.abs(from.getSceneY() - dest.getSceneY())) <= 3;
        }).orElse(true);
    }

    private int distToLocal(LocalPoint lp) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player me = Microbot.getClient().getLocalPlayer();
            LocalPoint from = me == null ? null : me.getLocalLocation();
            return from == null ? Integer.MAX_VALUE
                    : Math.max(Math.abs(from.getSceneX() - lp.getSceneX()), Math.abs(from.getSceneY() - lp.getSceneY()));
        }).orElse(Integer.MAX_VALUE);
    }

    /** The scene point {@code tiles} along the line from us to {@code target} (or the target if it's closer). */
    private LocalPoint stepTowards(LocalPoint target, int tiles) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player me = Microbot.getClient().getLocalPlayer();
            LocalPoint from = me == null ? null : me.getLocalLocation();
            if (from == null) return target;
            int dx = target.getSceneX() - from.getSceneX(), dy = target.getSceneY() - from.getSceneY();
            int dist = Math.max(Math.abs(dx), Math.abs(dy));
            if (dist <= tiles) return target;
            LocalPoint lp = LocalPoint.fromScene(from.getSceneX() + dx * tiles / dist, from.getSceneY() + dy * tiles / dist,
                    Microbot.getClient().getTopLevelWorldView());
            return lp == null ? target : lp;
        }).orElse(target);
    }

    /**
     * Pass through the nearest barrier (37339 etc., not the Hunllef barrier) into an adjacent room.
     * In phase 1 barriers outside the scout ring are skipped (inScoutArea). An earlier rim check mis-classified inner rooms
     * because it measured in template coords, which are stitched per room; inScoutArea uses instance coords.
     */
    private boolean passNearestBarrier() {
        TileObject door = findNearestPassableBarrier();
        if (door == null) return false;
        if (!Rs2Player.isMoving() && !busyWithOwnAction()) {
            Microbot.log("[Prep] Passing barrier " + door.getId() + " into a new room");
            if (!Rs2GameObject.interact(door, "Quick-pass")
                    && !Rs2GameObject.interact(door, "Pass")
                    && !Rs2GameObject.interact(door, "Open")) {
                Rs2GameObject.interact(door);
            }
        }
        return true;
    }

    /** Nearest passable barrier (not the Hunllef arena barrier). Client thread. */
    private TileObject findNearestPassableBarrier() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            TileObject best = null;
            int bestDist = Integer.MAX_VALUE;
            for (TileObject obj : collectAllTileObjects()) {
                if (obj == null) continue;
                int dist = tileDist(obj); // instance-safe local distance
                if (dist > 25) continue;
                if (!isPassableBarrier(obj, client)) continue;
                if (!inScoutArea(obj.getLocalLocation())) continue;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = obj;
                }
            }
            return best;
        }).orElse(null);
    }

    /** Whether a tile object is a passable room barrier (not the Hunllef arena barrier). Client thread. */
    private boolean isPassableBarrier(TileObject obj, Client client) {
        int id = obj.getId();
        if (HUNLLEF_BARRIER_IDS.contains(id)) return false;
        if (ROOM_DOOR_IDS.contains(id)) return true;
        ObjectComposition comp = client.getObjectDefinition(id);
        if (comp == null) return false;
        String[] actions = comp.getActions();
        return "Barrier".equalsIgnoreCase(comp.getName())
                || (actions != null && (Arrays.asList(actions).contains("Pass") || Arrays.asList(actions).contains("Quick-pass")));
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
     * Keeps the correct protection prayer up while a demiboss is within {@link #DEMIBOSS_PRAYER_RANGE}
     * tiles - Magic for the Dragon, Range for the Dark beast, Melee for the Bear. They attack from a
     * distance, so this runs every loop in all prep phases (previously we only prayed once we chose to
     * engage, which is why Phase-1 wandering near the rim got us killed). The nearest demiboss wins if
     * several are close. With no demiboss near, a scorpion/wolf/unicorn (all melee) targeting us gets
     * Protect from Melee; otherwise protection prayers are turned back off to save prayer.
     */
    private void updateDemibossProtectionPrayer() {
        if (currentPhase == PrepPhase.COMPLETE) return; // the handover keeps Protect from Missiles on for the Hunllef
        // The demiboss we're going for decides, not the nearest one: a bear next door kept Protect from Melee up while
        // we ran to the dark beast and fought it (ranged).
        Rs2PrayerEnum targetPrayer = demibossTargetPrayer();
        if (targetPrayer != null) {
            togglePrayerIfNeeded(targetPrayer, true);
            return;
        }
        Rs2PrayerEnum needed = null;
        int best = DEMIBOSS_PRAYER_RANGE + 1;

        Rs2NpcModel dragon = nearestLivingNpc(DRAGON_IDS);
        int d = npcTileDist(dragon);
        if (d <= DEMIBOSS_PRAYER_RANGE && d < best) { best = d; needed = Rs2PrayerEnum.PROTECT_MAGIC; }

        Rs2NpcModel darkBeast = nearestLivingNpc(DARK_BEAST_IDS);
        d = npcTileDist(darkBeast);
        if (d <= DEMIBOSS_PRAYER_RANGE && d < best) { best = d; needed = Rs2PrayerEnum.PROTECT_RANGE; }

        Rs2NpcModel bear = nearestLivingNpc(BEAR_IDS);
        d = npcTileDist(bear);
        if (d <= DEMIBOSS_PRAYER_RANGE && d < best) { best = d; needed = Rs2PrayerEnum.PROTECT_MELEE; }

        if (needed == null && isTargetedByMediumMob()) {
            needed = Rs2PrayerEnum.PROTECT_MELEE;
        }

        if (needed != null) {
            // Protection prayers are mutually exclusive in-game: switching one on drops the other, so a
            // single click switches styles (toggling the old one off first would be a wasted, racing click).
            togglePrayerIfNeeded(needed, true);
        } else {
            togglePrayerIfNeeded(Rs2PrayerEnum.PROTECT_MAGIC, false);
            togglePrayerIfNeeded(Rs2PrayerEnum.PROTECT_RANGE, false);
            togglePrayerIfNeeded(Rs2PrayerEnum.PROTECT_MELEE, false);
        }
    }

    /**
     * True while our own last click is still running: a gather/light animation, or the fight with the NPC we chose
     * to attack. A mob that attacked us (auto-retaliate makes it our target) and the block/attack animations of
     * that fight don't count - treating them as busy held every step until the mob was dead.
     */
    private boolean busyWithOwnAction() {
        Player me = Microbot.getClient().getLocalPlayer();
        if (me == null) return false;
        if (Rs2Player.isInteracting()) return unchosenOpponent() == null;
        if (COMBAT_ANIMS.contains(me.getAnimation())) return false;
        return Rs2Player.isAnimating();
    }

    /** An NPC we're fighting, read on the client thread (getInteracting() throws anywhere else). */
    private static final class Opponent {
        final int index, id;
        final String name;

        Opponent(NPC npc) {
            index = npc.getIndex();
            id = npc.getId();
            name = npc.getName();
        }
    }

    /** The NPC we're fighting that the script didn't click Attack on itself, or null. */
    private Opponent unchosenOpponent() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player me = Microbot.getClient().getLocalPlayer();
            if (me == null) return null;
            Actor target = me.getInteracting();
            if (!(target instanceof NPC)) return null;
            NPC npc = (NPC) target;
            return npc.getIndex() == chosenTargetIndex ? null : new Opponent(npc);
        }).orElse(null);
    }

    /**
     * A mob attacked us and we're fighting back. Keep fighting it when the kill is what we're after anyway (a weak
     * mob while we still need a weapon frame, any shard mob while farming shards, a scorpion/wolf/unicorn that would
     * chase us); otherwise ignore it - the next click walks us away from it.
     */
    private void handleUnchosenOpponent() {
        Opponent opp = unchosenOpponent();
        if (opp == null) return;
        int id = opp.id;
        // Scorpions/wolves/unicorns used to count as useful in every phase, so gathering waited for the kill.
        boolean useful = (WEAK_MOB_IDS.contains(id) && currentPhase == PrepPhase.PHASE_1_RESOURCES && staffFrameNeeded())
                || (currentPhase == PrepPhase.PHASE_2_TOP_UP && (WEAK_MOB_IDS.contains(id) || MEDIUM_MOB_IDS.contains(id)) && shardsShort());
        if (useful) chosenTargetIndex = opp.index;
        if (loggedAttackerIndex == opp.index) return;
        loggedAttackerIndex = opp.index;
        Microbot.log(String.format("[Prep] Attacked by %s - %s", opp.name,
                useful ? "fighting it (the kill is useful)" : "ignoring it and continuing"));
    }

    /**
     * Auto-retaliate interrupts fishing/chopping whenever a mob hits us, so the prep waited for every kill. Keep it on
     * only while phase 1 still needs the staff's frame (the weak mobs come to us); every other fight we start ourselves.
     * Turned back on for the Hunllef if we switched it off.
     */
    private void syncAutoRetaliate() {
        boolean want = currentPhase == PrepPhase.COMPLETE ? autoRetaliateWasOn : staffFrameNeeded();
        boolean on = Microbot.getVarbitPlayerValue(VarPlayerID.OPTION_NODEF) == 0;
        if (on == want) return;
        long now = System.currentTimeMillis();
        if (now - lastRetaliateToggleAt < RETALIATE_TOGGLE_RETRY_MS) return;
        lastRetaliateToggleAt = now;
        if (on) autoRetaliateWasOn = true;
        Microbot.log(want ? "[Prep] Auto-retaliate back on for the Hunllef"
                : "[Prep] Weapon frame in - auto-retaliate off, so mobs no longer interrupt gathering");
        Rs2Combat.setAutoRetaliate(want);
    }

    private static boolean isDemiboss(int id) {
        return DRAGON_IDS.contains(id) || DARK_BEAST_IDS.contains(id) || BEAR_IDS.contains(id);
    }

    private static Rs2PrayerEnum demibossPrayer(int id) {
        return DRAGON_IDS.contains(id) ? Rs2PrayerEnum.PROTECT_MAGIC
                : DARK_BEAST_IDS.contains(id) ? Rs2PrayerEnum.PROTECT_RANGE
                : BEAR_IDS.contains(id) ? Rs2PrayerEnum.PROTECT_MELEE : null;
    }

    /** The prayer for the demiboss we're going for while it's alive and near, else null (and the target is dropped). */
    private Rs2PrayerEnum demibossTargetPrayer() {
        int index = demibossTargetIndex;
        if (index < 0) return null;
        Rs2NpcModel npc = Microbot.getRs2NpcCache().query().where(n -> n.getIndex() == index && isDemiboss(n.getId())).nearest();
        if (npc == null || npc.isDead() || npcTileDist(npc) > CLICK_RANGE * 2) {
            demibossTargetIndex = -1;
            return null;
        }
        return demibossPrayer(npc.getId());
    }

    /** A living demiboss other than {@code targetIndex} is within prayer range of us. */
    private boolean otherDemibossNear(int targetIndex) {
        return Microbot.getRs2NpcCache().query()
                .where(n -> isDemiboss(n.getId()) && n.getIndex() != targetIndex && !n.isDead()
                        && npcTileDist(n) <= DEMIBOSS_PRAYER_RANGE)
                .nearest() != null;
    }

    /** A living scorpion, wolf or unicorn is targeting us from within MEDIUM_MOB_PRAYER_RANGE (they only hit in melee). */
    private boolean isTargetedByMediumMob() {
        return Microbot.getRs2NpcCache().query()
                .where(n -> MEDIUM_MOB_IDS.contains(n.getId()) && !n.isDead() && n.isInteractingWithPlayer()
                        && npcTileDist(n) <= MEDIUM_MOB_PRAYER_RANGE)
                .nearest() != null;
    }

    /** Nearest non-dead NPC of the given ids, or null. */
    private Rs2NpcModel nearestLivingNpc(Set<Integer> ids) {
        Rs2NpcModel npc = Microbot.getRs2NpcCache().query().where(n -> ids.contains(n.getId())).nearest();
        return (npc != null && !npc.isDead()) ? npc : null;
    }

    /**
     * Toggle a prayer only when it isn't already in the desired state (avoids click spam / flicker). Uses the
     * non-blocking 1-arg toggle - the 2-arg one sleeps up to 10s when a click doesn't land, freezing prep - and
     * holds off re-clicking for ~2 ticks so a click that hasn't registered yet isn't flipped straight back.
     */
    private void togglePrayerIfNeeded(Rs2PrayerEnum prayer, boolean on) {
        if (Rs2Prayer.isPrayerActive(prayer) == on) return;
        long now = System.currentTimeMillis();
        Long last = lastPrayerToggle.get(prayer);
        if (last != null && now - last < PRAYER_TOGGLE_PENDING_MS) return;
        lastPrayerToggle.put(prayer, now);
        Rs2Prayer.toggle(prayer);
    }

    /**
     * Anchors the base room centre from the base stations (Singing Bowl, pump, range, tool storage) - they only
     * exist in the starting room, so this works wherever in the base we start. Stored in instance-space world
     * coords ({@link WorldPoint#fromLocal}), which are contiguous across the maze and stable across scene
     * reloads; template coords ({@link Rs2Player#getWorldLocation()}) are stitched per room and mis-place rooms.
     *
     * @return true once anchored
     */
    private boolean initBaseAnchor() {
        WorldPoint anchor = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            long sumX = 0, sumY = 0;
            int n = 0;
            boolean hasBowl = false;
            for (TileObject obj : collectAllTileObjects()) {
                if (obj == null || tileDist(obj) > 20) continue;
                int id = obj.getId();
                if (!SINGING_BOWL_IDS.contains(id) && !WATER_PUMP_IDS.contains(id)
                        && !RANGE_STOVE_IDS.contains(id) && !TOOL_STORAGE_IDS.contains(id)) continue;
                hasBowl |= SINGING_BOWL_IDS.contains(id);
                WorldPoint wp = WorldPoint.fromLocal(client, obj.getLocalLocation());
                sumX += wp.getX();
                sumY += wp.getY();
                n++;
            }
            if (!hasBowl) return null;
            return new WorldPoint((int) Math.round((double) sumX / n), (int) Math.round((double) sumY / n),
                    client.getTopLevelWorldView().getPlane());
        }).orElse(null);

        if (anchor == null) {
            if (!anchorMissingLogged) {
                anchorMissingLogged = true;
                Microbot.log("[Prep] Base stations not in view - rim avoidance disabled until the base is seen");
            }
            return false;
        }
        grid.setBaseCenterPoint(anchor);
        Microbot.log("[Prep] Anchored base centre at " + anchor + " (instance coords)");
        locateHunllefRoom();
        return true;
    }

    /**
     * Finds which side of the base the Hunllef room is on from its arena barriers (loaded in the scene from the base)
     * and places it at the grid centre. Without it the scout area stays 1 room around the base.
     *
     * @return true once placed
     */
    private boolean locateHunllefRoom() {
        WorldPoint base = grid.getBaseCenterPoint();
        if (base == null) return false;
        int[] dir = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            long sumX = 0, sumY = 0;
            int n = 0;
            for (TileObject obj : collectAllTileObjects()) {
                if (obj == null || !HUNLLEF_BARRIER_IDS.contains(obj.getId())) continue;
                WorldPoint wp = WorldPoint.fromLocal(client, obj.getLocalLocation());
                sumX += wp.getX();
                sumY += wp.getY();
                n++;
            }
            if (n == 0) return null;
            double dx = (double) sumX / n - base.getX(), dy = (double) sumY / n - base.getY();
            if (Math.max(Math.abs(dx), Math.abs(dy)) < 4) return null; // barriers on top of the base: can't tell
            return Math.abs(dx) >= Math.abs(dy) ? new int[]{(int) Math.signum(dx), 0} : new int[]{0, (int) Math.signum(dy)};
        }).orElse(null);
        if (dir == null) {
            if (!hunllefMissingLogged) {
                hunllefMissingLogged = true;
                Microbot.log("[Prep] Hunllef arena barriers not found yet - scouting only 1 room around the base until they are");
            }
            return false;
        }
        grid.setHunllefDirection(dir[0], dir[1]);
        String side = dir[1] > 0 ? "NORTH" : dir[1] < 0 ? "SOUTH" : dir[0] > 0 ? "EAST" : "WEST";
        Microbot.log(String.format("[Prep] Hunllef room is %s of the base (base = room (%d,%d)) - phase 1 scouts ring %d around the Hunllef room, at most ring %d",
                side, grid.getBaseGridX(), grid.getBaseGridY(), scoutRing, PHASE1_MAX_RING));
        return true;
    }

    /** Phase 1 stays in the rings around the Hunllef room (never the outer rim); later phases go anywhere. */
    private boolean innerPhase() {
        return currentPhase == PrepPhase.PHASE_1_RESOURCES;
    }

    /**
     * Phase-1 target filter: true only inside the current scout ring around the Hunllef room, so gathering never
     * walks us into (or opens) an outer-rim demiboss room. Before the Hunllef room is placed: 1 room around the base.
     * Later phases hunt the demibosses on the rim, so they're unrestricted; so is an unanchored base.
     */
    private boolean inScoutArea(LocalPoint lp) {
        if (!innerPhase() || lp == null) return true;
        WorldPoint wp = WorldPoint.fromLocal(Microbot.getClient(), lp);
        WorldPoint centre = grid.getHunllefCenterPoint();
        int ring = scoutRing;
        if (centre == null) {
            centre = grid.getBaseCenterPoint();
            ring = 1;
            if (centre == null) return true;
        }
        int offset = Math.max(Math.abs(wp.getX() - centre.getX()), Math.abs(wp.getY() - centre.getY()));
        return offset <= ring * GauntletGrid.ROOM_TILE_SIZE + GauntletGrid.ROOM_TILE_SIZE / 2 - SCOUT_EDGE_MARGIN;
    }

    /**
     * Phase 3, at the Singing Bowl: crush shards into dust, make the T2 armour, the weapon upgrades and 3 vials, wear
     * everything, drop what's no longer needed, brew the 3 Egniol potions, pick up and cook all the fish.
     */
    private void executePhase3BossPrep() {
        TileObject bowl = findTileObject(SINGING_BOWL_IDS);
        if (!atBase(bowl)) {
            walkToBase(bowl);
            return;
        }
        if (crushDust()) return;
        if (!runBowlSteps(bowl, finalBowlSteps())) return;
        if (equipGauntletGear()) return;
        if (dropItems(TOOL_AND_MATERIAL_DROPS)) return;
        if (brewPotionStep()) return;
        if (dropItems(LEFTOVER_DROPS)) return;
        if (pickUpFish()) return;
        if (cookFish()) return;
        Microbot.log("[Prep] Final prep done - entering the Hunllef arena");
        enterPhase(PrepPhase.COMPLETE);
    }

    private List<BowlStep> finalBowlSteps() {
        return Arrays.asList(
                new BowlStep("helm (attuned)", "helm", '3', () -> hasItemOrWorn("helm (attuned)"), () -> armourNextCost("helm")),
                new BowlStep("body (attuned)", "body", '4', () -> hasItemOrWorn("body (attuned)"), () -> armourNextCost("body")),
                new BowlStep("legs (attuned)", "legs", '5', () -> hasItemOrWorn("legs (attuned)"), () -> armourNextCost("legs")),
                new BowlStep("perfected staff", "staff", '7', () -> hasItemOrWorn("staff (perfected)"), this::staffNextCost),
                new BowlStep("perfected bow", "bow", '8', () -> hasItemOrWorn("bow (perfected)"), this::bowNextCost),
                new BowlStep("vials", "vial", '2', () -> vialFamilyCount() >= TARGET_VIALS, () -> VIAL_COST));
    }

    /**
     * Handover to the boss fight: Protect from Missiles on (the Hunllef opens with ranged), pass the arena barrier,
     * and let the fight script take over.
     */
    private void handoverToBossFight() {
        togglePrayerIfNeeded(Rs2PrayerEnum.PROTECT_RANGE, true);
        if (Rs2Player.isMoving()) return;
        TileObject barrier = findTileObject(HUNLLEF_BARRIER_IDS);
        if (barrier != null) {
            Microbot.log("[Prep] Prep COMPLETE - Protect from Missiles on, entering the Hunllef arena");
            // The arena barrier's action differs per id: try each name it might carry, then its default action.
            if (!Rs2GameObject.interact(barrier, "Pass-through") && !Rs2GameObject.interact(barrier, "Quick-pass")
                    && !Rs2GameObject.interact(barrier, "Pass")) {
                Rs2GameObject.interact(barrier);
            }
            sleep(1200);
            CustomGauntletScript.ghState = State.FIGHTING;
        }
    }

    // ------------------------------------------------------------------
    // --- Base helpers
    // ------------------------------------------------------------------

    private boolean atBase(TileObject bowl) {
        return bowl != null && distTo(bowl) <= BASE_NEAR_TILES;
    }

    /** Run (never teleport) back to the Singing Bowl, or the base centre while the bowl isn't in the scene. */
    private void walkToBase(TileObject bowl) {
        if (Rs2Player.isMoving() && !nearWalkDestination()) return;
        if (!Rs2Player.isRunEnabled()) Rs2Player.toggleRunEnergy(true);
        if (bowl != null) {
            if (approachIfFar(bowl.getLocalLocation(), "The Singing Bowl")) return;
            Microbot.log(String.format("[Prep] Running back to the Singing Bowl (%d tiles)", distTo(bowl)));
            Rs2Walker.walkFastLocal(bowl.getLocalLocation());
            return;
        }
        WorldPoint base = grid.getBaseCenterPoint();
        LocalPoint lp = base == null ? null : Microbot.getClientThread().runOnClientThreadOptional(
                () -> LocalPoint.fromWorld(Microbot.getClient().getTopLevelWorldView(), base)).orElse(null);
        if (lp == null) {
            logWaiting("[Prep] Can't find the way back to the base: the Singing Bowl isn't in the loaded scene");
            return;
        }
        if (approachIfFar(lp, "The base")) return;
        Microbot.log("[Prep] Running back towards the base");
        Rs2Walker.walkFastLocal(lp);
    }

    /** Drop the raw fish at the base (picked up again at the end of the final prep). */
    private boolean dropFishAtBase() {
        if (!Rs2Inventory.hasItem(GauntletChecklist.RAW_FISH_NAMES, true)) return false;
        if (closeBowl()) return true;
        int fishCount = checklist.getRawFishCount();
        Microbot.log("[Prep] Dropping " + fishCount + " raw paddlefish at the base");
        Rs2Inventory.dropAll(true, GauntletChecklist.RAW_FISH_NAMES);
        checklist.setDroppedFishAtBase(checklist.getDroppedFishAtBase() + fishCount);
        return true;
    }

    private boolean closeBowl() {
        if (!isSingingBowlOpen()) return false;
        Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
        sleep(600);
        return true;
    }

    /** Crush shards with the pestle and mortar until we hold the dust for 3 potions. */
    private boolean crushDust() {
        if (quantity(DUST_NAMES) >= TARGET_DUST || !Rs2Inventory.hasItem(PESTLE_NAME)) return false;
        String shards = firstHeld(GauntletChecklist.SHARD_NAMES);
        if (shards == null) {
            logWaiting("[Prep] No shards left to crush into dust");
            return false;
        }
        if (closeBowl()) return true;
        int[] before = checklist.carriedMaterials();
        Microbot.log(String.format("[Prep] Crushing shards into dust (%d/%d dust)", quantity(DUST_NAMES), TARGET_DUST));
        Rs2Inventory.combine(PESTLE_NAME, shards);
        Rs2Inventory.waitForInventoryChanges(3000);
        if (Rs2Widget.isProductionWidgetOpen()) {
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            Rs2Inventory.waitForInventoryChanges(3000);
        }
        checklist.recordSpent(before);
        return true;
    }

    /**
     * Wear every armour piece we carry and wield a weapon if none is, all in one go: one item per loop pass with a
     * fixed wait took ~2 seconds per piece.
     *
     * @return true if anything was put on
     */
    private boolean equipGauntletGear() {
        List<String[]> toEquip = new ArrayList<>(); // {name fragment, action}
        for (String piece : new String[]{"helm (", "body (", "legs ("}) {
            if (Rs2Inventory.hasItem(piece, false)) toEquip.add(new String[]{piece, "Wear"});
        }
        boolean wielding = Rs2Equipment.isWearing("staff (", false) || Rs2Equipment.isWearing("bow (", false)
                || Rs2Equipment.isWearing("halberd (", false);
        if (!wielding) {
            for (String weapon : new String[]{"staff (perfected)", "staff (attuned)", "bow (perfected)", "staff (", "bow ("}) {
                if (!Rs2Inventory.hasItem(weapon, false)) continue;
                toEquip.add(new String[]{weapon, "Wield"});
                break;
            }
        }
        if (toEquip.isEmpty()) return false;
        if (isSingingBowlOpen()) {
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            sleepUntil(() -> !isSingingBowlOpen(), 600);
        }
        StringBuilder names = new StringBuilder();
        for (String[] item : toEquip) {
            Rs2Inventory.interact(item[0], item[1], false);
            names.append(item[0]).append("...) ");
            sleep(Rs2Random.between(90, 180));
        }
        Microbot.log("[Prep] Put on " + names.toString().trim());
        Rs2Inventory.waitForInventoryChanges(1200);
        return true;
    }

    /** Drop the first item we still carry from the list (exact names). One item type per call. */
    private boolean dropItems(String[] names) {
        for (String name : names) {
            if (!Rs2Inventory.hasItem(name, true)) continue;
            if (closeBowl()) return true;
            Microbot.log("[Prep] Dropping " + name + " (no longer needed)");
            Rs2Inventory.dropAll(true, name);
            sleep(600);
            return true;
        }
        return false;
    }

    /** One step of brewing: vial -> water at the pump, + grym leaf, + dust = Egniol potion. */
    private boolean brewPotionStep() {
        if (Rs2Inventory.hasItem(VIAL_NAME, true)) {
            TileObject pump = findTileObject(WATER_PUMP_IDS);
            if (pump == null) return false;
            Microbot.log("[Prep] Filling a vial at the water pump");
            useItemOn(VIAL_NAME, pump);
            return true;
        }
        if (Rs2Inventory.hasItem(GauntletChecklist.LEAF_NAMES, true) && Rs2Inventory.hasItem(WATER_VIAL_NAME, true)) {
            Microbot.log("[Prep] Adding a grym leaf to a vial of water");
            Rs2Inventory.combine(GauntletChecklist.LEAF_NAMES[0], WATER_VIAL_NAME);
            Rs2Inventory.waitForInventoryChanges(2000);
            return true;
        }
        String dust = firstHeld(DUST_NAMES);
        if (dust != null && Rs2Inventory.hasItem(UNF_POTION_NAME, true)) {
            Microbot.log("[Prep] Adding crystal dust to a grym potion (unf) -> Egniol potion");
            Rs2Inventory.combine(dust, UNF_POTION_NAME);
            Rs2Inventory.waitForInventoryChanges(2000);
            return true;
        }
        return false;
    }

    private boolean pickUpFish() {
        if (Rs2Inventory.isFull()) return false;
        if (!Rs2GroundItem.loot(GauntletChecklist.RAW_FISH_NAMES[0], 15)) return false;
        Microbot.log("[Prep] Picking up raw paddlefish from the floor");
        checklist.setDroppedFishAtBase(Math.max(0, checklist.getDroppedFishAtBase() - 1));
        return true;
    }

    private boolean cookFish() {
        if (!Rs2Inventory.hasItem(GauntletChecklist.RAW_FISH_NAMES, true)) return false;
        if (Rs2Widget.isProductionWidgetOpen()) {
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            sleep(600);
            return true;
        }
        if (busyWithOwnAction() || Rs2Player.isMoving()) return true; // cooking the batch
        TileObject range = findTileObject(RANGE_STOVE_IDS);
        if (range == null) return false;
        Microbot.log(String.format("[Prep] Cooking paddlefish (%d raw left)", checklist.getRawFishCount()));
        useItemOn(GauntletChecklist.RAW_FISH_NAMES[0], range);
        return true;
    }

    private void useItemOn(String itemName, TileObject station) {
        Rs2ItemModel item = Rs2Inventory.get(itemName, true);
        if (item == null) return;
        Rs2Inventory.useItemOnObject(item.getId(), station.getId());
        Rs2Inventory.waitForInventoryChanges(3000);
    }

    private int vialFamilyCount() {
        return quantity(VIAL_NAME, WATER_VIAL_NAME, UNF_POTION_NAME) + Rs2Inventory.count("Egniol potion", false);
    }

    private static int quantity(String... names) {
        int total = 0;
        for (String name : names) total += Rs2Inventory.itemQuantity(name, true);
        return total;
    }

    private static String firstHeld(String[] names) {
        for (String name : names) if (Rs2Inventory.hasItem(name, true)) return name;
        return null;
    }

    // ------------------------------------------------------------------
    // --- Singing Bowl
    // ------------------------------------------------------------------

    /**
     * Something to make at the bowl: the menu option is found by name, the hotkey is only a fallback. {@code cost}
     * gives what the next press uses as {shards, ore, bark, linum} (null supplier or result = unknown, just try).
     */
    private static final class BowlStep {
        final String label, option;
        final char key;
        final BooleanSupplier done;
        final Supplier<int[]> cost;

        BowlStep(String label, String option, char key, BooleanSupplier done, Supplier<int[]> cost) {
            this.label = label;
            this.option = option;
            this.key = key;
            this.done = done;
            this.cost = cost;
        }
    }

    // Armour: basic (tier 1) helm/body/legs and the attuned (tier 2) helm/legs cost 50 shards + 1 ore, bark and linum
    // each; the attuned body costs 100 shards + 2 of each. Upgrading a weapon to attuned costs 50 shards, a vial 10.
    private static final int[] ARMOUR_COST = {50, 1, 1, 1};
    private static final int[] ATTUNED_BODY_COST = {100, 2, 2, 2};
    private static final int[] ATTUNED_WEAPON_COST = {50, 0, 0, 0};
    private static final int[] VIAL_COST = {10, 0, 0, 0};
    private static final int[] TELEPORT_CRYSTAL_COST = {50, 0, 0, 0};

    /** What the next press on this armour piece costs: basic if we have none, else the attuned upgrade. */
    private int[] armourNextCost(String piece) {
        if (hasItemOrWorn(piece + " (attuned)")) return null;
        if (!hasItemOrWorn(piece + " (")) return ARMOUR_COST;
        return "body".equals(piece) ? ATTUNED_BODY_COST : ARMOUR_COST;
    }

    /** Only the basic -> attuned weapon upgrade has a known cost; the basic weapon and the perfected one we just try. */
    private int[] staffNextCost() {
        return hasItemOrWorn("staff (basic)") ? ATTUNED_WEAPON_COST : null;
    }

    private int[] bowNextCost() {
        return hasItemOrWorn("bow (basic)") ? ATTUNED_WEAPON_COST : null;
    }

    /** Null if we carry enough for {@code cost}, else what's short, e.g. "20 shards, 1 linum". */
    private String shortfall(int[] cost) {
        int[] have = checklist.carriedMaterials();
        String[] names = {"shards", "ore", "bark", "linum"};
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (have[i] < cost[i]) missing.add((cost[i] - have[i]) + " " + names[i]);
        }
        return missing.isEmpty() ? null : String.join(", ", missing);
    }

    /**
     * One bowl press per call. A step that changes nothing (not enough shards/resources) or isn't done after
     * MAX_BOWL_PRESSES is skipped for this phase. Closes the bowl and returns true once every step is done or skipped.
     */
    private boolean runBowlSteps(TileObject bowl, List<BowlStep> steps) {
        for (BowlStep step : steps) {
            if (bowlSkipped.contains(step.label) || step.done.getAsBoolean()) continue;
            int[] cost = step.cost == null ? null : step.cost.get();
            String shortBy = cost == null ? null : shortfall(cost);
            if (shortBy != null) {
                bowlSkipped.add(step.label);
                Microbot.log(String.format("[Prep] Not making %s yet: short %s", step.label, shortBy));
                continue;
            }
            int presses = bowlPresses.merge(step.label, 1, Integer::sum);
            if (presses > MAX_BOWL_PRESSES) {
                bowlSkipped.add(step.label);
                Microbot.log(String.format("[Prep] %s still not made after %d presses - skipping it (wrong menu option?)", step.label, MAX_BOWL_PRESSES));
                continue;
            }
            if (!openBowl(bowl)) return false;
            if (!singOption(step)) {
                bowlSkipped.add(step.label);
                Microbot.log("[Prep] Couldn't make " + step.label + " at the bowl (not enough shards/resources?) - skipping it");
            }
            return false;
        }
        return !closeBowl();
    }

    private boolean openBowl(TileObject bowl) {
        if (isSingingBowlOpen()) return true;
        if (!Rs2GameObject.interact(bowl, "Sing-dialogue")) Rs2GameObject.interact(bowl);
        sleepUntil(this::isSingingBowlOpen, 5000);
        return isSingingBowlOpen();
    }

    /** @return true if the inventory changed (something was made) */
    private boolean singOption(BowlStep step) {
        int[] before = checklist.carriedMaterials();
        Widget option = findBowlOption(step.option);
        if (option != null) {
            Microbot.log(String.format("[Prep] Singing %s (menu option \"%s\")", step.label, Text.removeTags(option.getName())));
            Rs2Widget.clickWidget(option);
        } else {
            Microbot.log(String.format("[Prep] Singing %s (no \"%s\" option found in the bowl menu - pressing '%s')",
                    step.label, step.option, step.key == ' ' ? "space" : String.valueOf(step.key)));
            Rs2Keyboard.keyPress(step.key);
        }
        boolean changed = Rs2Inventory.waitForInventoryChanges(3000);
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            changed = false;
        }
        checklist.recordSpent(before);
        return changed;
    }

    /** The bowl menu entry whose item name contains {@code fragment} (case-insensitive), or null. */
    private Widget findBowlOption(String fragment) {
        String want = fragment.toLowerCase();
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget root = Microbot.getClient().getWidget(SINGING_BOWL_GROUP, 0);
            return root == null ? null : findOptionIn(root, want, 0);
        }).orElse(null);
    }

    private static Widget findOptionIn(Widget w, String want, int depth) {
        if (w == null || w.isHidden() || depth > 6) return null;
        String name = w.getName();
        if (name != null && Text.removeTags(name).toLowerCase().contains(want)) return w;
        for (Widget[] group : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()}) {
            if (group == null) continue;
            for (Widget c : group) {
                Widget found = findOptionIn(c, want, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // --- Open rooms
    // ------------------------------------------------------------------

    private boolean[][] currentOpenRooms() {
        return Microbot.getClientThread().runOnClientThreadOptional(
                () -> openRooms(Microbot.getClient(), collectAllTileObjects())).orElse(null);
    }

    /** True if the point is in a room we can walk into (always true while the grid isn't anchored). */
    private boolean inOpenRoom(boolean[][] open, LocalPoint lp) {
        if (open == null || lp == null) return true;
        int[] g = grid.getGridCoordinates(WorldPoint.fromLocal(Microbot.getClient(), lp));
        return g == null || open[g[0]][g[1]];
    }

    private int distTo(TileObject obj) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> tileDist(obj)).orElse(Integer.MAX_VALUE);
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
        return findTileObject(ids, maxTiles, o -> true);
    }

    private TileObject findTileObject(Set<Integer> ids, int maxTiles, Predicate<TileObject> filter) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            TileObject closest = null;
            int closestDist = Integer.MAX_VALUE;
            for (TileObject obj : collectAllTileObjects()) { // already on the client thread here
                if (obj == null || !ids.contains(obj.getId()) || !filter.test(obj)) continue;
                int dist = tileDist(obj);
                if (dist <= maxTiles && dist < closestDist) {
                    closestDist = dist;
                    closest = obj;
                }
            }
            return closest;
        }).orElse(null);
    }

    /**
     * Step one tile (instance-safe local walk) to interrupt a continuous action such as fishing. Tries each
     * cardinal neighbour and walks to the first valid scene tile; the client paths one tile, breaking the action.
     */
    /** Drop every inventory item matching any of the given exact names (covers Corrupted + Crystal variants). */
    private void dropByName(String... names) {
        for (String name : names) {
            Rs2Inventory.drop(name, true);
        }
    }

    private void stepOffCurrentTile() {
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player p = Microbot.getClient().getLocalPlayer();
            LocalPoint lp = p == null ? null : p.getLocalLocation();
            if (lp == null) return false;
            int sx = lp.getSceneX(), sy = lp.getSceneY();
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                LocalPoint target = LocalPoint.fromScene(sx + d[0], sy + d[1]);
                if (target != null) {
                    Rs2Walker.walkFastLocal(target);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * @param stackableResult names of the item this resource yields when it stacks (ore/bark/linum). When the
     *        inventory is full we still gather these IF we already hold the item, because it stacks into the
     *        existing slot (no free slot needed). Pass null for non-stackable yields (leaf, fish), which
     *        genuinely need a free slot, so a full inventory blocks them.
     * @param maxTiles how far to look (game click-to-walk paths us there through the open rooms)
     */
    private boolean gatherNearestObject(Set<Integer> objectIds, String action, IntSupplier remaining, String[] stackableResult, int maxTiles) {
        // A full inventory only blocks us if the yield needs a NEW slot. Stackable yields we already hold just
        // add to the existing stack, so keep gathering those (the old blanket isFull() guard made the bot run
        // past ore/linum it still needed once the inventory filled with paddlefish).
        boolean stacksIntoExisting = stackableResult != null && Rs2Inventory.hasItem(stackableResult, true);
        if (Rs2Inventory.isFull() && !stacksIntoExisting) return false;
        TileObject obj = findGatherTarget(objectIds, maxTiles);
        if (obj == null) return false;
        // Click once, then WAIT while the gather runs. Resource nodes are game objects (not NPCs), so the
        // engine's isInteracting() flag stays false while mining/chopping/picking — only the animation tells
        // us we're busy. Re-clicking every loop (the old behaviour) just spam-restarts the action.
        if (!busyWithOwnAction() && approachIfFar(obj.getLocalLocation(), action + " target")) return true;
        if (!busyWithOwnAction() && !Rs2Player.isMoving()) {
            Microbot.log("[Prep] Gathering (" + action + ") " + distTo(obj) + " tiles away");
            Rs2GameObject.interact(obj, action);
            activeGatherRemaining = remaining; // remember what we're on so we can stop at target
        }
        return true;
    }

    /** Nearest resource object within maxTiles, in the phase's scout area and in a room we can walk into. */
    private TileObject findGatherTarget(Set<Integer> objectIds, int maxTiles) {
        boolean[][] open = currentOpenRooms();
        return findTileObject(objectIds, maxTiles, o -> inScoutArea(o.getLocalLocation()) && inOpenRoom(open, o.getLocalLocation()));
    }

    /**
     * Teleports back to the starting base using the gauntlet teleport crystal. Matched by name (both
     * variants) and the correct "Activate" action - the old "Teleport crystal"/"Teleport" call matched
     * neither the corrupted item name nor its real action, so it silently did nothing and the script
     * dropped fish / crafted wherever it happened to be standing. Sleeps so the teleport lands before the
     * caller proceeds.
     *
     * @return true if the teleport was issued
     */
    private boolean teleportToBase() {
        String[] names = {"Corrupted teleport crystal", "Teleport crystal"};
        if (!Rs2Inventory.hasItem(names, false)) return false;
        Microbot.log("[Prep] Activating teleport crystal to return to base");
        boolean issued = Rs2Inventory.interact(names, "Activate");
        if (issued) sleep(2400);
        return issued;
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
     * Lights the nearest unlit node in the current room (phase 1: inside the scout ring around the Hunllef room).
     */
    private boolean scoutBaseRooms() {
        return lightNode(NODE_SEARCH_TILES, "doorway node", false);
    }

    /**
     * Fallback for when we're cornered: nothing to gather, no near node, no barrier within reach. Reach for
     * the nearest unlit node up to FRONTIER_SEARCH_TILES away and let click-to-walk path through the opened
     * rooms to it. It MIGHT be behind a wall (Chebyshev distance ignores walls), but standing still forever
     * is worse, and the nearest unlit node is normally on the reachable frontier.
     */
    private boolean scoutFrontierNode() {
        return lightNode(FRONTIER_SEARCH_TILES, "distant frontier node", false);
    }

    /**
     * Light the nearest reachable unlit node. COMMITS: it only (re)issues the click when idle - while walking
     * to the node it holds, so it doesn't re-pick a different "nearest" node every loop and oscillate.
     */
    private boolean lightNode(int maxTiles, String label, boolean outward) {
        TileObject node = findDoorwayNodeToLight(maxTiles, outward);
        String skipped = skippedClosedNodes;
        if (!skipped.isEmpty() && !skipped.equals(loggedSkippedNodes)) {
            loggedSkippedNodes = skipped;
            Microbot.log("[Scout] Not lighting nodes into rooms we can't reach (closed on both sides, or the Hunllef room): " + skipped);
        }
        if (node == null) return false;
        if (!busyWithOwnAction() && approachIfFar(node.getLocalLocation(), "Node")) return true;
        if (!Rs2Player.isMoving() && !busyWithOwnAction()) {
            Microbot.log(String.format("[Scout] Lighting %s at %s%s", label, node.getWorldLocation(), chosenNodeRooms));
            Rs2GameObject.interact(node, "Light");
            sleep(600);
            scanScoutRooms();
        }
        return true; // committed - either walking to it or just lit it
    }

    /**
     * @param outward phase 2: prefer the node whose closed room is furthest out (ring around the Hunllef room), so we
     *                head straight for the rim and then along it; nearest first among equals
     */
    private TileObject findDoorwayNodeToLight(int maxTiles, boolean outward) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            // Nearest reachable unlit node within maxTiles (Chebyshev, scene coords). Nearest keeps it to the
            // open/reachable frontier - a "farthest" pick just targets off-screen tiles the player can't path
            // to. By phase 2 the inner rooms are already open, so the nearest frontier node heads outward.
            // The game only lets us reach a node from a room that's open, and distance ignores walls: from the base
            // west of the Hunllef we walked for a node between the (closed) north-west and north rooms.
            List<TileObject> objects = collectAllTileObjects();
            boolean[][] open = openRooms(client, objects);
            Player me = client.getLocalPlayer();
            int[] myRoom = me == null || me.getLocalLocation() == null ? null
                    : grid.getGridCoordinates(WorldPoint.fromLocal(client, me.getLocalLocation()));
            int playerRing = myRoom == null ? 0 : GauntletGrid.ringOf(myRoom[0], myRoom[1]);
            TileObject nearest = null;
            int nearestScore = Integer.MIN_VALUE;
            String nearestRooms = "";
            StringBuilder skipped = new StringBuilder();
            for (TileObject obj : objects) {
                if (obj == null) continue;
                int dist = tileDist(obj);
                if (dist > maxTiles) continue;
                if (!isUnlitNode(obj, client)) continue;
                if (!inScoutArea(obj.getLocalLocation())) continue; // a node on the ring's outer edge opens the next ring
                int[] rooms = open == null ? null : nodeRooms(client, obj);
                if (rooms != null && (touchesHunllefRoom(rooms)
                        || (!isOpen(open, rooms[0], rooms[1]) && !isOpen(open, rooms[2], rooms[3])))) {
                    skipped.append(String.format("(%d,%d)-(%d,%d) ", rooms[0], rooms[1], rooms[2], rooms[3]));
                    continue;
                }
                int score = -dist;
                boolean firstOpen = rooms != null && isOpen(open, rooms[0], rooms[1]);
                boolean secondOpen = rooms != null && isOpen(open, rooms[2], rooms[3]);
                if (firstOpen && secondOpen) continue; // both rooms already open: lighting it gets us nowhere new
                // Phase 1: only nodes into a room of the current ring (an outer-edge node opened a ring-2 room while
                // ring-1 rooms were still closed).
                if (innerPhase() && rooms != null && grid.getHunllefCenterPoint() != null) {
                    int closedRing = firstOpen ? GauntletGrid.ringOf(rooms[2], rooms[3]) : GauntletGrid.ringOf(rooms[0], rooms[1]);
                    if (closedRing > scoutRing) continue;
                }
                if (outward && rooms != null) {
                    // Heading out: the outermost room first, at any distance. On the rim: the nearest node, rim rooms
                    // only slightly preferred (from a corner it walked 46 tiles back past the base for a rim node).
                    int closedRing = firstOpen ? GauntletGrid.ringOf(rooms[2], rooms[3]) : GauntletGrid.ringOf(rooms[0], rooms[1]);
                    score += closedRing * (playerRing >= GauntletGrid.HUNLLEF_X ? RIM_RING_BONUS_TILES : 1000);
                }
                if (score > nearestScore) {
                    nearestScore = score;
                    nearest = obj;
                    nearestRooms = rooms == null ? "" : String.format(" (opens (%d,%d)-(%d,%d))", rooms[0], rooms[1], rooms[2], rooms[3]);
                }
            }
            skippedClosedNodes = skipped.toString().trim();
            chosenNodeRooms = nearestRooms;
            return nearest;
        }).orElse(null);
    }

    /**
     * Rooms we can walk into: the base, the room we stand in, and both rooms of every lit node (lighting one needs one
     * side open, and opens the other). Null while the Hunllef room isn't placed. Client thread.
     */
    private boolean[][] openRooms(Client client, List<TileObject> objects) {
        if (grid.getHunllefCenterPoint() == null) return null;
        boolean[][] open = new boolean[GauntletGrid.GRID_SIZE][GauntletGrid.GRID_SIZE];
        GauntletRoom base = grid.getBaseRoom();
        if (base != null) open[base.getGridX()][base.getGridY()] = true;
        Player me = client.getLocalPlayer();
        if (me != null && me.getLocalLocation() != null) {
            int[] g = grid.getGridCoordinates(WorldPoint.fromLocal(client, me.getLocalLocation()));
            if (g != null) open[g[0]][g[1]] = true;
        }
        for (TileObject obj : objects) {
            if (obj == null || !LIT_NODE_IDS.contains(obj.getId())) continue;
            int[] rooms = nodeRooms(client, obj);
            if (rooms == null) continue;
            markOpen(open, rooms[0], rooms[1]);
            markOpen(open, rooms[2], rooms[3]);
        }
        // The Hunllef room is never a way through: the base's doorway into it is lit from the start, and counting it
        // as open made every node between it and a closed room look lightable ((3,3)-(3,2), clicked over and over).
        open[GauntletGrid.HUNLLEF_X][GauntletGrid.HUNLLEF_Y] = false;
        return open;
    }

    /**
     * The two rooms a doorway node connects, as {x1, y1, x2, y2}: the room it stands in, and the neighbour on the side
     * of the room centre it's offset towards (nodes sit at the doorway on a room's edge). Null if it's at a centre.
     */
    private int[] nodeRooms(Client client, TileObject node) {
        WorldPoint h = grid.getHunllefCenterPoint();
        LocalPoint lp = node.getLocalLocation();
        if (h == null || lp == null) return null;
        WorldPoint wp = WorldPoint.fromLocal(client, lp);
        int dx = wp.getX() - h.getX(), dy = wp.getY() - h.getY();
        int rx = Math.round((float) dx / GauntletGrid.ROOM_TILE_SIZE), ry = Math.round((float) dy / GauntletGrid.ROOM_TILE_SIZE);
        int ox = dx - rx * GauntletGrid.ROOM_TILE_SIZE, oy = dy - ry * GauntletGrid.ROOM_TILE_SIZE;
        if (ox == 0 && oy == 0) return null;
        int gx = GauntletGrid.HUNLLEF_X + rx, gy = GauntletGrid.HUNLLEF_Y + ry;
        return Math.abs(ox) >= Math.abs(oy)
                ? new int[]{gx, gy, gx + Integer.signum(ox), gy}
                : new int[]{gx, gy, gx, gy + Integer.signum(oy)};
    }

    private static boolean touchesHunllefRoom(int[] rooms) {
        return (rooms[0] == GauntletGrid.HUNLLEF_X && rooms[1] == GauntletGrid.HUNLLEF_Y)
                || (rooms[2] == GauntletGrid.HUNLLEF_X && rooms[3] == GauntletGrid.HUNLLEF_Y);
    }

    private static boolean isOpen(boolean[][] open, int x, int y) {
        return x >= 0 && y >= 0 && x < GauntletGrid.GRID_SIZE && y < GauntletGrid.GRID_SIZE && open[x][y];
    }

    private static void markOpen(boolean[][] open, int x, int y) {
        if (x >= 0 && y >= 0 && x < GauntletGrid.GRID_SIZE && y < GauntletGrid.GRID_SIZE) open[x][y] = true;
    }

    /** A gauntlet "node" (unlit doorway opener) - matched by id, or by name/"Light" action. Client thread. */
    private boolean isUnlitNode(TileObject obj, Client client) {
        if (NODE_IDS.contains(obj.getId())) return true;
        ObjectComposition comp = client.getObjectDefinition(obj.getId());
        if (comp == null) return false;
        String[] actions = comp.getActions();
        return "Node".equalsIgnoreCase(comp.getName())
                || (actions != null && Arrays.asList(actions).contains("Light"));
    }

    /**
     * Scans the rooms in our scout ring (phase 1) or the whole maze (later) that are loaded around us, in one pass over
     * the scene, and logs each room whose contents changed. Instance coords throughout ({@code WorldPoint.fromLocal}):
     * object world locations are template coords there and put everything in the wrong room.
     */
    private void scanScoutRooms() {
        if (grid.getHunllefCenterPoint() == null) return;
        int ring = innerPhase() ? scoutRing : GauntletGrid.HUNLLEF_X;
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            Player me = client.getLocalPlayer();
            if (me == null || me.getLocalLocation() == null) return null;
            WorldPoint here = WorldPoint.fromLocal(client, me.getLocalLocation());
            int[][][] counts = new int[GauntletGrid.GRID_SIZE][GauntletGrid.GRID_SIZE][5];
            for (TileObject obj : collectAllTileObjects()) {
                if (obj == null) continue;
                int id = obj.getId();
                int kind = ORE_DEPOSIT_IDS.contains(id) ? 0 : PHREN_ROOTS_IDS.contains(id) ? 1 : LINUM_TIRINUM_IDS.contains(id) ? 2
                        : GRYM_ROOT_IDS.contains(id) ? 3 : FISHING_SPOT_IDS.contains(id) ? 4 : -1;
                if (kind < 0) continue;
                int[] g = grid.getGridCoordinates(WorldPoint.fromLocal(client, obj.getLocalLocation()));
                if (g != null) counts[g[0]][g[1]][kind]++;
            }
            GauntletRoom.DemibossType[][] boss = new GauntletRoom.DemibossType[GauntletGrid.GRID_SIZE][GauntletGrid.GRID_SIZE];
            boolean[][] weak = new boolean[GauntletGrid.GRID_SIZE][GauntletGrid.GRID_SIZE];
            boolean[][] medium = new boolean[GauntletGrid.GRID_SIZE][GauntletGrid.GRID_SIZE];
            for (Rs2NpcModel npc : Microbot.getRs2NpcCache().query().toListOnClientThread()) {
                if (npc == null || npc.getLocalLocation() == null) continue;
                int[] g = grid.getGridCoordinates(WorldPoint.fromLocal(client, npc.getLocalLocation()));
                if (g == null) continue;
                int id = npc.getId();
                if (DRAGON_IDS.contains(id)) boss[g[0]][g[1]] = GauntletRoom.DemibossType.CORRUPTED_DRAGON;
                else if (DARK_BEAST_IDS.contains(id)) boss[g[0]][g[1]] = GauntletRoom.DemibossType.CORRUPTED_DARK_BEAST;
                else if (BEAR_IDS.contains(id)) boss[g[0]][g[1]] = GauntletRoom.DemibossType.CORRUPTED_BEAR;
                else if (WEAK_MOB_IDS.contains(id)) weak[g[0]][g[1]] = true;
                else if (MEDIUM_MOB_IDS.contains(id)) medium[g[0]][g[1]] = true;
            }
            for (GauntletRoom room : grid.getRoomsWithinRing(ring)) {
                WorldPoint center = grid.getRoomCenter(room.getGridX(), room.getGridY());
                room.setCenterPoint(center);
                // Only rooms whose whole area is loaded around us; a room half outside the scene would read as emptied.
                if (Math.max(Math.abs(center.getX() - here.getX()), Math.abs(center.getY() - here.getY())) > ROOM_SCAN_RANGE) continue;
                int x = room.getGridX(), y = room.getGridY();
                int[] c = counts[x][y];
                room.setOreNodes(c[0]);
                room.setPhrenRoots(c[1]);
                room.setLinumPlants(c[2]);
                room.setGrymRoots(c[3]);
                room.setFishingSpots(c[4]);
                if (boss[x][y] != null) room.setDemiboss(boss[x][y]); // a demiboss stays known once seen
                room.setHasWeakMob(weak[x][y]);
                room.setHasMediumMob(medium[x][y]);
                if (room.getState() == GauntletRoom.ExplorationState.UNEXPLORED) room.setState(GauntletRoom.ExplorationState.SCOUTED);
                boolean any = c[0] + c[1] + c[2] + c[3] + c[4] > 0 || room.hasDemiboss() || weak[x][y] || medium[x][y];
                String summary = String.format("Ore=%d, Bark=%d, Linum=%d, Leaf=%d, Fish=%d, Demiboss=%s, WeakMob=%b",
                        c[0], c[1], c[2], c[3], c[4], room.getDemiboss(), weak[x][y]);
                if (!any || summary.equals(room.getLastScanSummary())) continue;
                room.setLastScanSummary(summary);
                Microbot.log(String.format("[Scout] Room (%d,%d) ring %d scanned: %s", x, y, GauntletGrid.ringOf(x, y), summary));
            }
            return null;
        });
    }

    private boolean hasItemOrWorn(String fragment) {
        return Rs2Inventory.hasItem(fragment, false) || Rs2Equipment.isWearing(fragment, false);
    }

    /** The Singing Bowl "What would you like to make?" interface (group 270) is open. Verified live: it is
     *  NOT a chat dialogue (isInDialogue is false) and NOT group 640 - it renders under group 270. */
    private boolean isSingingBowlOpen() {
        return Rs2Widget.isWidgetVisible(SINGING_BOWL_GROUP, SINGING_BOWL_TITLE_CHILD);
    }
}

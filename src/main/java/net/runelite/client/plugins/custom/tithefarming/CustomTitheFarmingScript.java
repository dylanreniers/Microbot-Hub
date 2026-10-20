package net.runelite.client.plugins.custom.tithefarming;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmMaterial;
import net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmState;
import net.runelite.client.plugins.custom.tithefarming.models.CustomTitheFarmPlant;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmState.HARVEST;
import static net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmState.PLANTING_SEEDS;
import static net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmState.RECHARING_RUN_ENERGY;
import static net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmState.REFILL_WATERCANS;
import static net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmState.STARTING;
import static net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue.hasSelectAnOption;

/**
 * TODO list:
 * -plants per hour
 * -check for seed dibber and spade inventory
 * -deposit sack
 * -test other plants
 * -move script into seperate folder
 */


public class CustomTitheFarmingScript extends Script {

    final int FARM_DOOR = 27445;
    final String FERTILISER = "gricoller's fertiliser";

    public static List<CustomTitheFarmPlant> plants = new ArrayList<>();


    public static CustomTitheFarmState state = CustomTitheFarmState.STARTING;

    public static int initialFruit = 0;
    public static int fruits = 0;

    public static final int WATERING_CANS_AMOUNT = 8;

    public static final int DISTANCE_THRESHOLD_MINIMAP_WALK = 8;

    public static int gricollerCanCharges = -1;

    public static boolean init = true;

    // Event-driven patch state: the plugin's onGameObjectSpawned records every tithe patch's current
    // object id here the instant it changes (same signal RuneLite's tithe plugin uses). Patch state
    // checks read this first so "is it watered?" flips immediately instead of lagging the once-per-
    // tick scene cache by ~2s. Keyed by the patch's world location; read by region-coord match.
    public static final Map<WorldPoint, Integer> PATCH_IDS = new ConcurrentHashMap<>();

    public static void recordPatch(WorldPoint wp, int id) {
        if (wp != null) PATCH_IDS.put(wp, id);
    }

    /** Latest known object id for the patch at these lane region coords (±1 tolerance), or -1. */
    public static int patchIdAt(int regionX, int regionY) {
        for (Map.Entry<WorldPoint, Integer> e : PATCH_IDS.entrySet()) {
            WorldPoint wp = e.getKey();
            if (Math.abs(wp.getRegionX() - regionX) <= 1 && Math.abs(wp.getRegionY() - regionY) <= 1) {
                return e.getValue();
            }
        }
        return -1;
    }

    private boolean allPlanted = false;

    // Cursor into the route-ordered `plants` list for the sweep in coreLoop(). Advances strictly
    // forward (wrapping at the end) so the player never runs back and forth between patches.
    private int sweepCursor = 0;

    // We refill water ONLY at the start of the run and right before the harvesting phase - never
    // mid-cycle (that walks back to the barrel and lets patches die). This flag gates the one-time
    // startup refill; the before-harvest refill is triggered from coreLoop's harvest transition.
    private boolean startupRefillPending = true;

    public void init(CustomTitheFarmingConfig config) {
        // Fixed route for lanes 1 (west, x=35) and 2 (east, x=40). Starting at the south end we
        // work the west patch then the east patch of each row and move north through both growing
        // sections - 16 patches as 8 west/east pairs (index 1..16). Then the 4 remaining patches on
        // lane 3 (east, x=45), worked north->south since we finish the pairs at the north end
        // (index 17..20). Index order is the visiting order, which is also the watering order, so
        // we never backtrack further than one row and travel stays minimal.
        plants = new ArrayList<>(Arrays.asList(
                new CustomTitheFarmPlant(35, 25, 1),
                new CustomTitheFarmPlant(40, 25, 2),
                new CustomTitheFarmPlant(35, 28, 3),
                new CustomTitheFarmPlant(40, 28, 4),
                new CustomTitheFarmPlant(35, 31, 5),
                new CustomTitheFarmPlant(40, 31, 6),
                new CustomTitheFarmPlant(35, 34, 7),
                new CustomTitheFarmPlant(40, 34, 8),
                new CustomTitheFarmPlant(35, 40, 9),
                new CustomTitheFarmPlant(40, 40, 10),
                new CustomTitheFarmPlant(35, 43, 11),
                new CustomTitheFarmPlant(40, 43, 12),
                new CustomTitheFarmPlant(35, 46, 13),
                new CustomTitheFarmPlant(40, 46, 14),
                new CustomTitheFarmPlant(35, 49, 15),
                new CustomTitheFarmPlant(40, 49, 16),
                new CustomTitheFarmPlant(45, 49, 17),
                new CustomTitheFarmPlant(45, 46, 18),
                new CustomTitheFarmPlant(45, 43, 19),
                new CustomTitheFarmPlant(45, 40, 20)));
    }


    public boolean run(CustomTitheFarmingConfig config) {
        init = true;
        plants = new ArrayList<>();
        state = STARTING;
        allPlanted = false;
        startupRefillPending = true;
        sweepCursor = 0;
        Microbot.log("Tithe farming script started");
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                if (BreakHandlerScript.isBreakActive()) return;

                if (init) {
                    state = STARTING;
                    plants = new ArrayList<>();
                    Rs2ItemModel rs2ItemSeed = Rs2Inventory.get(CustomTitheFarmMaterial.getSeedForLevel().getFruitId());
                    initialFruit = rs2ItemSeed == null ? 0 : rs2ItemSeed.getQuantity();
                    init = false;
                    sleep(2000); //extra sleep to have the game initialize correctly
                }

                //Dialogue stuff only applicable if you enter for the first time
                if (Rs2Dialogue.isInDialogue()) {
                    Rs2Dialogue.clickContinue();
                    sleep(400, 600);
                    return;
                }

                if (hasSelectAnOption()) {
                    Rs2Keyboard.keyPress('3');
                    sleep(1500, 1800);
                    return;
                }

                if (!isInMinigame() && !Rs2Inventory.hasItem(CustomTitheFarmMaterial.getSeedForLevel().getName())) {
                    state = CustomTitheFarmState.TAKE_SEEDS;
                }

                if (validateSeedsAndPatches() && isInMinigame()) {
                    state = CustomTitheFarmState.LEAVE;
                }

                if (Rs2Inventory.hasItemAmount(CustomTitheFarmMaterial.getSeedForLevel().getFruitId(), config.storeFruitThreshold())) {
                    depositSack();
                    return;
                }

                switch (state) {
                    case LEAVE:
                        if (!depositSack()) {
                            leave();
                        }
                        BreakHandlerScript.setLockState(false);
                    break;
                    case TAKE_SEEDS:
                        if (isInMinigame()) {
                            state = CustomTitheFarmState.STARTING;
                        } else {
                            takeSeeds();
                            if (Rs2Inventory.hasItem(CustomTitheFarmMaterial.getSeedForLevel().getName())) {
                                enter();
                            }
                        }
                        break;
                    case STARTING:
                        Rs2Player.toggleRunEnergy(true);
                        Rs2Tab.switchTo(InterfaceTab.INVENTORY);
                        init(config);
                        validateInventory();
                        DropFertiliser();
                        validateRunEnergy();
                        if (state != RECHARING_RUN_ENERGY)
                            // Refill once at the very start of the run; on later replant cycles the
                            // cans were already topped up in the before-harvest refill, so skip it.
                            state = startupRefillPending ? REFILL_WATERCANS : PLANTING_SEEDS;
                        break;
                    case RECHARING_RUN_ENERGY:
                        validateRunEnergy();
                        break;
                    case REFILL_WATERCANS:
                        refillWaterCans(config);
                        BreakHandlerScript.setLockState(false);
                        sleepGaussian(800, 200);
                        break;
                    case PLANTING_SEEDS:
                    case HARVEST:
                        BreakHandlerScript.setLockState(true);
                        coreLoop(config);
                        break;
                }

                if (config.enableDebugging() && plants.stream().anyMatch(x -> x.getGameObject() == null)) {
                    Microbot.log("There is an empty plant gameobject!");
                }

            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }

        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }

    /**
     * ALL PRIVATE SCRIPT METHODS
     */

    private void coreLoop(CustomTitheFarmingConfig config) {
        if (Rs2Player.isMoving()) return;

        // Once the whole field has been harvested, restart the cycle (refill + replant).
        if (state == HARVEST && hasAllEmptyPatches()) {
            state = STARTING;
            allPlanted = false;
            sweepCursor = 0;
            return;
        }

        // Flip allPlanted once every patch is known and none is still empty.
        if (plants.stream().allMatch(p -> p.currentId() != -1)
                && plants.stream().noneMatch(CustomTitheFarmPlant::isEmptyPatch))
            allPlanted = true;

        debugField(null);

        // SWEEP the field in route order with a moving cursor. The old loop re-picked a global
        // "best" patch every 100ms, so when a lower-index patch went dry it abandoned its forward
        // progress and ran all the way back - a lot of wasted travel. Instead we keep a cursor and
        // move strictly FORWARD through the route, acting on each patch that needs something, only
        // wrapping back to the start after a full lap. That's exactly how the field is swept by hand.
        //   - empty patch        -> plant + water (only while not harvesting)
        //   - dry patch/seedling -> water
        //   - ripe patch         -> harvest (only once in HARVEST state)
        int n = plants.size();
        for (int step = 0; step < n; step++) {
            int i = (sweepCursor + step) % n;
            CustomTitheFarmPlant p = plants.get(i);

            if (p.isEmptyPatch() && state != HARVEST) {
                sweepCursor = i;
                plantAndWater(p);
                sweepCursor = (i + 1) % n;
                return;
            }
            if (p.isValidToWater()) {
                sweepCursor = i;
                water(p);
                sweepCursor = (i + 1) % n;
                return;
            }
            if (state == HARVEST && p.isValidToHarvest()) {
                sweepCursor = i;
                harvest(p);
                sweepCursor = (i + 1) % n;
                return;
            }
        }

        // A full lap found nothing to plant or water. If the field has grown out (something ripe),
        // top up the cans ONCE right before the harvest phase, then harvest. finishRefill() flips
        // the state to HARVEST. (In HARVEST state we just idle here until the next ripe patch.)
        if (state != HARVEST && plants.stream().anyMatch(CustomTitheFarmPlant::isValidToHarvest)) {
            state = REFILL_WATERCANS;
        }
    }

    // One tight action per patch, all keyed off OBJECT STATE (like RuneLite's tithe plugin reads it
    // from the object id), never animations/fixed delays: we return the instant the patch's object
    // changes to the next state, so there is no idle time after an action.
    private void plantAndWater(CustomTitheFarmPlant plant) {
        long t0 = System.currentTimeMillis();
        Rs2Inventory.interact(CustomTitheFarmMaterial.getSeedForLevel().getName(), "Use");
        clickPatch(plant);
        boolean seedling = sleepUntil(plant::isValidToWater, 4000); // seedling object = planted
        boolean wet = false;
        if (seedling) {
            Rs2Inventory.interact(CustomTitheFarmMaterial.getWateringCan(), "Use");
            clickPatch(plant);
            wet = sleepUntil(plant::isWatered, 3000);               // WET object = watered
        }
        plant.setPlanted(Instant.now());
        Microbot.log(String.format("[TF] PLANT #%d seedling=%b wet=%b TOTAL=%dms",
                plant.getIndex(), seedling, wet, System.currentTimeMillis() - t0));
    }

    private void water(CustomTitheFarmPlant plant) {
        long t0 = System.currentTimeMillis();
        clickPatch(plant, "water");
        boolean wet = sleepUntil(plant::isWatered, 3000);
        plant.setPlanted(Instant.now());
        Microbot.log(String.format("[TF] WATER #%d wet=%b TOTAL=%dms",
                plant.getIndex(), wet, System.currentTimeMillis() - t0));
    }

    private void harvest(CustomTitheFarmPlant plant) {
        long t0 = System.currentTimeMillis();
        clickPatch(plant, "harvest");
        boolean done = sleepUntil(plant::isEmptyPatch, 3000);
        Microbot.log(String.format("[TF] HARVEST #%d done=%b TOTAL=%dms",
                plant.getIndex(), done, System.currentTimeMillis() - t0));
    }

    // ───────────────────────── DEBUG INSTRUMENTATION (temporary) ─────────────────────────
    // Throttled whole-field state summary (incl. DEAD / untracked NONE). Runs on the script
    // executor thread so Microbot.log() is safe here; reads the object cache directly (no invoke).
    private static long debugLastLogMs = 0;

    private void debugField(CustomTitheFarmPlant pick) {
        long now = System.currentTimeMillis();
        if (now - debugLastLogMs < 500) return;
        debugLastLogMs = now;

        int empty = 0, dry = 0, wet = 0, ripe = 0, dead = 0, none = 0;
        StringBuilder deadIdx = new StringBuilder();
        for (CustomTitheFarmPlant p : plants) {
            switch (debugClassify(debugRawId(p))) {
                case "EMPTY": empty++; break;
                case "DRY": dry++; break;
                case "WET": wet++; break;
                case "RIPE": ripe++; break;
                case "DEAD": dead++; deadIdx.append('#').append(p.getIndex()).append(' '); break;
                default: none++;
            }
        }
        String phase = (!allPlanted && state != HARVEST) ? "PLANT" : (state == HARVEST ? "HARVEST" : "MAINT");
        String pickStr = pick == null ? "-" : ("#" + pick.getIndex() + "(" + debugClassify(debugRawId(pick)) + ")");
        Microbot.log(String.format("[TF] %-7s allPlanted=%-5b pick=%-10s | empty=%d dry=%d wet=%d ripe=%d DEAD=%d none=%d %s",
                phase, allPlanted, pickStr, empty, dry, wet, ripe, dead, none,
                dead > 0 ? ("dead=" + deadIdx) : ""));
    }

    private static int debugRawId(CustomTitheFarmPlant plant) {
        Rs2TileObjectModel m = Microbot.getRs2TileObjectCache().query()
                .where(o -> {
                    int id = o.getId();
                    if (id < 27383 || id > 27426) return false;
                    WorldPoint wp = o.getWorldLocation();
                    if (wp == null) return false;
                    return Math.abs(wp.getRegionX() - plant.regionX) <= 1
                            && Math.abs(wp.getRegionY() - plant.regionY) <= 1;
                })
                .first();
        return m == null ? -1 : m.getId();
    }

    private static String debugClassify(int id) {
        if (id == -1) return "NONE";
        if (id == 27383) return "EMPTY";
        if (id == 27393 || id == 27404 || id == 27415) return "RIPE";
        switch (id) {
            case 27386: case 27389: case 27392: case 27394:
            case 27397: case 27400: case 27403: case 27405:
            case 27408: case 27411: case 27414: case 27416:
                return "DEAD";
            case 27384: case 27387: case 27390:
            case 27395: case 27398: case 27401:
            case 27406: case 27409: case 27412:
                return "DRY";
            case 27385: case 27388: case 27391:
            case 27396: case 27399: case 27402:
            case 27407: case 27410: case 27413:
                return "WET";
            default: return "?" + id;
        }
    }

        // Helper method to validate inventory items
        private void validateInventory() {
            if (!Rs2Inventory.hasItem(ItemID.DIBBER) || !Rs2Inventory.hasItem(ItemID.SPADE)) {
                Microbot.showMessage("You need a seed dibber and a spade in your inventory!");
                shutdown();
            }
            if (!Rs2Inventory.hasItemAmount("watering can", WATERING_CANS_AMOUNT) && !Rs2Inventory.hasItem(ItemID.ZEAH_WATERINGCAN)) {
                Microbot.showMessage("You need at least 8 watering can(8) or a Gricoller's can!");
                shutdown();
            }
        }

// Helper method to validate run energy and patches
        private void validateRunEnergy() {
            if (Microbot.getClient().getEnergy() < 4000 && hasAllEmptyPatches() && state != RECHARING_RUN_ENERGY) {
                state = RECHARING_RUN_ENERGY;
                Microbot.log("Recharging run energy...");
                Rs2Inventory.useRestoreEnergyItem();
            } else if (state == RECHARING_RUN_ENERGY && Microbot.getClient().getEnergy() >= 4000) {
                state = STARTING;
            }
        }

        private boolean validateSeedsAndPatches() {
            if (!Rs2Inventory.hasItem(CustomTitheFarmMaterial.getSeedForLevel().getName())) {
                return true;
            }
            return false;
        }
        
    private static void clickPatch(CustomTitheFarmPlant plant) {
        Rs2TileObjectModel model = plant.getGameObject();
        if (model == null) return;
        model.click();
    }

    private static void clickPatch(CustomTitheFarmPlant plant, String action) {
        Rs2TileObjectModel model = plant.getGameObject();
        if (model == null) return;
        model.click(action);
    }

    private static boolean interactWithObject(int id, String action) {
        Rs2TileObjectModel model = Microbot.getRs2TileObjectCache().query()
                .withId(id)
                .nearest();
        if (model == null) {
            Microbot.log("Object id " + id + " not in scene");
            return false;
        }
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc != null && playerLoc.distanceTo(model.getWorldLocation()) > 51) {
            Microbot.log("Object id " + id + " is " + playerLoc.distanceTo(model.getWorldLocation()) + " tiles away, walking...");
            Rs2Walker.walkTo(model.getWorldLocation());
            return false;
        }
        return action == null ? model.click() : model.click(action);
    }

    private static void DropFertiliser() {
        if (Rs2Inventory.hasItem("Gricoller's fertiliser")) {
            Rs2Inventory.drop("Gricoller's fertiliser");
        }
    }

    private void refillWaterCans(CustomTitheFarmingConfig config) {
        if (CustomTitheFarmMaterial.hasGricollersCan()) {
            checkGricollerCharges();
            sleepUntil(() -> gricollerCanCharges != -1);
            if (gricollerCanCharges < config.gricollerCanRefillThreshold()) {
                walkToBarrel();
                Rs2Inventory.interact(ItemID.ZEAH_WATERINGCAN, "Use");
                Rs2TileObjectModel barrel = Microbot.getRs2TileObjectCache().query()
                        .withName("Water barrel")
                        .nearestOnClientThread();
                if (barrel != null) barrel.click();
                sleepUntil(Rs2Player::isAnimating, 10000);
                return; // re-enter next pass; charges will read full and we finish then
            }
            finishRefill();
        } else if (CustomTitheFarmMaterial.hasWateringCanToBeFilled()) {
            walkToBarrel();
            Rs2Inventory.interact(CustomTitheFarmMaterial.getWateringCanToBeFilled(), "Use");
            Rs2TileObjectModel barrel = Microbot.getRs2TileObjectCache().query()
                    .withId(ObjectID.WATER_BARREL1)
                    .nearest();
            if (barrel != null) barrel.click();
            sleepUntil(() -> Rs2Inventory.hasItemAmount(ItemID.WATERING_CAN_8, WATERING_CANS_AMOUNT), 60000);
            finishRefill();
        } else {
            finishRefill();
        }
    }

    // Decide where to go after a refill: if the field is already planted we were refilling right
    // before the harvest, so go harvest; otherwise this was the startup refill, so start planting.
    private void finishRefill() {
        startupRefillPending = false;
        state = allPlanted ? HARVEST : PLANTING_SEEDS;
    }

    private void walkToBarrel() {
        Rs2TileObjectModel barrel = Microbot.getRs2TileObjectCache().query()
                .withId(ObjectID.WATER_BARREL1)
                .nearest();
        if (barrel == null) return;
        WorldPoint barrelLoc = barrel.getWorldLocation();
        WorldPoint barrelPlayerLoc = Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation());
        if (barrelLoc.distanceTo2D(barrelPlayerLoc) > DISTANCE_THRESHOLD_MINIMAP_WALK) {
            Rs2Walker.walkMiniMap(barrelLoc, 1);
            sleepUntil(Rs2Player::isMoving);
        }
        sleepUntil(() -> barrelLoc.distanceTo2D(Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation())) < DISTANCE_THRESHOLD_MINIMAP_WALK);
    }

    private void checkGricollerCharges() {
        gricollerCanCharges = -1;
        Rs2Inventory.interact(ItemID.ZEAH_WATERINGCAN, "check");
    }

    private void takeSeeds() {
        if (Rs2Inventory.hasItem(CustomTitheFarmMaterial.getSeedForLevel().getName())) {
            Rs2Inventory.drop(CustomTitheFarmMaterial.getSeedForLevel().getName());
            sleep(400, 600);
        }
        interactWithObject(ObjectID.TITHE_PLANT_SEED_TABLE, null);
        boolean result = Rs2Widget.sleepUntilHasWidget(CustomTitheFarmMaterial.getSeedForLevel().getName());
        if (!result) return;
        Rs2Keyboard.keyPress(CustomTitheFarmMaterial.getSeedForLevel().getOption());
        sleep(1000);
        Rs2Keyboard.typeString(String.valueOf(Rs2Random.betweenInclusive(1000, 10000)));
        sleep(600);
        Rs2Keyboard.enter();
        sleepUntil(() -> Rs2Inventory.hasItem(CustomTitheFarmMaterial.getSeedForLevel().getName()));
    }

    private void enter() {
        interactWithObject(FARM_DOOR, null);
        sleepUntil(this::isInMinigame);
    }

    private boolean depositSack() {
        if (Rs2Inventory.hasItem(CustomTitheFarmMaterial.getSeedForLevel().getFruitId())) {
            Microbot.log("Storing fruits into sack for experience...");
            Rs2TileObjectModel sack = Microbot.getRs2TileObjectCache().query()
                    .withId(ObjectID.TITHE_SACK_OF_FRUIT_EMPTY)
                    .nearest();
            if (sack == null) {
                Microbot.log("Sack not found in scene");
                return true;
            }
            WorldPoint sackLoc = sack.getWorldLocation();
            WorldPoint playerLoc = Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation());
            if (sackLoc.distanceTo2D(playerLoc) > DISTANCE_THRESHOLD_MINIMAP_WALK) {
                Rs2Walker.walkMiniMap(sackLoc, 1);
                sleepUntil(Rs2Player::isMoving);
                sleepUntil(() -> sackLoc.distanceTo2D(Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation())) < DISTANCE_THRESHOLD_MINIMAP_WALK);
            }
            sack.click();
            Rs2Player.waitForAnimation();
            return true;
        }
        return false;
    }

    private void leave() {
        interactWithObject(FARM_DOOR, null);
        sleepUntil(() -> !Rs2Inventory.hasItem(FERTILISER), 8000);
    }

    private boolean hasAllEmptyPatches() {
        return plants.stream().allMatch(CustomTitheFarmPlant::isEmptyPatch);
    }

    private boolean isInMinigame() {
        return Rs2Widget.getWidget(15794178) != null;
    }
}

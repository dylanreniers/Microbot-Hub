package net.runelite.client.plugins.custom.customherbrun;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Rs2Leprechaun;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingHandler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.LoginManager;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.api.MenuAction;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.timetracking.Tab;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.Microbot.log;

@Slf4j
public class CustomHerbrunScript extends Script {
    @Inject
    private ConfigManager configManager;
    @Inject
    private FarmingWorld farmingWorld;
    private FarmingHandler farmingHandler;
    private final CustomHerbrunPlugin plugin;
    private final CustomHerbrunConfig config;
    private HerbPatch currentPatch;
    @Inject
    ClientThread clientThread;
    private boolean initialized = false;

    private enum LocationPhase { HERB, FLOWER, ALLOTMENT, DONE }
    private LocationPhase currentPhase = LocationPhase.HERB;

    private static final Set<String> ALLOTMENT_FLOWER_REGIONS = Set.of(
            "Ardougne", "Catherby", "Civitas illa Fortis", "Falador", "Kourend", "Morytania"
    );

    @Inject
    public CustomHerbrunScript(CustomHerbrunPlugin plugin, CustomHerbrunConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    private final List<HerbPatch> herbPatches = new ArrayList<>();
    private final Map<String, List<FarmingPatch>> allotmentsByRegion = new HashMap<>();
    private final Map<String, FarmingPatch> flowerByRegion = new HashMap<>();
    private final Set<Integer> handledAllotmentIds = new HashSet<>();
    private int currentAllotmentId = -1;

    // --- Repeat timer state ---
    // When a run finishes and the repeat timer is enabled, we log out and stay out until
    // resumeAtMillis, then log back in and start a fresh run. This phase must be driven even
    // while logged out, so it is handled before the isLoggedIn() guard below.
    private boolean timerWaiting = false;
    private long resumeAtMillis = 0L;

    // --- Supercompost bin state ---
    private boolean compostBinHandled = false;
    private boolean compostBinDepositedFirst = false;
    private boolean compostBinAshAdded = false;
    private int compostBinAttempts = 0;

    public boolean run() {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;

            // Handle the between-runs waiting period first — this runs while logged out.
            if (timerWaiting) {
                handleTimerWait();
                return;
            }

            if (!Microbot.isLoggedIn()) return;

            // The bank PIN keypad (group 213) can appear at a bank OR at a tool leprechaun when
            // withdrawing compost / noting produce. The leprechaun code path never enters it, so
            // handle it here for every case — handleBankPin() is a no-op when the keypad isn't shown.
            if (Rs2Bank.isBankPinWidgetVisible()) {
                CustomHerbrunPlugin.status = "Entering bank PIN";
                Rs2Bank.handleBankPin();
                return;
            }

            // Debug: run ONLY the compost bin routine (no herb run), then stop. Lets the bin
            // collect/store/refill flow be tested in isolation without a full run first.
            if (config.compostBinOnly()) {
                if (!handleCompostBin()) return;
                CustomHerbrunPlugin.status = "Compost bin done";
                log("[Bin] Compost-bin-only run complete");
                if (!config.enableRepeatTimer()) {
                    Microbot.stopPlugin(plugin);
                }
                return;
            }

            if (!initialized) {
                initialized = true;
                CustomHerbrunPlugin.status = "Gearing up";
                populatePatches();

                if (!sleepUntil(() -> !herbPatches.isEmpty(), 1000)) {
                    if (herbPatches.isEmpty()) {
                        return;
                    }
                }

                if (!loadRunInventory()) {
                    return;
                }

                int allotmentRegions = allotmentsByRegion.size();
                int flowerCount = flowerByRegion.size();
                log("Will visit " + herbPatches.size() + " herb patches, " + allotmentRegions + " allotment locations, " + flowerCount + " flower patches");
            }

            if (currentPatch == null) {
                getNextPatch();
                currentPhase = LocationPhase.HERB;
            }
            if (currentPatch == null) {
                CustomHerbrunPlugin.status = "Finishing up";

                // Sustain supercompost via the Farming Guild Big Compost Bin (multi-tick; re-enter
                // until fully handled) before the final bank / logout.
                if (config.sustainSupercompost() && !compostBinHandled) {
                    if (!handleCompostBin()) return;
                    compostBinHandled = true;
                }

                if (!config.goToBank()) {
                    // Bank After Run disabled: the whole cycle is fully ended.
                    CustomHerbrunPlugin.status = "Finished";
                    if (!config.enableRepeatTimer()) {
                        Microbot.stopPlugin(plugin);
                    }
                    return;
                }

                // Bank After Run enabled: deposit everything and re-stock the chosen inventory setup,
                // so the character ends geared and ready for the next run.
                CustomHerbrunPlugin.status = "Banking & re-stocking setup";
                if (!loadRunInventory()) return;

                if (config.enableRepeatTimer()) {
                    // Start the between-runs wait: log out now, resume after the configured interval
                    // plus a random offset (0..range minutes) so we never log in on the exact minute.
                    long baseMs = config.runIntervalMinutes() * 60_000L;
                    long randomCapMs = Math.max(0, config.runIntervalRandomMinutes()) * 60_000L;
                    long randomMs = randomCapMs > 0 ? ThreadLocalRandom.current().nextLong(randomCapMs + 1) : 0L;
                    long waitMs = baseMs + randomMs;
                    resumeAtMillis = System.currentTimeMillis() + waitMs;
                    timerWaiting = true;
                    long waitMinutes = waitMs / 60_000L;
                    CustomHerbrunPlugin.status = "Run complete - logging out for ~" + waitMinutes + " min";
                    log("Herb run complete. Logging out for " + waitMinutes + " min "
                            + (waitMs % 60_000L) / 1000 + " s before the next run.");
                    Rs2Player.logout();
                    return;
                }

                CustomHerbrunPlugin.status = "Finished";
                if (!config.enableRepeatTimer()) {
                    Microbot.stopPlugin(plugin);
                }
                return;
            }

            if (!currentPatch.isEnabled()) {
                currentPatch = null;
                return;
            }

            if (!currentPatch.isInRange(40)) {
                CustomHerbrunPlugin.status = "Walking to " + currentPatch.getRegionName();
                Rs2Walker.walkTo(currentPatch.getLocation(), 20);
                return;
            }

            String region = currentPatch.getRegionName();

            switch (currentPhase) {
                case HERB:
                    CustomHerbrunPlugin.status = "Farming herbs at " + region;
                    if (handleHerbPatch()) {
                        currentPhase = LocationPhase.FLOWER;
                    }
                    break;
                case FLOWER:
                    if (!config.enableFlowers() || !flowerByRegion.containsKey(region)) {
                        log("Skipping flowers at " + region + " (enabled=" + config.enableFlowers() + " hasRegion=" + flowerByRegion.containsKey(region) + " keys=" + flowerByRegion.keySet() + ")");
                        currentPhase = LocationPhase.ALLOTMENT;
                    } else {
                        CustomHerbrunPlugin.status = "Farming flowers at " + region;
                        if (handleFlowerPatch(region)) {
                            currentPhase = LocationPhase.ALLOTMENT;
                        }
                    }
                    break;
                case ALLOTMENT:
                    if (!config.enableAllotments() || !allotmentsByRegion.containsKey(region)) {
                        log("Skipping allotments at " + region + " (enabled=" + config.enableAllotments() + " hasRegion=" + allotmentsByRegion.containsKey(region) + " keys=" + allotmentsByRegion.keySet() + ")");
                        currentPhase = LocationPhase.DONE;
                    } else {
                        CustomHerbrunPlugin.status = "Farming allotments at " + region;
                        if (handleAllotmentPatches(region)) {
                            currentPhase = LocationPhase.DONE;
                        }
                    }
                    break;
                case DONE:
                    currentPatch = null;
                    currentPhase = LocationPhase.HERB;
                    handledAllotmentIds.clear();
                    currentAllotmentId = -1;
                    break;
            }

        }, 0, 1000, TimeUnit.MILLISECONDS);

        return true;
    }

    /**
     * Drives the between-runs waiting period. While the interval hasn't elapsed we make sure the
     * character is logged out. Once it elapses we log back in, and on a confirmed login we clear the
     * waiting flag and reset state so the scheduler starts a fresh run (re-gearing and re-scanning
     * patches from scratch).
     */
    private void handleTimerWait() {
        long remainingMs = resumeAtMillis - System.currentTimeMillis();

        if (remainingMs > 0) {
            // Still waiting — ensure we're logged out and show the countdown.
            long totalSeconds = remainingMs / 1000;
            CustomHerbrunPlugin.status = String.format("Next run in %d:%02d",
                    totalSeconds / 60, totalSeconds % 60);
            if (Microbot.isLoggedIn()) {
                Rs2Player.logout();
            }
            return;
        }

        // Interval elapsed — log back in.
        if (!Microbot.isLoggedIn()) {
            CustomHerbrunPlugin.status = "Logging back in for next run";
            LoginManager.login();
            sleepUntil(Microbot::isLoggedIn, 15000);
            return;
        }

        // Logged in and ready — reset for a fresh run.
        log("Logged back in. Starting next herb run.");
        timerWaiting = false;
        resetForNextRun();
    }

    /** Clears per-run state so the scheduler re-initialises (re-gear + re-scan) on the next tick. */
    private void resetForNextRun() {
        initialized = false;
        currentPatch = null;
        currentPhase = LocationPhase.HERB;
        herbPatches.clear();
        allotmentsByRegion.clear();
        flowerByRegion.clear();
        handledAllotmentIds.clear();
        currentAllotmentId = -1;
        compostBinHandled = false;
        compostBinDepositedFirst = false;
        compostBinAshAdded = false;
        compostBinAttempts = 0;
    }

    private void populatePatches() {
        this.farmingHandler = new FarmingHandler(Microbot.getClient(), configManager);
        herbPatches.clear();
        allotmentsByRegion.clear();
        flowerByRegion.clear();

        clientThread.runOnClientThreadOptional(() -> {
            Map<String, HerbPatch> allHerbsByRegion = new HashMap<>();

            for (FarmingPatch patch : farmingWorld.getTabs().get(Tab.HERB)) {
                HerbPatch _patch = new HerbPatch(patch, config, farmingHandler);
                if (!_patch.isEnabled()) continue;
                allHerbsByRegion.put(_patch.getRegionName(), _patch);
                if (_patch.getPrediction() != CropState.GROWING) {
                    herbPatches.add(_patch);
                }
            }

            if (config.enableAllotments()) {
                for (FarmingPatch patch : farmingWorld.getTabs().get(Tab.ALLOTMENT)) {
                    String region = patch.getRegion().getName();
                    if (!ALLOTMENT_FLOWER_REGIONS.contains(region)) continue;
                    CropState prediction = farmingHandler.predictPatch(patch);
                    if (prediction != CropState.GROWING) {
                        allotmentsByRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(patch);
                    }
                }
            }

            if (config.enableFlowers()) {
                for (FarmingPatch patch : farmingWorld.getTabs().get(Tab.FLOWER)) {
                    String region = patch.getRegion().getName();
                    if (!ALLOTMENT_FLOWER_REGIONS.contains(region)) continue;
                    CropState prediction = farmingHandler.predictPatch(patch);
                    if (prediction != CropState.GROWING) {
                        flowerByRegion.put(region, patch);
                    }
                }
            }

            for (String region : allHerbsByRegion.keySet()) {
                boolean alreadyInList = herbPatches.stream().anyMatch(p -> p.getRegionName().equals(region));
                if (alreadyInList) continue;
                boolean hasAllotmentWork = allotmentsByRegion.containsKey(region);
                boolean hasFlowerWork = flowerByRegion.containsKey(region);
                if (hasAllotmentWork || hasFlowerWork) {
                    herbPatches.add(allHerbsByRegion.get(region));
                }
            }

            return true;
        });
    }

    private void getNextPatch() {
        if (currentPatch == null) {
            if (herbPatches.isEmpty()) {
                return;
            }

            currentPatch = herbPatches.stream()
                    .filter(patch -> patch.isEnabled() && Objects.equals(patch.getRegionName(), "Weiss"))
                    .findFirst()
                    .orElseGet(() -> herbPatches.stream()
                            .filter(HerbPatch::isEnabled)
                            .findFirst()
                            .orElse(null));

            if (currentPatch != null) {
                herbPatches.remove(currentPatch);
            }
        }
    }

    private static final int[] ALLOTMENT_PATCH_IDS = {
            ObjectID.FARMING_VEG_PATCH_1, ObjectID.FARMING_VEG_PATCH_2,
            ObjectID.FARMING_VEG_PATCH_3, ObjectID.FARMING_VEG_PATCH_4,
            ObjectID.FARMING_VEG_PATCH_5, ObjectID.FARMING_VEG_PATCH_6,
            ObjectID.FARMING_VEG_PATCH_7, ObjectID.FARMING_VEG_PATCH_8,
            ObjectID.FARMING_VEG_PATCH_9, ObjectID.FARMING_VEG_PATCH_10,
            ObjectID.FARMING_VEG_PATCH_11, ObjectID.FARMING_VEG_PATCH_12,
            ObjectID.FARMING_VEG_PATCH_13, ObjectID.FARMING_VEG_PATCH_14,
            ObjectID.FARMING_VEG_PATCH_15, ObjectID.FARMING_VEG_PATCH_16,
            ObjectID.FARMING_VEG_PATCH_17
    };

    private static final int[] HERB_PATCH_IDS = {
            ObjectID.MYARM_HERBPATCH,
            ObjectID.FARMING_HERB_PATCH_2,
            ObjectID.FARMING_HERB_PATCH_4,
            ObjectID.FARMING_HERB_PATCH_8,
            ObjectID.FARMING_HERB_PATCH_6,
            ObjectID.FARMING_HERB_PATCH_3,
            ObjectID.FARMING_HERB_PATCH_1,
            ObjectID.FARMING_HERB_PATCH_7,
            ObjectID.MY2ARM_HERBPATCH,
            ObjectID.FARMING_HERB_PATCH_5
    };

    // --- Herb patch handling (existing logic, refactored for shared compost/noting) ---

    private boolean handleHerbPatch() {
        if (!ensureInventorySpace()) return false;

        final var obj = Microbot.getRs2TileObjectCache().query().withIds(HERB_PATCH_IDS).nearest();
        if (obj == null) return true;
        String state = getHerbPatchState(obj);

        if (state.equals("Harvestable")) {
            obj.click("Pick");
            Rs2Player.waitForWalking();
            sleepUntil(() -> getHerbPatchState(obj).equals("Empty") || Rs2Inventory.isFull(), 20000);
            return false;
        }

        if (state.equals("Weeds")) {
            obj.click("Rake");
            Rs2Player.waitForWalking();
            sleepUntil(() -> !getHerbPatchState(obj).equals("Weeds"), 15000);
            state = getHerbPatchState(obj);
        }

        if (state.equals("Dead")) {
            obj.click("Clear");
            Rs2Player.waitForWalking();
            sleepUntil(() -> getHerbPatchState(obj).equals("Empty"), 10000);
            state = getHerbPatchState(obj);
        }

        if (state.equals("Empty")) {
            if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.dropAll("Weeds");
            HerbSeedType seedInInventory = getFirstHerbSeedInInventory();
            if (seedInInventory == null) {
                log("No herb seeds found in inventory, skipping patch");
                return true;
            }
            if (!applyCompost(obj, config.herbCompostType())) return false;
            Rs2Inventory.use(seedInInventory.getItemId());
            obj.click("Plant");
            Rs2Player.waitForWalking();
            sleepUntil(() -> getHerbPatchState(obj).equals("Growing"), 10000);
            return false;
        }

        return true;
    }

    private static String getHerbPatchState(TileObject rs2TileObject) {
        var game_obj = Rs2GameObject.convertToObjectComposition(rs2TileObject, true);
        var varbitValue = Microbot.getVarbitValue(game_obj.getVarbitId());

        if ((varbitValue >= 0 && varbitValue < 3) ||
                (varbitValue >= 60 && varbitValue <= 67) ||
                (varbitValue >= 173 && varbitValue <= 191) ||
                (varbitValue >= 204 && varbitValue <= 219) ||
                (varbitValue >= 221 && varbitValue <= 255)) {
            return "Weeds";
        }

        if ((varbitValue >= 4 && varbitValue <= 7) ||
                (varbitValue >= 11 && varbitValue <= 14) ||
                (varbitValue >= 18 && varbitValue <= 21) ||
                (varbitValue >= 25 && varbitValue <= 28) ||
                (varbitValue >= 32 && varbitValue <= 35) ||
                (varbitValue >= 39 && varbitValue <= 42) ||
                (varbitValue >= 46 && varbitValue <= 49) ||
                (varbitValue >= 53 && varbitValue <= 56) ||
                (varbitValue >= 68 && varbitValue <= 71) ||
                (varbitValue >= 75 && varbitValue <= 78) ||
                (varbitValue >= 82 && varbitValue <= 85) ||
                (varbitValue >= 89 && varbitValue <= 92) ||
                (varbitValue >= 96 && varbitValue <= 99) ||
                (varbitValue >= 103 && varbitValue <= 106) ||
                (varbitValue >= 192 && varbitValue <= 195)) {
            return "Growing";
        }

        if ((varbitValue >= 8 && varbitValue <= 10) ||
                (varbitValue >= 15 && varbitValue <= 17) ||
                (varbitValue >= 22 && varbitValue <= 24) ||
                (varbitValue >= 29 && varbitValue <= 31) ||
                (varbitValue >= 36 && varbitValue <= 38) ||
                (varbitValue >= 43 && varbitValue <= 45) ||
                (varbitValue >= 50 && varbitValue <= 52) ||
                (varbitValue >= 57 && varbitValue <= 59) ||
                (varbitValue >= 72 && varbitValue <= 74) ||
                (varbitValue >= 79 && varbitValue <= 81) ||
                (varbitValue >= 86 && varbitValue <= 88) ||
                (varbitValue >= 93 && varbitValue <= 95) ||
                (varbitValue >= 100 && varbitValue <= 102) ||
                (varbitValue >= 107 && varbitValue <= 109) ||
                (varbitValue >= 196 && varbitValue <= 197)) {
            return "Harvestable";
        }

        if ((varbitValue >= 128 && varbitValue <= 169) ||
                (varbitValue >= 198 && varbitValue <= 200)) {
            return "Diseased";
        }

        if ((varbitValue >= 170 && varbitValue <= 172) ||
                (varbitValue >= 201 && varbitValue <= 203)) {
            return "Dead";
        }

        return "Empty";
    }

    // --- Flower patch handling ---

    private static final int[] FLOWER_PATCH_IDS = {
            ObjectID.FARMING_FLOWER_PATCH_1, ObjectID.FARMING_FLOWER_PATCH_2,
            ObjectID.FARMING_FLOWER_PATCH_3, ObjectID.FARMING_FLOWER_PATCH_4,
            ObjectID.FARMING_FLOWER_PATCH_5, ObjectID.FARMING_FLOWER_PATCH_6,
            ObjectID.FARMING_FLOWER_PATCH_7, ObjectID.FARMING_FLOWER_PATCH_8,
            ObjectID.FARMING_FLOWER_PATCH_9
    };

    private boolean handleFlowerPatch(String region) {
        if (!ensureInventorySpace()) return false;

        final var obj = Microbot.getRs2TileObjectCache().query().withIds(FLOWER_PATCH_IDS).nearest();
        if (obj == null) {
            log("[Flower] No flower patch object found at " + region);
            return true;
        }

        String state = getPatchState(obj);
        log("[Flower] Patch at " + obj.getWorldLocation() + " state=" + state);

        switch (state) {
            case "Harvestable":
                // A flower patch harvests in one action. Free as much space as possible first so a
                // bulk yield (e.g. limpwurt roots) fits rather than overflowing onto the ground.
                noteProduceViaLeprechaun();
                obj.click("Pick");
                Rs2Player.waitForWalking();
                sleepUntil(() -> getPatchState(obj).equals("Empty") || Rs2Inventory.isFull(), 10000);
                recoverOwnLimpwurtDrops();
                return false;
            case "Weeds":
                obj.click("Rake");
                Rs2Player.waitForWalking();
                sleepUntil(() -> !getPatchState(obj).equals("Weeds"), 15000);
                return false;
            case "Dead":
                obj.click("Clear");
                Rs2Player.waitForWalking();
                sleepUntil(() -> getPatchState(obj).equals("Empty"), 10000);
                return false;
            case "Empty":
                if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.dropAll("Weeds");
                FlowerSeedType flowerSeed = config.flowerSeedType();
                if (!Rs2Inventory.hasItem(flowerSeed.getItemId())) {
                    log("[Flower] No " + flowerSeed.getSeedName() + " in inventory, skipping");
                    return true;
                }
                if (!applyCompost(obj, config.flowerCompostType())) return false;
                Rs2Inventory.use(flowerSeed.getItemId());
                obj.click("Plant");
                Rs2Player.waitForWalking();
                // Only advance once Growing is confirmed; otherwise retry next tick (weeds may have
                // regrown between clearing and planting, or the click missed).
                if (sleepUntil(() -> getPatchState(obj).equals("Growing"), 10000)) {
                    log("[Flower] Planted at " + obj.getWorldLocation());
                    return true;
                }
                log("[Flower] Plant not confirmed (state=" + getPatchState(obj) + "), retrying");
                return false;
            default:
                log("[Flower] Patch is " + state + ", skipping");
                return true;
        }
    }

    // --- Allotment patch handling ---

    /**
     * A multi-tile allotment patch shares one ObjectID across ~12 tiles. Interior tiles cannot be
     * interacted with because the player has no adjacent tile to stand on — all four neighbours are
     * other (blocked) patch tiles. An edge tile is interactable: at least one orthogonal neighbour is
     * open ground (passes the BLOCK_MOVEMENT_FULL collision check). isReachable() can't distinguish
     * these (it returns true for any same-worldview object), so we test for a standable neighbour here.
     */
    private static boolean hasStandableNeighbor(WorldPoint tile) {
        if (tile == null) return false;
        return Rs2Tile.isWalkable(tile.dx(1))
                || Rs2Tile.isWalkable(tile.dx(-1))
                || Rs2Tile.isWalkable(tile.dy(1))
                || Rs2Tile.isWalkable(tile.dy(-1));
    }

    /** Squared Euclidean distance — finer than WorldPoint.distanceTo (Chebyshev), so ties between a
     *  diagonal and an orthogonal tile resolve toward the orthogonal (genuinely nearest) one. */
    private static int sqDist(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        return dx * dx + dy * dy;
    }

    private boolean handleAllotmentPatches(String region) {
        if (!ensureInventorySpace()) return false;

        var allObjects = Microbot.getRs2TileObjectCache().query().withIds(ALLOTMENT_PATCH_IDS).toList();
        if (allObjects == null || allObjects.isEmpty()) {
            log("[Allotment] No allotment objects found in scene");
            currentAllotmentId = -1;
            return true;
        }

        // distanceTo() is Chebyshev (max(|dx|,|dy|)), so many tiles tie and the tie is broken by list
        // order — picking a diagonal tile over the orthogonally-adjacent one. Compare by squared
        // Euclidean distance instead so we land on the genuinely-nearest tile at decision time.
        final WorldPoint playerLoc = Rs2Player.getWorldLocation();
        Rs2TileObjectModel pinned = null;
        if (currentAllotmentId >= 0) {
            pinned = allObjects.stream()
                    .filter(o -> o.getId() == currentAllotmentId && hasStandableNeighbor(o.getWorldLocation()))
                    .min(Comparator.comparingInt(o -> sqDist(o.getWorldLocation(), playerLoc)))
                    .orElse(null);
            if (pinned == null || handledAllotmentIds.contains(currentAllotmentId)) {
                log("[Allotment] Finished patch id=" + currentAllotmentId + ", looking for next");
                currentAllotmentId = -1;
                pinned = null;
            }
        }
        if (currentAllotmentId < 0) {
            pinned = allObjects.stream()
                    .filter(o -> !handledAllotmentIds.contains(o.getId()) && hasStandableNeighbor(o.getWorldLocation()))
                    .min(Comparator.comparingInt(o -> sqDist(o.getWorldLocation(), playerLoc)))
                    .orElse(null);
            if (pinned == null) {
                log("[Allotment] All patches handled at " + region);
                return true;
            }
            currentAllotmentId = pinned.getId();
            log("[Allotment] Starting patch id=" + currentAllotmentId + " near " + pinned.getWorldLocation());
        }
        final var obj = pinned;

        String state = getPatchState(obj);
        log("[Allotment] Patch id=" + currentAllotmentId + " state=" + state);

        switch (state) {
            case "Harvestable":
                obj.click("Pick");
                Rs2Player.waitForWalking();
                sleepUntil(() -> getPatchState(obj).equals("Empty") || Rs2Inventory.isFull(), 20000);
                return false;
            case "Weeds":
                obj.click("Rake");
                Rs2Player.waitForWalking();
                sleepUntil(() -> !getPatchState(obj).equals("Weeds"), 15000);
                return false;
            case "Dead":
                obj.click("Clear");
                Rs2Player.waitForWalking();
                sleepUntil(() -> getPatchState(obj).equals("Empty"), 10000);
                return false;
            case "Empty":
                if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.dropAll("Weeds");
                AllotmentSeedType allotmentSeed = getFirstAllotmentSeedInInventory();
                if (allotmentSeed == null || Rs2Inventory.itemQuantity(allotmentSeed.getItemId()) < 3) {
                    log("[Allotment] Not enough seeds (need 3), skipping id=" + currentAllotmentId);
                    handledAllotmentIds.add(currentAllotmentId);
                    currentAllotmentId = -1;
                    return false;
                }
                if (!applyCompost(obj, config.allotmentCompostType())) return false;
                Rs2Inventory.use(allotmentSeed.getItemId());
                obj.click("Plant");
                Rs2Player.waitForWalking();
                // Only mark done once the patch is confirmed Growing. If the plant didn't take
                // (e.g. weeds regrew between clearing and planting, or the click missed), leave the
                // patch pinned so the next tick re-rakes/re-plants instead of silently giving up.
                if (sleepUntil(() -> getPatchState(obj).equals("Growing"), 10000)) {
                    log("[Allotment] Planted id=" + currentAllotmentId);
                    handledAllotmentIds.add(currentAllotmentId);
                    currentAllotmentId = -1;
                } else {
                    log("[Allotment] Plant not confirmed (state=" + getPatchState(obj) + "), retrying id=" + currentAllotmentId);
                }
                return false;
            default:
                log("[Allotment] Patch id=" + currentAllotmentId + " is " + state + ", marking done");
                handledAllotmentIds.add(currentAllotmentId);
                currentAllotmentId = -1;
                return false;
        }
    }

    // --- Shared patch state detection via game object actions ---

    private static String getPatchState(TileObject tileObj) {
        if (Rs2GameObject.hasAction(tileObj, "Rake")) return "Weeds";
        if (Rs2GameObject.hasAction(tileObj, "Pick")) return "Harvestable";
        if (Rs2GameObject.hasAction(tileObj, "Harvest")) return "Harvestable";
        if (Rs2GameObject.hasAction(tileObj, "Clear")) return "Dead";

        var comp = Rs2GameObject.convertToObjectComposition(tileObj, true);
        int varbit = Microbot.getVarbitValue(comp.getVarbitId());
        if (varbit <= 5) return "Empty";

        return "Growing";
    }

    // --- Shared compost application ---

    private boolean applyCompost(Rs2TileObjectModel obj, CompostType compost) {
        if (compost == CompostType.NONE) return true;

        if (compost.isBottomless()) {
            if (!Rs2Inventory.hasItem(compost.getItemId())) {
                log("Bottomless compost bucket not found in inventory");
                return false;
            }
        } else {
            if (!Rs2Inventory.hasItem(compost.getItemId())) {
                if (!Rs2Leprechaun.withdrawCompost(compost.getItemId())) {
                    log("Failed to withdraw " + compost.getCompostName() + " from leprechaun");
                    return false;
                }
                return false;
            }
        }

        int xpBefore = Microbot.getClient().getSkillExperience(Skill.FARMING);
        Rs2Inventory.use(compost.getItemId());
        obj.click("Compost");
        Rs2Player.waitForWalking();
        boolean applied = sleepUntil(
                () -> Microbot.getClient().getSkillExperience(Skill.FARMING) > xpBefore, 5000);

        if (!applied) {
            log("Patch already composted, skipping");
        } else if (config.dropEmptyBuckets() && !compost.isBottomless()) {
            Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
        }
        return true;
    }

    // --- Shared inventory space management ---

    private boolean ensureInventorySpace() {
        if (!Rs2Inventory.isFull()) return true;
        return noteProduceViaLeprechaun();
    }

    private boolean noteProduceViaLeprechaun() {
        Rs2NpcModel leprechaun = Microbot.getRs2NpcCache().query().withName("Tool leprechaun").nearestOnClientThread();
        if (leprechaun == null) return false;

        // Note EVERY notable produce type we hold more than one of, not just the patch we're currently
        // harvesting. Otherwise produce from an earlier patch (e.g. grimy herbs) keeps occupying slots
        // and space never actually gets freed. Using an item on the leprechaun notes its whole stack.
        Set<Integer> seen = new HashSet<>();
        List<Rs2ItemModel> toNote = new ArrayList<>();
        for (Rs2ItemModel item : Rs2Inventory.all()) {
            if (item == null || item.isNoted() || item.getName() == null) continue;
            if (!isNotableProduce(item.getName())) continue;
            if (Rs2Inventory.count(item.getId()) <= 1) continue;
            if (seen.add(item.getId())) toNote.add(item);
        }

        boolean notedAny = false;
        for (Rs2ItemModel item : toNote) {
            Rs2Inventory.use(item);
            leprechaun.click("Talk-to");
            Rs2Inventory.waitForInventoryChanges(10000);
            notedAny = true;
        }
        if (notedAny) return true;

        if (Rs2Inventory.hasItem("Weeds")) {
            Rs2Inventory.dropAll("Weeds");
            return true;
        }
        if (Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY)) {
            Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
            return true;
        }
        return false;
    }

    /** A limpwurt patch harvests in bulk; if the inventory fills mid-harvest the surplus roots drop
     *  to the ground. Reclaim ONLY our own dropped roots (never another player's), noting between
     *  pickups to make room. Bounded loop so a contested/uncollectable drop can't spin forever. */
    private void recoverOwnLimpwurtDrops() {
        for (int i = 0; i < 10; i++) {
            Rs2TileItemModel root = Microbot.getRs2TileItemCache().query()
                    .withId(ItemID.LIMPWURT_ROOT)
                    .where(Rs2TileItemModel::isOwned)
                    .within(3)
                    .nearest();
            if (root == null) return;
            if (Rs2Inventory.isFull() && !noteProduceViaLeprechaun()) return; // can't free space, give up
            if (!root.pickup()) return;
            Rs2Inventory.waitForInventoryChanges(3000);
        }
    }

    private static final String[] ALLOTMENT_PRODUCE = {
            "Potato", "Onion", "Cabbage", "Tomato", "Sweetcorn", "Strawberry", "Watermelon", "Snape grass"
    };
    private static final String[] FLOWER_PRODUCE = {
            "Marigold", "Rosemary", "Nasturtium", "Woad leaf", "Limpwurt root", "White lily"
    };

    /** A farming product worth noting for space: any grimy herb, or allotment/flower produce. Produce
     *  names are matched exactly so seeds ("Potato seed", "Marigold seed") are never caught. */
    private static boolean isNotableProduce(String name) {
        if (name.startsWith("Grimy")) return true;
        for (String p : ALLOTMENT_PRODUCE) if (name.equals(p)) return true;
        for (String p : FLOWER_PRODUCE) if (name.equals(p)) return true;
        return false;
    }

    // --- Seed helpers ---

    private HerbSeedType getFirstHerbSeedInInventory() {
        for (HerbSeedType herbType : HerbSeedType.values()) {
            if (herbType != HerbSeedType.BEST && Rs2Inventory.hasItem(herbType.getItemId())) {
                return herbType;
            }
        }
        return null;
    }

    private AllotmentSeedType getFirstAllotmentSeedInInventory() {
        if (config.allotmentSeedType() != AllotmentSeedType.BEST) {
            AllotmentSeedType selected = config.allotmentSeedType();
            if (Rs2Inventory.hasItem(selected.getItemId())) return selected;
            return null;
        }
        for (AllotmentSeedType seed : AllotmentSeedType.getPlantableSeeds(
                Microbot.getClient().getRealSkillLevel(Skill.FARMING))) {
            if (Rs2Inventory.hasItem(seed.getItemId())) return seed;
        }
        return null;
    }

    // --- Banking ---

    /** Deposit and re-stock the run loadout: load the configured inventory setup, or auto-withdraw
     *  tools/seeds/runes. Used both when gearing up at the start and when re-stocking at the end.
     *  For inventory-setup mode this is a no-op when the inventory already matches. */
    private boolean loadRunInventory() {
        if (config.useInventorySetup()) {
            var inventorySetup = new Rs2InventorySetup(config.inventorySetup(), mainScheduledFuture);
            if (!inventorySetup.doesInventoryMatch() || !inventorySetup.doesEquipmentMatch()) {
                Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);
                if (!inventorySetup.loadEquipment() || !inventorySetup.loadInventory()) {
                    return false;
                }
                Rs2Bank.closeBank();
            }
            return true;
        }
        return setupAutoInventory();
    }

    private boolean setupAutoInventory() {
        Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);

        if (!Rs2Bank.openBank()) {
            log("Failed to open bank");
            return false;
        }
        if (!sleepUntil(Rs2Bank::isOpen, 10000)) {
            log("Timeout waiting for bank to open after 10 seconds");
            return false;
        }

        Rs2Bank.depositAll();
        Rs2Inventory.waitForInventoryChanges(5000);

        int herbPatchCount = (int) herbPatches.stream().filter(HerbPatch::isEnabled).count();
        int allotmentLocationCount = countEnabledAllotmentFlowerLocations();

        boolean toolsOk = true;
        toolsOk &= Rs2Bank.withdrawX(ItemID.RAKE, 1);
        toolsOk &= Rs2Bank.withdrawX(ItemID.SPADE, 1);
        toolsOk &= Rs2Bank.withdrawX(ItemID.DIBBER, 1);
        if (!toolsOk) {
            log("Missing farming tools in bank (rake/spade/dibber)");
            return false;
        }

        if (Rs2Bank.hasItem(ItemID.FAIRY_ENCHANTED_SECATEURS)) {
            Rs2Bank.withdrawX(ItemID.FAIRY_ENCHANTED_SECATEURS, 1);
        }

        boolean missingRunes = false;
        missingRunes |= !Rs2Bank.withdrawX(ItemID.LAWRUNE, 20);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.AIRRUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.EARTHRUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.FIRERUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.WATERRUNE, 50);

        if (missingRunes) {
            log("Missing teleportation runes - cannot complete herb run");
            return false;
        }

        if (config.enableMorytania() && Rs2Bank.hasItem(ItemID.ECTOPHIAL)) {
            Rs2Bank.withdrawX(ItemID.ECTOPHIAL, 1);
        }

        // Withdraw herb seeds
        HerbSeedType seedType = config.herbSeedType();
        if (seedType == HerbSeedType.BEST) {
            if (!withdrawBestAvailableSeeds(herbPatchCount)) {
                log("Failed to withdraw best available herb seeds for " + herbPatchCount + " patches");
                return false;
            }
        } else {
            if (!withdrawSpecificSeeds(seedType.getItemId(), seedType.getSeedName(), herbPatchCount,
                    seedType.getLevelRequired(), config.allowPartialRuns())) {
                return false;
            }
        }

        // Withdraw allotment seeds
        if (config.enableAllotments() && allotmentLocationCount > 0) {
            int allotmentSeedsNeeded = 3 * 2 * allotmentLocationCount;
            AllotmentSeedType allotmentSeed = config.allotmentSeedType();
            if (allotmentSeed == AllotmentSeedType.BEST) {
                if (!withdrawBestAvailableAllotmentSeeds(allotmentSeedsNeeded)) {
                    log("Failed to withdraw allotment seeds");
                    if (!config.allowPartialRuns()) return false;
                }
            } else {
                if (!withdrawSpecificSeeds(allotmentSeed.getItemId(), allotmentSeed.getSeedName(),
                        allotmentSeedsNeeded, allotmentSeed.getLevelRequired(), config.allowPartialRuns())) {
                    if (!config.allowPartialRuns()) return false;
                }
            }
        }

        // Withdraw flower seeds
        if (config.enableFlowers() && allotmentLocationCount > 0) {
            FlowerSeedType flowerSeed = config.flowerSeedType();
            int flowerSeedsNeeded = allotmentLocationCount;
            if (!withdrawSpecificSeeds(flowerSeed.getItemId(), flowerSeed.getSeedName(),
                    flowerSeedsNeeded, flowerSeed.getLevelRequired(), config.allowPartialRuns())) {
                if (!config.allowPartialRuns()) return false;
            }
        }

        // Withdraw compost (bottomless only — non-bottomless comes from leprechaun). A single
        // bottomless bucket works on every patch type, so withdraw one if any active category uses it.
        boolean needsBottomless = config.herbCompostType().isBottomless()
                || (config.enableFlowers() && config.flowerCompostType().isBottomless())
                || (config.enableAllotments() && config.allotmentCompostType().isBottomless());
        if (needsBottomless) {
            if (!Rs2Bank.withdrawX(CompostType.BOTTOMLESS.getItemId(), 1)) {
                log("Failed to withdraw bottomless compost bucket");
                return false;
            }
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);

        log("Inventory setup complete - starting farm run");
        return true;
    }

    private boolean withdrawSpecificSeeds(int itemId, String seedName, int count, int levelRequired, boolean allowPartial) {
        int farmingLevel = Microbot.getClient().getRealSkillLevel(Skill.FARMING);
        if (farmingLevel < levelRequired) {
            log("Cannot plant " + seedName + " - requires Farming level " + levelRequired + " (you have " + farmingLevel + ")");
            return false;
        }

        if (!Rs2Bank.withdrawX(itemId, count)) {
            if (!allowPartial) {
                log("Failed to withdraw " + count + " " + seedName);
                return false;
            }
            int available = Rs2Bank.count(itemId);
            if (available > 0) {
                int toWithdraw = Math.min(available, count);
                Rs2Bank.withdrawX(itemId, toWithdraw);
                log("Partial run: withdrew " + toWithdraw + " " + seedName + " instead of " + count);
            } else {
                log("No " + seedName + " available in bank");
                return false;
            }
        }
        Rs2Inventory.waitForInventoryChanges(2000);
        return true;
    }

    private boolean withdrawBestAvailableSeeds(int patchCount) {
        int farmingLevel = Microbot.getClient().getRealSkillLevel(Skill.FARMING);
        List<HerbSeedType> plantableHerbs = HerbSeedType.getPlantableHerbs(farmingLevel);

        if (plantableHerbs.isEmpty()) {
            log("No herbs can be planted at farming level " + farmingLevel);
            return false;
        }

        int seedsWithdrawn = 0;

        for (HerbSeedType herb : plantableHerbs) {
            if (seedsWithdrawn >= patchCount) break;
            int availableSeeds = Rs2Bank.count(herb.getItemId());
            if (availableSeeds > 0) {
                int toWithdraw = Math.min(availableSeeds, patchCount - seedsWithdrawn);
                if (Rs2Bank.withdrawX(herb.getItemId(), toWithdraw)) {
                    seedsWithdrawn += toWithdraw;
                    log("Withdrew " + toWithdraw + " " + herb.getSeedName());
                }
            }
        }

        if (seedsWithdrawn < patchCount && !config.allowPartialRuns()) {
            return false;
        }
        return seedsWithdrawn > 0;
    }

    private boolean withdrawBestAvailableAllotmentSeeds(int totalSeedsNeeded) {
        int farmingLevel = Microbot.getClient().getRealSkillLevel(Skill.FARMING);
        List<AllotmentSeedType> plantableSeeds = AllotmentSeedType.getPlantableSeeds(farmingLevel);

        if (plantableSeeds.isEmpty()) {
            log("No allotment seeds can be planted at farming level " + farmingLevel);
            return false;
        }

        int seedsWithdrawn = 0;

        for (AllotmentSeedType seed : plantableSeeds) {
            if (seedsWithdrawn >= totalSeedsNeeded) break;
            int available = Rs2Bank.count(seed.getItemId());
            if (available > 0) {
                int toWithdraw = Math.min(available, totalSeedsNeeded - seedsWithdrawn);
                if (Rs2Bank.withdrawX(seed.getItemId(), toWithdraw)) {
                    seedsWithdrawn += toWithdraw;
                    log("Withdrew " + toWithdraw + " " + seed.getSeedName());
                }
            }
        }

        if (seedsWithdrawn < totalSeedsNeeded && !config.allowPartialRuns()) {
            return false;
        }
        return seedsWithdrawn > 0;
    }

    private int countEnabledAllotmentFlowerLocations() {
        int count = 0;
        if (config.enableArdougne()) count++;
        if (config.enableCatherby()) count++;
        if (config.enableVarlamore()) count++;
        if (config.enableFalador()) count++;
        if (config.enableHosidius()) count++;
        if (config.enableMorytania()) count++;
        return count;
    }

    // ------------------------------------------------------------------
    // Supercompost sustain: Farming Guild "Big Compost Bin"
    // ------------------------------------------------------------------
    // Object base id is 34631, but its live state is read from the IMPOSTOR (morph) id, which the
    // client updates per state (verified live):
    //   33762            = empty (ready to fill)
    //   33825 .. 33854   = filling (1..30 watermelons added, lid open)
    //   33855            = closed & rotting (NOT ready)
    //   33856            = rotted & ready, lid closed (needs "Open")
    //   33857 .. 33886   = lid open with 1..30 supercompost remaining ("Take" with buckets)
    // The tool leprechaun exchange has two interfaces (verified live):
    //   group 125 = the leprechaun's store, items have Remove-All/1/5/X (WITHDRAW)
    //   group 126 = your carried items, items have Store-All/1/5/X (DEPOSIT)
    // Empty buckets are withdrawn from 125,16; supercompost is deposited via 126,11 (Store-All).
    // Everything sits within a few tiles at the Farming Guild (bin, leprechaun and bank adjacent).
    private static final int BIN_BASE_ID = 34631;
    private static final int BIN_EMPTY = 33762;
    private static final int BIN_FILL_MIN = 33825;
    private static final int BIN_FILL_MAX = 33854;
    private static final int BIN_ROTTING = 33855;
    private static final int BIN_READY_CLOSED = 33856;
    private static final int BIN_COLLECT_MIN = 33857;
    private static final int BIN_COLLECT_MAX = 33886;
    private static final WorldPoint COMPOST_BIN_LOCATION = new WorldPoint(1272, 3729, 0);
    private static final int BIN_CAPACITY = 30;          // big bin holds 30 items
    private static final int MAX_FILL_PER_TRIP = 28;     // watermelons that fit in one inventory
    private static final int LEP_WITHDRAW_GROUP = 125;
    private static final int LEP_WITHDRAW_BUCKET_CHILD = 16;
    private static final int LEP_STORE_GROUP = 126;
    private static final int LEP_STORE_BUCKET_CHILD = 9;
    private static final int LEP_STORE_SUPERCOMPOST_CHILD = 11;
    private static final int LEP_STORE_ULTRACOMPOST_CHILD = 12;

    /** Resolve the bin's live morph id (its state). Returns -1 if the bin can't be found. */
    private int compostBinMorphId(Rs2TileObjectModel bin) {
        var comp = Rs2GameObject.convertToObjectComposition(bin, false);
        return comp == null ? -1 : comp.getId();
    }

    private Rs2TileObjectModel findCompostBin() {
        return Microbot.getRs2TileObjectCache().query().withId(BIN_BASE_ID).nearest();
    }

    /**
     * End-of-run routine: collect any ready supercompost (storing it in the leprechaun), then
     * refill the bin with watermelons and close it so it rots before the next run. Multi-tick:
     * returns {@code false} to be re-entered next tick, {@code true} when fully handled.
     */
    private boolean handleCompostBin() {
        if (compostBinAttempts++ > 120) {
            log("[Bin] Giving up after too many attempts");
            return true;
        }

        // Deposit the whole herb-run inventory once up front, so we have the maximum free slots for
        // withdrawing empty buckets and watermelons — more efficient than clearing space piecemeal.
        if (!compostBinDepositedFirst) {
            CustomHerbrunPlugin.status = "Depositing before compost bin";
            if (!Rs2Bank.isOpen()) {
                Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 6);
                if (!Rs2Bank.openBank()) return false;
                return false;
            }
            Rs2Bank.depositAll();
            Rs2Inventory.waitForInventoryChanges(3000);
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
            compostBinDepositedFirst = true;
            return false;
        }

        // Always offload collected compost (super or ultra) into the leprechaun store before anything
        // else, so it can never be carried into the final bank step (which would bank it instead).
        if (Rs2Inventory.hasItem(ItemID.BUCKET_SUPERCOMPOST) || Rs2Inventory.hasItem(ItemID.BUCKET_ULTRACOMPOST)) {
            depositCollectedCompost();
            depositEmptyBucketsToLeprechaun();
            return false;
        }

        // The bin, leprechaun and bank are all a few tiles apart at the Farming Guild, so we never
        // walk between them — clicking auto-approaches. Only walk when the bin isn't even in the
        // scene yet (e.g. the run ended far away, or we just stepped away to the bank).
        Rs2TileObjectModel bin = findCompostBin();
        if (bin == null) {
            CustomHerbrunPlugin.status = "Walking to compost bin";
            Rs2Walker.walkTo(COMPOST_BIN_LOCATION, 6);
            return false;
        }

        int id = compostBinMorphId(bin);
        boolean hasTake = Rs2GameObject.hasAction(bin, "Take");
        log("[Bin] state morphId=" + id + " take=" + hasTake
                + " open=" + Rs2GameObject.hasAction(bin, "Open")
                + " close=" + Rs2GameObject.hasAction(bin, "Close"));

        // Collect whenever the bin offers "Take" (works for both super- and ultra-compost, whose
        // collecting morph ids may differ) or its morph id is in the known supercompost range.
        if (hasTake || (id >= BIN_COLLECT_MIN && id <= BIN_COLLECT_MAX)) {
            // Phase 2: bin is full — add volcanic ash (while still open) if making ultracompost.
            if (config.addVolcanicAsh() && !compostBinAshAdded) {
                if (Rs2Inventory.hasItem(ItemID.FOSSIL_VOLCANIC_ASH)) {
                    if (findCompostBin() == null) { Rs2Walker.walkTo(COMPOST_BIN_LOCATION, 6); return false; }
                    if (ensureExchangeClosed()) return false;
                    CustomHerbrunPlugin.status = "Adding volcanic ash";
                    Rs2Inventory.use(ItemID.FOSSIL_VOLCANIC_ASH);
                    findCompostBin().click();
                    if (sleepUntil(() -> !Rs2Inventory.hasItem(ItemID.FOSSIL_VOLCANIC_ASH), 8000)) {
                        compostBinAshAdded = true;
                    }
                    return false;
                }
                CustomHerbrunPlugin.status = "Banking for volcanic ash";
                if (!Rs2Bank.isOpen()) {
                    Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 6);
                    if (!Rs2Bank.openBank()) return false;
                    return false;
                }
                if (!Rs2Bank.withdrawX(ItemID.FOSSIL_VOLCANIC_ASH, config.volcanicAshAmount())) {
                    log("[Bin] Not enough volcanic ash in bank - cannot make ultracompost");
                    Rs2Bank.closeBank();
                    return true;
                }
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
                return false;
            }
            return collectCompost(bin);
        }
        if (id == BIN_READY_CLOSED) {
            if (ensureExchangeClosed()) return false;
            CustomHerbrunPlugin.status = "Opening compost bin";
            bin.click("Open");
            Rs2Player.waitForWalking();
            sleepUntil(() -> compostBinMorphId(findCompostBin()) >= BIN_COLLECT_MIN, 5000);
            return false;
        }
        if (id == BIN_ROTTING) {
            log("[Bin] Still rotting - leaving it to finish for next run");
            return true;
        }
        if (id == BIN_EMPTY || (id >= BIN_FILL_MIN && id <= BIN_FILL_MAX)) {
            return fillAndCloseCompostBin(bin);
        }

        log("[Bin] Unexpected morphId=" + id + ", skipping");
        return true;
    }

    /** Withdraw empty buckets from the leprechaun, then use the bin's "Take" option (which auto-fills
     *  every empty bucket at once). (Held compost is offloaded at the top of handleCompostBin.)
     *  Re-entered until we run out of empty buckets or the bin empties. */
    private boolean collectCompost(Rs2TileObjectModel bin) {
        if (!Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY)) {
            if (!withdrawEmptyBucketsFromLeprechaun()) {
                log("[Bin] No empty buckets available at leprechaun - stopping collection");
                return true;
            }
            return false;
        }
        // The exchange overlay must be closed before we can interact with the world object.
        if (ensureExchangeClosed()) return false;

        CustomHerbrunPlugin.status = "Collecting compost";
        bin.click("Take");
        // One "Take" fills the empty buckets progressively, so wait for the whole operation to
        // finish: either every empty bucket has been filled, or the bin has been fully emptied
        // (it held fewer loads than the buckets we carried). Generous timeout for a full inventory.
        sleepUntil(() -> !Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY) || isCompostBinEmpty(), 60000);
        return false;
    }

    /** True when the bin holds no more collectable compost (no "Take" action and its morph id is
     *  out of the collecting range). */
    private boolean isCompostBinEmpty() {
        Rs2TileObjectModel bin = findCompostBin();
        if (bin == null) return true;
        int id = compostBinMorphId(bin);
        return !Rs2GameObject.hasAction(bin, "Take") && !(id >= BIN_COLLECT_MIN && id <= BIN_COLLECT_MAX);
    }

    /** Number of items currently in the bin from its morph id: 0 when empty, 1..30 while filling,
     *  or -1 if the id isn't a fill state (e.g. rotting/ready/ash-modified). */
    private int binFillCount(int id) {
        if (id == BIN_EMPTY) return 0;
        if (id >= BIN_FILL_MIN && id <= BIN_FILL_MAX) return id - BIN_FILL_MIN + 1;
        return -1;
    }

    /**
     * Refill the bin and close it. A full big bin needs 30 items but only 28 watermelons fit in one
     * inventory, so filling takes two bank trips (28, then the remaining 2). When ultracompost is
     * enabled, volcanic ash is added on top of the full bin (while still open) before closing.
     * Re-entered each tick; returns true only once the bin is closed.
     */
    private boolean fillAndCloseCompostBin(Rs2TileObjectModel bin) {
        int id = compostBinMorphId(bin);
        int count = binFillCount(id);
        boolean binFull = count >= BIN_CAPACITY;

        // Phase 1: top the bin up to capacity with watermelons (skipped once ash has been added).
        if (!binFull && !compostBinAshAdded) {
            if (Rs2Inventory.hasItem(ItemID.WATERMELON)) {
                if (findCompostBin() == null) { Rs2Walker.walkTo(COMPOST_BIN_LOCATION, 6); return false; }
                if (ensureExchangeClosed()) return false;
                CustomHerbrunPlugin.status = "Filling compost bin";
                Rs2Inventory.use(ItemID.WATERMELON);
                findCompostBin().click();
                sleepUntil(() -> !Rs2Inventory.hasItem(ItemID.WATERMELON)
                        || compostBinMorphId(findCompostBin()) >= BIN_FILL_MAX, 20000);
                return false;
            }
            // Need more watermelons — return leftover empty buckets, then bank for just what's missing.
            if (Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY)) { depositEmptyBucketsToLeprechaun(); return false; }
            CustomHerbrunPlugin.status = "Banking for watermelons";
            if (!Rs2Bank.isOpen()) {
                Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 6);
                if (!Rs2Bank.openBank()) return false;
                return false;
            }
            Rs2Bank.depositAll();
            Rs2Inventory.waitForInventoryChanges(3000);
            int needed = BIN_CAPACITY - Math.max(count, 0);
            int toWithdraw = Math.min(needed, MAX_FILL_PER_TRIP);
            if (!Rs2Bank.withdrawX(ItemID.WATERMELON, toWithdraw)) {
                log("[Bin] No watermelons in bank - cannot refill");
                Rs2Bank.closeBank();
                return true;
            }
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
            return false;
        }

        // Phase 2: close the bin to start rotting.
        if (findCompostBin() == null) { Rs2Walker.walkTo(COMPOST_BIN_LOCATION, 6); return false; }
        if (ensureExchangeClosed()) return false;
        CustomHerbrunPlugin.status = "Closing compost bin";
        findCompostBin().click("Close");
        sleepUntil(() -> {
            int cur = compostBinMorphId(findCompostBin());
            return cur == BIN_ROTTING || cur == BIN_READY_CLOSED;
        }, 5000);
        return true;
    }

    /** Close the leprechaun exchange overlay if it's open. Returns true if it had to close it
     *  (caller should return and re-tick), false if it was already closed. */
    private boolean ensureExchangeClosed() {
        if (Rs2Leprechaun.isExchangeOpen()) {
            Rs2Leprechaun.closeExchange();
            return true;
        }
        return false;
    }

    /** Open the leprechaun exchange and withdraw ALL empty buckets in one action (Remove-All on 125,16). */
    private boolean withdrawEmptyBucketsFromLeprechaun() {
        if (!Rs2Leprechaun.isExchangeOpen() && !Rs2Leprechaun.openExchange()) return false;
        sleep(300, 600);
        clickLeprechaunAllAction(LEP_WITHDRAW_GROUP, LEP_WITHDRAW_BUCKET_CHILD, "Remove-All");
        sleepUntil(() -> Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY), 3000);
        return Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY);
    }

    /** Deposit collected compost into the leprechaun via the exchange's store side: supercompost on
     *  126,11 and ultracompost on 126,12 (Store-All). Handles whichever the bin produced. */
    private boolean depositCollectedCompost() {
        boolean done = true;
        if (Rs2Inventory.hasItem(ItemID.BUCKET_SUPERCOMPOST)) {
            done &= storeAtLeprechaun(LEP_STORE_SUPERCOMPOST_CHILD, ItemID.BUCKET_SUPERCOMPOST, "supercompost");
        }
        if (Rs2Inventory.hasItem(ItemID.BUCKET_ULTRACOMPOST)) {
            done &= storeAtLeprechaun(LEP_STORE_ULTRACOMPOST_CHILD, ItemID.BUCKET_ULTRACOMPOST, "ultracompost");
        }
        return done;
    }

    /** Return leftover empty buckets to the leprechaun store (Store-All on 126,9). */
    private boolean depositEmptyBucketsToLeprechaun() {
        return storeAtLeprechaun(LEP_STORE_BUCKET_CHILD, ItemID.BUCKET_EMPTY, "empty buckets");
    }

    private boolean storeAtLeprechaun(int storeChild, int itemId, String label) {
        if (!Rs2Inventory.hasItem(itemId)) return true;
        if (!Rs2Leprechaun.isExchangeOpen() && !Rs2Leprechaun.openExchange()) return false;
        sleep(300, 600);
        CustomHerbrunPlugin.status = "Storing " + label;
        clickLeprechaunAllAction(LEP_STORE_GROUP, storeChild, "Store-All");
        sleepUntil(() -> !Rs2Inventory.hasItem(itemId), 3000);
        return !Rs2Inventory.hasItem(itemId);
    }

    /** Invoke the "-All" menu op on a leprechaun exchange item widget. The action-list order isn't
     *  stable (verified live), so locate the label and invoke its 1-based CC_OP op. Builds the menu
     *  entry directly with param0 = -1: clickWidgetFast can't be used here because it substitutes
     *  widget.getType() for a -1 param0, which these component widgets reject (verified live). */
    private boolean clickLeprechaunAllAction(int group, int child, String actionLabel) {
        Widget w = Rs2Widget.getWidget(group, child);
        if (w == null || w.getActions() == null) {
            log("[Bin] Leprechaun widget " + group + "," + child + " not available");
            return false;
        }
        String[] actions = w.getActions();
        for (int i = 0; i < actions.length; i++) {
            if (actionLabel.equalsIgnoreCase(actions[i])) {
                Microbot.doInvoke(new NewMenuEntry()
                        .param0(-1)
                        .param1(w.getId())
                        .opcode(MenuAction.CC_OP.getId())
                        .identifier(i + 1)
                        .itemId(w.getItemId())
                        .target(""), w.getBounds());
                return true;
            }
        }
        log("[Bin] '" + actionLabel + "' not found on widget " + group + "," + child);
        return false;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        initialized = false;
        allotmentsByRegion.clear();
        flowerByRegion.clear();
        currentPhase = LocationPhase.HERB;
        handledAllotmentIds.clear();
        currentAllotmentId = -1;
        timerWaiting = false;
        resumeAtMillis = 0L;
        compostBinHandled = false;
        compostBinDepositedFirst = false;
        compostBinAshAdded = false;
        compostBinAttempts = 0;
    }
}

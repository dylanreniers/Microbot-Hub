package net.runelite.client.plugins.custom.gotr;

import com.google.inject.Inject;
import lombok.Setter;
import net.runelite.api.GameObject;
import net.runelite.api.ItemID;
import net.runelite.api.ObjectID;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.TileObject;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.custom.gotr.data.CellType;
import net.runelite.client.plugins.custom.gotr.data.GuardianPortalInfo;
import net.runelite.client.plugins.custom.gotr.data.RuneType;
import net.runelite.client.plugins.custom.gotr.services.AltarService;
import net.runelite.client.plugins.custom.gotr.services.LocationService;
import net.runelite.client.plugins.custom.gotr.services.MiningService;
import net.runelite.client.plugins.custom.gotr.services.PouchService;
import net.runelite.client.plugins.custom.gotr.services.TimerService;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.Microbot.log;

/**
 * Refactored Guardians of the Rift script with improved maintainability and readability
 */
public class GotrScript extends Script {

    // Static game data (TODO: Move to state service)
    public static final Map<Integer, GuardianPortalInfo> guardianPortalInfo = new HashMap<>();
    public static final List<GameObject> activeGuardianPortals = new ArrayList<>();
    public static int elementalRewardPoints;
    public static int catalyticRewardPoints;
    public final Set<GameObject> guardians = new HashSet<>();
    // Injected services
    private final LocationService locationService;
    private final TimerService timerService;
    private final MiningService miningService;
    private final PouchService pouchService;
    private final AltarService altarService;
    // Game state
    private GotrState currentState;
    private GotrConfig config;
    @Setter
    private boolean shouldMineGuardianRemains = true;
    private boolean initializationComplete = false;
    private long totalTime = 0;

    @Inject
    public GotrScript(LocationService locationService, TimerService timerService, MiningService miningService, PouchService pouchService, AltarService altarService) {
        this.locationService = locationService;
        this.timerService = timerService;
        this.miningService = miningService;
        this.pouchService = pouchService;
        this.altarService = altarService;
    }

    /**
     * Main script execution method
     */
    public boolean run(GotrConfig config) {
        this.config = config;
        altarService.setConfig(config);

        // One-time initialization
        if (!initializationComplete) {
            performInitialization();
            return true;
        }

        // Check equipment requirements
        if (!hasRequiredEquipment()) {
            return false;
        }


        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                long startTime = System.currentTimeMillis();
                executeGameLoop();
                long endTime = System.currentTimeMillis();
                totalTime = endTime - startTime;

            } catch (Exception ex) {
                log("Error in GOTR Script: " + ex.getMessage());
                ex.printStackTrace();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        return true;
    }

    /**
     * Main game execution loop - simplified and organized
     */
    private void executeGameLoop() {
        // Handle urgent pouch repairs
        if (pouchService.repairPouches()) {
            return;
        }

        // Check current location and execute appropriate logic
        if (locationService.isInActiveMinigame()) {
            executeMinigameLoop();
        } else {
            executeOutsideMinigameLoop();
        }
    }

    /**
     * Executes logic when inside the minigame
     */
    private void executeMinigameLoop() {
        int timeToStart = timerService.getTimeToStart();

        // Wait for game to start if needed
        if (isWaitingForGameStart(timeToStart)) {
            handleGameWaitPeriod();
            return;
        }

        // Ensure we have uncharged cells
        if (!Rs2Inventory.hasItem(GotrConstants.UNCHARGED_CELL) && !locationService.isInLargeMine() && !locationService.isInHugeMine()) {
            takeUnchargedCells();
            return;
        }

        // Execute main minigame activities in priority order
        if (handlePortalSpawn() || miningService.mineHugeGuardianRemains() || handleGreatGuardianPowerUp() || handleCellRepairs()) {
            return;
        }

        // Handle essence and rune crafting
        if (shouldMineGuardianRemains) {
            handleGuardianRemainsMining();
        } else {
            handleEssenceAndRuneCrafting();
        }
    }

    /**
     * Executes logic when outside the minigame
     */
    private void executeOutsideMinigameLoop() {
        if (!altarService.craftRunes() && !enterMinigame()) {
            waitForMinigameToStart();
        }
    }

    /**
     * Performs one-time initialization
     */
    private void performInitialization() {
        initializeGuardianPortalInfo();

        if (!Rs2Magic.isSpellbook(Rs2Spellbook.LUNAR)) {
            log("Lunar spellbook not found - disabling NPC contact");
            pouchService.setUseNpcContact(false);
        }

        initializationComplete = true;
        Rs2Walker.setTarget(null);
    }

    /**
     * Checks if player has required equipment
     */
    private boolean hasRequiredEquipment() {
        if (!Rs2Inventory.hasItem(GotrConstants.PICKAXE) && !Rs2Equipment.isWearing(GotrConstants.PICKAXE)) {
            log("You need a pickaxe to participate in this minigame.");
            return false;
        }
        return true;
    }

    /**
     * Determines if we're waiting for the game to start
     */
    private boolean isWaitingForGameStart(int timeToStart) {
        int startTimer = timerService.getStartTimer();
        int threshold = Rs2Random.randomGaussian(GotrConstants.GAME_START_THRESHOLD, Rs2Random.between(1, 5));

        return locationService.isInHugeMine() || startTimer > threshold || startTimer == -1 || timeToStart > 10;
    }

    /**
     * Handles activities during game wait period
     */
    private void handleGameWaitPeriod() {
        if (!Rs2Inventory.hasItem(GotrConstants.UNCHARGED_CELL)) {
            if (locationService.isInLargeMine()) {
                miningService.leaveLargeMine();
                return;
            }
            takeUnchargedCells();
        }

        pouchService.repairPouches();

        if (shouldMineGuardianRemains) {
            miningService.mineGuardianRemains(currentState);
        }
    }

    /**
     * Handles portal spawn detection and entry
     */
    private boolean handlePortalSpawn() {
        if (!locationService.isInHugeMine() && Microbot.getClient().hasHintArrow() && Rs2Inventory.count() < config.maxAmountEssence()) {

            if (locationService.isInLargeMine() && miningService.leaveLargeMine()) {
                return true;
            }

            Rs2Walker.walkFastCanvas(Microbot.getClient().getHintArrowPoint());
            Global.sleepUntil(Rs2Player::isMoving);
            Rs2GameObject.interact(Microbot.getClient().getHintArrowPoint());
            log("Found portal spawn - entering...");

            Rs2Player.waitForWalking();
            Global.sleepUntil(locationService::isInHugeMine);
            Global.sleepUntil(() -> getGuardiansPower() > 0);

            timerService.markPortalSpawn();
            return true;
        }
        return false;
    }

    /**
     * Handles powering up the great guardian
     */
    private boolean handleGreatGuardianPowerUp() {
        if (Rs2Inventory.hasItem(GotrConstants.GUARDIAN_STONE) && !shouldMineGuardianRemains && !locationService.isInLargeMine() && !locationService.isInHugeMine()) {

            currentState = GotrState.POWERING_UP;
            Rs2GameObject.interact("The great guardian", "power-up");
            log("Powering up the great guardian...");

            Global.sleepUntil(Rs2Player::isAnimating);
            Global.sleep(Rs2Random.randomGaussian(Rs2Random.between(1000, 2000), Rs2Random.between(100, 300)));
            return true;
        }
        return false;
    }

    /**
     * Handles cell repairs and upgrades
     */
    private boolean handleCellRepairs() {
        Rs2ItemModel cell = Rs2Inventory.get(CellType.poweredCellList().stream().mapToInt(i -> i).toArray());

        if (cell != null && locationService.isInActiveMinigame() && !shouldMineGuardianRemains && !locationService.isInLargeMine() && !locationService.isInHugeMine()) {

            return repairOrUpgradeCells(cell);
        }
        return false;
    }

    /**
     * Handles guardian remains mining logic
     */
    private void handleGuardianRemainsMining() {
        int guardianPower = getGuardiansPower();
        int fragmentThreshold = guardianPower > GotrConstants.GUARDIAN_POWER_THRESHOLD ? Rs2Random.between(Rs2Inventory.emptySlotCount() + Rs2Inventory.getRemainingCapacityInPouches(), Rs2Inventory.emptySlotCount() + Rs2Inventory.getRemainingCapacityInPouches() + 3) : config.maxFragmentAmount();

        if (Rs2Inventory.hasItemAmount(GotrConstants.GUARDIAN_FRAGMENTS, fragmentThreshold)) {
            shouldMineGuardianRemains = false;
        } else {
            miningService.mineGuardianRemains(currentState);
        }
    }

    /**
     * Handles essence and rune crafting workflow
     */
    private void handleEssenceAndRuneCrafting() {
        // Deposit runes if configured
        if (depositRunesIntoPool()) {
            return;
        }

        // Check if out of fragments
        if (isOutOfFragments()) {
            shouldMineGuardianRemains = true;
            return;
        }

        // Fill pouches and craft essence
        if (pouchService.fillPouchesIfNeeded(getGuardiansPower())) {
            craftGuardianEssences();
            return;
        }

        if (Rs2Inventory.isFull() && Rs2Inventory.hasItem(GotrConstants.GUARDIAN_ESSENCE)) {
            if (locationService.isInLargeMine() && miningService.leaveLargeMine()) {
                return;
            }
            altarService.enterBestAvailableAltar();
        }
    }

    private void takeUnchargedCells() {
        if (Rs2Inventory.hasItem(GotrConstants.UNCHARGED_CELL)) return;

        if (Rs2Inventory.isFull() && Rs2Inventory.drop(ItemID.GUARDIAN_ESSENCE)) {
            log("Dropped Guardian essence to make space for Uncharged cell");
        }

        Rs2GameObject.interact(GotrConstants.UNCHARGED_CELLS_ID, "Take-10");
        log("Taking uncharged cells...");
        Rs2Player.waitForAnimation();
    }

    private boolean isOutOfFragments() {
        boolean outOfFragments = !Rs2Inventory.hasItem(GotrConstants.GUARDIAN_FRAGMENTS) && !Rs2Inventory.isFull();
        boolean portalTimeout = timerService.getTimeSincePortal() > GotrConstants.PORTAL_TIME_THRESHOLD && !Rs2Inventory.hasItem(GotrConstants.GUARDIAN_ESSENCE);

        return outOfFragments || portalTimeout;
    }

    private boolean depositRunesIntoPool() {
        if (config.shouldDepositRunes() && Rs2Inventory.hasItem(GotrConstants.RUNE_IDS.stream().mapToInt(i -> i).toArray()) && !locationService.isInLargeMine() && !locationService.isInHugeMine() && !Rs2Inventory.isFull()) {

            if (Rs2Player.isMoving()) return true;

            if (Rs2GameObject.interact(GotrConstants.DEPOSIT_POOL_ID)) {
                log("Depositing runes into pool...");
                Global.sleep(600, 2400);
                return true;
            }
        }
        return false;
    }

    private boolean craftGuardianEssences() {
        if (Rs2GameObject.interact(GotrConstants.WORKBENCH_ID)) {
            currentState = GotrState.CRAFT_GUARDIAN_ESSENCE;
            Global.sleep(Rs2Random.randomGaussian(Rs2Random.between(600, 900), Rs2Random.between(150, 300)));
            log("Crafting guardian essences...");
            return true;
        }
        return false;
    }

    private boolean repairOrUpgradeCells(Rs2ItemModel cell) {
        int cellTier = CellType.getCellTier(cell.getId());
        List<Integer> shieldCellIds = Rs2GameObject.getObjectIdsByName("cell_tile");

        if (Rs2Inventory.hasItemAmount(GotrConstants.GUARDIAN_ESSENCE, 10)) {
            // Try to upgrade cells
            for (int shieldCellId : shieldCellIds) {
                TileObject shieldCell = Rs2GameObject.getTileObject(shieldCellId);
                if (shieldCell != null && CellType.getShieldTier(shieldCell.getId()) < cellTier) {
                    log("Upgrading power cell at " + shieldCell.getWorldLocation());
                    Rs2GameObject.interact(shieldCell, "Place-cell");
                    Global.sleepUntil(() -> !Rs2Player.isMoving());
                    return true;
                }
            }
        }

        // Repair broken cells
        shieldCellIds = shieldCellIds.stream().filter(id -> id != GotrConstants.CELL_TILE_BROKEN_ID).collect(Collectors.toList());

        int interactedObjectId = Rs2GameObject.interact(shieldCellIds);
        if (interactedObjectId != -1) {
            log("Using cell with ID: " + interactedObjectId);
            Global.sleep(Rs2Random.randomGaussian(1000, 300));
            Global.sleepUntil(() -> !Rs2Player.isMoving());
            return true;
        }

        return false;
    }

    private boolean enterMinigame() {
        if (Rs2GameObject.interact(GotrConstants.BARRIER_ENTER_ID, "quick-pass")) {
            Rs2Player.waitForWalking();
            currentState = GotrState.ENTER_GAME;
            shouldMineGuardianRemains = true;
            log("Entering minigame...");
            return true;
        }
        return false;
    }

    private void waitForMinigameToStart() {
        if (!locationService.isInMainRegion()) {
            TileObject rcPortal = altarService.findPortalToLeaveAltar();
            if (rcPortal != null) {
                altarService.leaveAltar();
                return;
            }
        }

        resetGameState();

        if (currentState != GotrState.WAITING) {
            currentState = GotrState.WAITING;
            log("Waiting for minigame to start...");
            Rs2GameObject.interact(GotrConstants.BARRIER_PEEK_ID, "Peek");
        }
    }

    private int getGuardiansPower() {
        Widget powerWidget = Rs2Widget.getWidget(GotrConstants.POWER_WIDGET_ID);
        if (powerWidget == null) {
            return 0;
        }

        Matcher matcher = GotrConstants.POWER_PERCENTAGE_PATTERN.matcher(powerWidget.getText());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private void initializeGuardianPortalInfo() {
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_AIR, new GuardianPortalInfo("AIR", 1, ItemID.AIR_RUNE, 26887, 4353, RuneType.ELEMENTAL, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_MIND, new GuardianPortalInfo("MIND", 2, ItemID.MIND_RUNE, 26891, 4354, RuneType.CATALYTIC, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_WATER, new GuardianPortalInfo("WATER", 5, ItemID.WATER_RUNE, 26888, 4355, RuneType.ELEMENTAL, CellType.MEDIUM, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_EARTH, new GuardianPortalInfo("EARTH", 9, ItemID.EARTH_RUNE, 26889, 4356, RuneType.ELEMENTAL, CellType.STRONG, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_FIRE, new GuardianPortalInfo("FIRE", 14, ItemID.FIRE_RUNE, 26890, 4357, RuneType.ELEMENTAL, CellType.OVERCHARGED, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_BODY, new GuardianPortalInfo("BODY", 20, ItemID.BODY_RUNE, 26895, 4358, RuneType.CATALYTIC, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_COSMIC, new GuardianPortalInfo("COSMIC", 27, ItemID.COSMIC_RUNE, 26896, 4359, RuneType.CATALYTIC, CellType.MEDIUM, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.LOST_CITY.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_CHAOS, new GuardianPortalInfo("CHAOS", 35, ItemID.CHAOS_RUNE, 26892, 4360, RuneType.CATALYTIC, CellType.MEDIUM, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_NATURE, new GuardianPortalInfo("NATURE", 44, ItemID.NATURE_RUNE, 26897, 4361, RuneType.CATALYTIC, CellType.STRONG, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_LAW, new GuardianPortalInfo("LAW", 54, ItemID.LAW_RUNE, 26898, 4362, RuneType.CATALYTIC, CellType.STRONG, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.TROLL_STRONGHOLD.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_DEATH, new GuardianPortalInfo("DEATH", 65, ItemID.DEATH_RUNE, 26893, 4363, RuneType.CATALYTIC, CellType.OVERCHARGED, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.MOURNINGS_END_PART_II.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_BLOOD, new GuardianPortalInfo("BLOOD", 77, ItemID.BLOOD_RUNE, 26894, 4364, RuneType.CATALYTIC, CellType.OVERCHARGED, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.SINS_OF_THE_FATHER.getState(Microbot.getClient())).orElse(null)));
    }

    @Override
    public void shutdown() {
        currentState = null;
        resetGameState();
        super.shutdown();
    }

    public GotrState getState() {
        return this.currentState;
    }

    // Plugin integration methods
    public void setState(GotrState state) {
        this.currentState = state;
    }

    public int getElementalRewardPoints() {
        return elementalRewardPoints;
    }

    public void setElementalRewardPoints(int points) {
        elementalRewardPoints = points;
    }

    public int getCatalyticRewardPoints() {
        return catalyticRewardPoints;
    }

    public void setCatalyticRewardPoints(int points) {
        catalyticRewardPoints = points;
    }

    public void addGuardian(GameObject guardian) {
        guardians.add(guardian);
    }

    public void removeGuardian(GameObject guardian) {
        guardians.remove(guardian);
    }

    public void removeActivePortal(GameObject portal) {
        activeGuardianPortals.remove(portal);
    }

    public boolean isGuardianPortal(GameObject gameObject) {
        return guardianPortalInfo.containsKey(gameObject.getId());
    }

    public void resetGameState() {
        guardians.clear();
        activeGuardianPortals.clear();
        timerService.resetForNewGame();
        miningService.resetState();
    }

    public Long getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(long totalTime) {
        this.totalTime = totalTime;
    }
}

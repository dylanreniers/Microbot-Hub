package net.runelite.client.plugins.custom.customvaletotems.handlers;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.custom.customvaletotems.CustomValeTotemConfig;
import net.runelite.client.plugins.custom.customvaletotems.enums.BankLocation;
import net.runelite.client.plugins.custom.customvaletotems.enums.GameObjectId;
import net.runelite.client.plugins.custom.customvaletotems.enums.GameState;
import net.runelite.client.plugins.custom.customvaletotems.utils.CoordinateUtils;
import net.runelite.client.plugins.custom.customvaletotems.utils.GameObjectUtils;
import net.runelite.client.plugins.custom.customvaletotems.utils.InventoryUtils;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepGaussian;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

import java.util.Random;
import net.runelite.client.plugins.custom.customvaletotems.enums.TotemLocation;
import net.runelite.client.plugins.custom.customvaletotems.models.GameSession;
import org.slf4j.event.Level;

/**
 * Handles all banking operations for the Vale Totems minigame
 */
public class BankingHandler {

    private static final Random random = new Random();
    private static final double SHORT_AFK_PROBABILITY = 0.40; // 40% chance
    private static final double MEDIUM_AFK_PROBABILITY = 0.30; // 30% chance
    private static final double LONG_AFK_PROBABILITY = 0.10; // 10% chance

    private static final int BANK_INTERACTION_DISTANCE = 5;
    private static final long BANK_TIMEOUT_MS = 10000; // 10 seconds
    
    // Configuration instance
    private static CustomValeTotemConfig config;

    /**
     * Set the configuration for this handler
     * @param config the CustomValeTotemConfig instance
     */
    public static void setConfig(CustomValeTotemConfig config) {
        BankingHandler.config = config;
    }

    /**
     * Navigate to the bank and position correctly for banking
     * @return true if successfully positioned at bank
     */
    public static boolean navigateToBank() {
        try {
            boolean alreadyNear = CoordinateUtils.isNearBank(BANK_INTERACTION_DISTANCE);
            Microbot.log("[Banking] navigateToBank: alreadyNear=" + alreadyNear + " pos=" + CoordinateUtils.getPlayerLocation());

            if (alreadyNear) {
                if (!CoordinateUtils.isAtBankingPosition()) {
                    Microbot.log("[Banking] Walking to optimal banking tile");
                    return Rs2Walker.walkTo(BankLocation.PLAYER_STANDING_TILE.getLocation());
                }
                return true;
            }

            Microbot.log("[Banking] Walking to bank booth at " + BankLocation.BANK_BOOTH.getLocation());
            boolean walked = Rs2Walker.walkTo(BankLocation.BANK_BOOTH.getLocation());
            Microbot.log("[Banking] walkTo bank result=" + walked);
            if (walked) {
                Rs2Player.waitForWalking();
                return Rs2Walker.walkTo(BankLocation.PLAYER_STANDING_TILE.getLocation());
            }

            return false;
        } catch (Exception e) {
            Microbot.log("[Banking] Error navigating to bank: " + e.getMessage());
            return false;
        }
    }

    /**
     * Open the bank interface
     * @return true if bank interface is open
     */
    public static boolean openBank() {
        try {
            if (Rs2Bank.isOpen()) {
                Microbot.log("[Banking] Bank already open");
                return true;
            }

            if (!CoordinateUtils.isNearBank(BANK_INTERACTION_DISTANCE)) {
                Microbot.log("[Banking] openBank: not near bank, navigating first");
                if (!navigateToBank()) {
                    return false;
                }
            }

            Microbot.log("[Banking] Interacting with bank booth (id=" + GameObjectId.BANK_BOOTH.getId() + " at " + BankLocation.BANK_BOOTH.getLocation() + ")");
            boolean interacted = GameObjectUtils.findAndInteractAtLocation(
                    GameObjectId.BANK_BOOTH.getId(),
                    BankLocation.BANK_BOOTH.getLocation(),
                    "Bank"
            );
            Microbot.log("[Banking] Bank booth interact result=" + interacted);

            if (interacted) {
                long startTime = System.currentTimeMillis();
                while (!Rs2Bank.isOpen() && System.currentTimeMillis() - startTime < BANK_TIMEOUT_MS) {
                    sleep(100);
                }
                boolean opened = Rs2Bank.isOpen();
                Microbot.log("[Banking] Bank opened=" + opened);
                return opened;
            }

            return false;
        } catch (Exception e) {
            Microbot.log("[Banking] Error opening bank: " + e.getMessage());
            return false;
        }
    }

    /**
     * Deposit all items except knife
     * @return true if successfully deposited items
     */
    public static boolean depositAllExceptKnife() {
        try {
            if (!Rs2Bank.isOpen() && !openBank()) {
                return false;
            }

            // Deposit all except knife
            boolean deposited = Rs2Bank.depositAllExcept(InventoryUtils.KNIFE_ID, InventoryUtils.FLETCHING_KNIFE_ID);
            
            if (deposited) {
                sleep(1300);
            }

            return true;
        } catch (Exception e) {
            Microbot.log("Error depositing items: " + e.getMessage());
            return false;
        }
    }

        /**
     * Withdraw the required items for the minigame
     * Note: This function assumes materials have already been pre-checked
     * @param gameSession the game session to update if critical errors occur
     * @return true if successfully withdrew required items
     */
    public static boolean withdrawRequiredItems(GameSession gameSession) {
        try {
            // Bank should already be open from prior checks, but ensure it is
            if (!Rs2Bank.isOpen() && !openBank()) {
                Microbot.log("Cannot withdraw items - failed to open bank");
                return false;
            }

            // Ready-made bow modes withdraw finished bows for the carvings PLUS a raw log per totem
            // for the base (the base is always built from a log, never a bow). No fletching.
            if (!InventoryUtils.isFletchingMode()) {
                return withdrawBowsAndBaseLogs();
            }

            // Calculate how many logs to withdraw
            int logsToWithdraw = InventoryUtils.getOptimalLogWithdrawAmount();

            if (logsToWithdraw <= 0) {
                Microbot.log("Already have sufficient logs in inventory - no withdrawal needed");
                return true; // Already have enough materials
            }

            // Get the configured log type ID
            int logId = InventoryUtils.getLogId();
            String logTypeName = config != null ? config.logType().getDisplayName() : "Yew Logs";

            // Rs2Bank.withdrawX only queues the menu click — it returns immediately and the item
            // can lag several ticks behind. Generic waitForInventoryChanges + hasRequiredItems
            // was racing and reporting shortages right after a successful withdraw.
            Microbot.log("Withdrawing " + logsToWithdraw + " " + logTypeName + " from bank");
            int logsBefore = InventoryUtils.getLogCount();
            int expectedLogs = logsBefore + logsToWithdraw;
            if (!Rs2Bank.withdrawX(logId, logsToWithdraw)) {
                Microbot.log("Failed to queue log withdrawal");
                return false;
            }
            if (!sleepUntil(() -> InventoryUtils.getLogCount() >= expectedLogs, 3000)) {
                Microbot.log("Log withdrawal timed out (have " + InventoryUtils.getLogCount() + "/" + expectedLogs + ")");
                return false;
            }
            Microbot.log("Successfully withdrew " + logsToWithdraw + " " + logTypeName);
            return InventoryUtils.hasRequiredItems();

        } catch (Exception e) {
            Microbot.log("Error withdrawing required items: " + e.getMessage());
            return false;
        }
    }

    /**
     * Withdraw materials for ready-made bow modes: a raw log per totem for the bases plus four bows per
     * totem for the carvings (one standard route = 5 totems → 5 logs + 20 bows). The base of every totem
     * is built from a log regardless of where the decorations come from, so the logs must be withdrawn too.
     * @return true if the required items are present in the inventory afterwards
     */
    private static boolean withdrawBowsAndBaseLogs() {
        try {
            int totems = InventoryUtils.HALF_LAP_TOTEMS;

            // Base logs first (fewer items, keeps them from being crowded out of the inventory)
            int logId = InventoryUtils.getLogId();
            int baseLogsWanted = totems * InventoryUtils.BASE_LOGS_PER_TOTEM; // 5
            int logsToWithdraw = baseLogsWanted - InventoryUtils.getLogCount();
            if (logsToWithdraw > 0) {
                int expectedLogs = InventoryUtils.getLogCount() + logsToWithdraw;
                String logTypeName = config != null ? config.logType().getDisplayName() : "logs";
                Microbot.log("Withdrawing " + logsToWithdraw + " " + logTypeName + " for the totem bases");
                if (!Rs2Bank.withdrawX(logId, logsToWithdraw)) {
                    Microbot.log("Failed to queue base-log withdrawal");
                    return false;
                }
                if (!sleepUntil(() -> InventoryUtils.getLogCount() >= expectedLogs, 3000)) {
                    Microbot.log("Base-log withdrawal timed out (have " + InventoryUtils.getLogCount() + "/" + expectedLogs + ")");
                    return false;
                }
            }

            // Then the bows used as decorations
            int bowId = InventoryUtils.getBowId();
            int bowsWanted = totems * InventoryUtils.DECORATIONS_PER_TOTEM; // 20
            int bowsToWithdraw = Math.min(bowsWanted - InventoryUtils.getBowCount(), Rs2Bank.count(bowId));
            if (bowsToWithdraw > 0) {
                int expectedBows = InventoryUtils.getBowCount() + bowsToWithdraw;
                String bowName = config != null ? config.materialSource().getDisplayName() : "bows";
                Microbot.log("Withdrawing " + bowsToWithdraw + " " + bowName + " from bank");
                if (!Rs2Bank.withdrawX(bowId, bowsToWithdraw)) {
                    Microbot.log("Failed to queue bow withdrawal");
                    return false;
                }
                if (!sleepUntil(() -> InventoryUtils.getBowCount() >= expectedBows, 3000)) {
                    Microbot.log("Bow withdrawal timed out (have " + InventoryUtils.getBowCount() + "/" + expectedBows + ")");
                    return false;
                }
            }

            return InventoryUtils.hasRequiredItems();

        } catch (Exception e) {
            Microbot.log("Error withdrawing bows and base logs: " + e.getMessage());
            return false;
        }
    }

    public static boolean fixZoom() {
        try {
            Rs2Camera.setZoom(0);
            return true;
        } catch (Exception e) {
            Microbot.log("Error fixing zoom: " + e.getMessage());
            return false;
        }
    }

    /**
     * Detect which route type to use based on inventory and bank contents
     * This method ensures the bank is open before checking for log basket
     * @return RouteType.EXTENDED if log basket is present, RouteType.STANDARD otherwise
     */
    public static TotemLocation.RouteType detectRouteTypeWithBankOpen() {
        try {
            // Check inventory first
            if (InventoryUtils.hasLogBasket()) {
                Microbot.log("Log basket detected in inventory - enabling Extended Route (8 totems)");
                return TotemLocation.RouteType.EXTENDED;
            }

            // Ensure bank is open before checking
            if (!Rs2Bank.isOpen() && !openBank()) {
                Microbot.log("Could not open bank for route detection - defaulting to Standard Route");
                return TotemLocation.RouteType.STANDARD;
            }

            // Check bank for log basket
            if (Rs2Bank.hasItem(InventoryUtils.LOG_BASKET_ID)) {
                Microbot.log("Log basket detected in bank - enabling Extended Route (8 totems)");
                return TotemLocation.RouteType.EXTENDED;
            }

            Microbot.log("No log basket detected in inventory or bank - using Standard Route (5 totems)");
            return TotemLocation.RouteType.STANDARD;

        } catch (Exception e) {
            Microbot.log("Error detecting route type: " + e.getMessage());
            Microbot.log("Defaulting to Standard Route due to error");
            return TotemLocation.RouteType.STANDARD;
        }
    }

    /**
     * Perform a unified banking cycle that detects route type and calls appropriate banking method
     * @param gameSession the game session to update with detected route type
     * @return true if banking cycle completed successfully
     */
    public static boolean performUnifiedBankingCycle(GameSession gameSession) {
        try {
            var playerPos = CoordinateUtils.getPlayerLocation();
            int distToBank = CoordinateUtils.getDistanceToBank();
            boolean nearBank = CoordinateUtils.isNearBank(BANK_INTERACTION_DISTANCE);
            Microbot.log("[Banking] Start cycle. Player=" + playerPos + " distToBank=" + distToBank + " nearBank=" + nearBank);

            if (!nearBank) {
                Microbot.log("[Banking] Not near bank, navigating...");
                boolean navResult = navigateToBank();
                Microbot.log("[Banking] Navigate result=" + navResult + " newPos=" + CoordinateUtils.getPlayerLocation());
                if (!navResult) {
                    return false;
                }
            }

            Microbot.log("[Banking] Opening bank...");
            if (!openBank()) {
                Microbot.log("[Banking] Failed to open bank");
                return false;
            }
            Microbot.log("[Banking] Bank opened successfully");

            // Logs mode: a log basket (if present) unlocks the 8-site extended route, else standard.
            // Ready-made bow modes always run the full 8-site lap, resupplying at the Nemus Retreat
            // bank buffalo halfway (after site 4) since bows can't go in a log basket.
            TotemLocation.RouteType detectedRouteType = InventoryUtils.isFletchingMode()
                    ? detectRouteTypeWithBankOpen()
                    : TotemLocation.RouteType.EXTENDED;

            // Update game session with detected route type
            if (gameSession.getCurrentRouteType() != detectedRouteType) {
                gameSession.setRouteType(detectedRouteType);
            }

            // Handle random AFK
            //handleRandomAFK();

            boolean bankingSuccess = false;

            // Call appropriate banking method based on material source and detected route type
            if (!InventoryUtils.isFletchingMode()) {
                Microbot.log("Performing ready-made bow banking cycle");
                bankingSuccess = performBowBankingOperations(gameSession);
            } else if (detectedRouteType == TotemLocation.RouteType.EXTENDED) {
                Microbot.log("Performing extended route banking cycle");
                bankingSuccess = performExtendedRouteBankingOperations(gameSession);
            } else {
                Microbot.log("Performing standard route banking cycle");
                bankingSuccess = performStandardBankingOperations(gameSession);
            }

            if (bankingSuccess) {
                // Close bank
                Rs2Bank.closeBank();
                fixZoom();
                Microbot.log("Banking cycle completed successfully using " + detectedRouteType.getDescription());
            }

            return bankingSuccess;

        } catch (Exception e) {
            Microbot.log("Error during unified banking cycle: " + e.getMessage());
            return false;
        }
    }

    /**
     * Perform standard route banking operations (assumes bank is already open)
     * @return true if standard banking operations completed successfully
     */
    private static boolean performStandardBankingOperations(GameSession gameSession) {
        try {
            // Step 1: Ensure we have a knife in inventory
            if (!ensureKnifeInInventory(gameSession)) {
                Microbot.log("Failed to ensure knife availability for standard route");
                return false;
            }

            // Step 2: Deposit all except knife (and stamina potion if we have one)
            if (!depositAllExceptKnife()) {
                Microbot.log("Failed to deposit items for standard route");
                return false;
            }

            // Step 3: Check if we have enough materials
            if (!hasSufficientMaterials(1)) {
                Microbot.log("Not enough materials for standard route - stopping bot");
                gameSession.setState(GameState.STOPPING);
                return false;
            }

            // Step 4: Withdraw required items
            if (!withdrawRequiredItems(gameSession)) {
                Microbot.log("Failed to withdraw required items for standard route");
                return false;
            }

            // Step 5: Handle stamina potion withdrawal if enabled
            if (!handleStaminaPotionWithdrawal()) {
                Microbot.log("Warning: Failed to handle stamina potions - continuing without");
            }

            return true;
        } catch (Exception e) {
            Microbot.log("Error during standard banking operations: " + e.getMessage());
            return false;
        }
    }

    /**
     * Perform banking operations for ready-made bow modes (unstrung/strung bows).
     * No knife or fletching is involved — deposit everything, then fill the inventory
     * with the configured bows. Assumes bank is already open.
     * @param gameSession the game session to update if critical errors occur
     * @return true if bow banking operations completed successfully
     */
    private static boolean performBowBankingOperations(GameSession gameSession) {
        try {
            // Step 1: Ensure a knife is in the inventory (needed to build/carve totems even with ready-made bows)
            if (!ensureKnifeInInventory(gameSession)) {
                Microbot.log("Failed to ensure knife availability for bow mode");
                return false;
            }

            // Step 2: Deposit everything except the knife
            if (!depositAllExceptKnife()) {
                Microbot.log("Failed to deposit items for bow mode");
                return false;
            }

            // Step 3: Check the bank has enough bows AND base logs for a full standard route
            if (!hasSufficientBows(1)) {
                Microbot.log("Not enough bows/base logs in bank for the route - stopping bot");
                gameSession.setState(GameState.STOPPING);
                return false;
            }

            // Step 4: Withdraw bows (decorations) and raw logs (bases)
            if (!withdrawRequiredItems(gameSession)) {
                Microbot.log("Failed to withdraw bows and base logs");
                return false;
            }

            // Step 5: Handle stamina potion withdrawal if enabled
            if (!handleStaminaPotionWithdrawal()) {
                Microbot.log("Warning: Failed to handle stamina potions - continuing without");
            }

            return true;
        } catch (Exception e) {
            Microbot.log("Error during bow banking operations: " + e.getMessage());
            return false;
        }
    }

    /**
     * Mid-lap resupply for ready-made bow modes at the Nemus Retreat bank buffalo (after site 4).
     * Walks to the buffalo tile, banks on the buffalo game object, then tops the inventory back up
     * to a half-lap of materials (4 base logs + 16 bows). Same account bank as Auburnvale, so stock
     * is shared. Does not reset the route — the caller continues straight on to site 5.
     * @param gameSession the game session (set to STOPPING on a hard material shortage)
     * @return true if the resupply completed and we can continue the lap
     */
    public static boolean resupplyAtNemus(GameSession gameSession) {
        try {
            WorldPoint buffaloTile = BankLocation.NEMUS_STANDING.getLocation();

            // Walk to the buffalo tile if we're not already next to it. Use the shortcut-aware
            // walker so agility shortcuts across the bridge to Nemus Retreat are actually taken.
            if (CoordinateUtils.getPlayerLocation().distanceTo(buffaloTile) > 4) {
                Microbot.log("[Nemus] Walking to bank buffalo at " + buffaloTile);
                NavigationHandler.walkToWithAgilityShortcuts(buffaloTile, 3);
            }

            if (!openNemusBank()) {
                Microbot.log("[Nemus] Failed to open bank on the buffalo");
                return false;
            }

            // Keep the knife, dump everything else (offerings included), then restock the second half
            if (!ensureKnifeInInventory(gameSession)) {
                Microbot.log("[Nemus] Failed to ensure knife availability");
                return false;
            }
            if (!depositAllExceptKnife()) {
                Microbot.log("[Nemus] Failed to deposit items");
                return false;
            }
            if (!hasSufficientBows(1)) {
                Microbot.log("[Nemus] Not enough bows/base logs for the second half - stopping bot");
                gameSession.setState(GameState.STOPPING);
                return false;
            }
            if (!withdrawRequiredItems(gameSession)) {
                Microbot.log("[Nemus] Failed to withdraw bows and base logs");
                return false;
            }
            if (!handleStaminaPotionWithdrawal()) {
                Microbot.log("[Nemus] Warning: failed to handle stamina potions - continuing without");
            }

            Rs2Bank.closeBank();
            fixZoom();
            Microbot.log("[Nemus] Resupply complete - continuing to the second half of the lap");
            return true;

        } catch (Exception e) {
            Microbot.log("[Nemus] Error during resupply: " + e.getMessage());
            return false;
        }
    }

    /**
     * Open the bank interface via the Nemus Retreat bank buffalo game object.
     * @return true once the bank interface is open
     */
    private static boolean openNemusBank() {
        if (Rs2Bank.isOpen()) {
            return true;
        }
        Microbot.log("[Nemus] Banking on buffalo (object id=" + GameObjectId.BANK_BUFFALO.getId() + ")");
        boolean interacted = GameObjectUtils.findAndInteract(GameObjectId.BANK_BUFFALO.getId(), "Bank");
        if (!interacted) {
            Microbot.log("[Nemus] Could not interact with bank buffalo");
            return false;
        }
        return sleepUntil(Rs2Bank::isOpen, (int) BANK_TIMEOUT_MS);
    }

    /**
     * Check if the bank (plus inventory) holds enough ready-made bows for the standard route.
     * @param roundsPlanned number of standard-route rounds planned (20 bows each)
     * @return true if sufficient bows are available
     */
    public static boolean hasSufficientBows(int roundsPlanned) {
        try {
            if (!Rs2Bank.isOpen() && !openBank()) {
                Microbot.log("Unable to check bows - cannot open bank");
                return false;
            }

            int bowsNeeded = roundsPlanned * InventoryUtils.HALF_LAP_TOTEMS * InventoryUtils.DECORATIONS_PER_TOTEM; // 4 totems * 4 bows
            int bowId = InventoryUtils.getBowId();
            int totalBowsAvailable = InventoryUtils.getBowCount() + Rs2Bank.count(bowId);

            // The base of every totem is built from a raw log, so bow modes also need base logs
            int baseLogsNeeded = roundsPlanned * InventoryUtils.HALF_LAP_TOTEMS * InventoryUtils.BASE_LOGS_PER_TOTEM; // 4 totems * 1 log
            int logId = InventoryUtils.getLogId();
            int totalBaseLogsAvailable = InventoryUtils.getLogCount() + Rs2Bank.count(logId);

            boolean sufficient = true;
            StringBuilder shortage = new StringBuilder();
            if (totalBowsAvailable < bowsNeeded) {
                sufficient = false;
                String bowName = config != null
                        ? config.materialSource().getDisplayName() + " (" + config.logType().getDisplayName().replace(" Logs", "") + " " + config.bowType().getDisplayName() + ")"
                        : "bows";
                shortage.append(String.format("• Bows: Have %d, Need %d (%s)%n", totalBowsAvailable, bowsNeeded, bowName));
            }
            if (totalBaseLogsAvailable < baseLogsNeeded) {
                sufficient = false;
                String logTypeName = config != null ? config.logType().getDisplayName() : "logs";
                shortage.append(String.format("• Base logs: Have %d, Need %d (%s)%n", totalBaseLogsAvailable, baseLogsNeeded, logTypeName));
            }

            if (!sufficient) {
                Microbot.log("=== MATERIAL SHORTAGE DETECTED ===");
                Microbot.log(shortage.toString().trim());
                Microbot.log("==================================");
                Microbot.showMessage("Material Shortage:\n" + shortage.toString().trim());
                return false;
            }

            Microbot.log("Bow material check passed - sufficient bows and base logs for " + roundsPlanned + " round(s)");
            return true;
        } catch (Exception e) {
            Microbot.log("Error checking bank bows: " + e.getMessage());
            return false;
        }
    }

    /**
     * Perform extended route banking operations (assumes bank is already open)
     * @return true if extended banking operations completed successfully
     */
    private static boolean performExtendedRouteBankingOperations(GameSession gameSession) {
        try {
            Microbot.log("Starting extended route banking operations");

            // Step 1: Ensure we have knife and log basket in inventory
            if (!ensureKnifeAndLogBasketInInventory(gameSession)) {
                Microbot.log("Failed to ensure knife and log basket are in inventory");
                return false;
            }

            // Step 2: Empty log basket if this is initial banking or log type changed
            if (InventoryUtils.shouldEmptyLogBasket(gameSession)) {
                Microbot.log("Emptying log basket for fresh start");
                if (!InventoryUtils.emptyLogBasket(gameSession)) {
                    Microbot.log("Failed to empty log basket");
                    return false;
                }
            }

            // Step 3: Check if we have enough materials for extended route
            if (!hasSufficientMaterialsForExtendedRoute(gameSession, 1)) {
                Microbot.log("Not enough materials for extended route - stopping bot");
                gameSession.setState(GameState.STOPPING);
                return false;
            }

            // Step 4: Deposit all items except knife and log basket
            if (!depositAllExceptKnifeAndLogBasket()) {
                Microbot.log("Failed to deposit items while preserving knife and log basket");
                return false;
            }

            // Step 5: Perform log basket filling operation
            if (!performLogBasketFillingOperation(gameSession)) {
                Microbot.log("Failed to perform log basket filling operation");
                return false;
            }

            // Step 6: Handle stamina potion withdrawal if enabled
            if (!handleStaminaPotionWithdrawal()) {
                Microbot.log("Warning: Failed to handle stamina potions - continuing without");
            }

            Microbot.log("Extended route banking operations completed successfully");
            return true;

        } catch (Exception e) {
            Microbot.log("Error during extended route banking operations: " + e.getMessage());
            return false;
        }
    }

    /**
     * Handles random AFK scenarios while banking
     */
    private static void handleRandomAFK() {
        double roll = random.nextDouble(); // Generates a value between 0.0 and 1.0

        if (roll < LONG_AFK_PROBABILITY) {
            // Long AFK: 30 to 90 seconds
            Microbot.log("Performing a long AFK...");
            Microbot.status = "Long AFK";
            Rs2Antiban.moveMouseOffScreen(90);
            sleep(30000, 90000);
        } else if (roll < MEDIUM_AFK_PROBABILITY) {
            // Medium AFK: 10 to 30 seconds
            Microbot.log("Performing a medium AFK...");
            Microbot.status = "Medium AFK";
            Rs2Antiban.moveMouseOffScreen(90);
            sleep(10000, 30000);
        } else if (roll < SHORT_AFK_PROBABILITY) {
            // Short AFK: 2 to 8 seconds
            Microbot.log("Performing a short AFK...");
            Microbot.status = "Short AFK";
            Rs2Antiban.moveMouseOffScreen(60);
            sleep(2000, 8000);
        }
    }

    /**
     * Check if we have enough materials in bank for continued operation
     * @param roundsPlanned number of rounds we want to complete
     * @return true if bank has sufficient materials
     */
    public static boolean hasSufficientMaterials(int roundsPlanned) {
        try {
            if (!Rs2Bank.isOpen() && !openBank()) {
                Microbot.log("Unable to check materials - cannot open bank");
                return false;
            }

            boolean hasSufficientMaterials = true;
            StringBuilder shortageDetails = new StringBuilder();

            // Check for knife
            boolean hasKnifeAvailable = InventoryUtils.hasKnife() ||
                Rs2Bank.hasItem(InventoryUtils.KNIFE_ID) ||
                Rs2Bank.hasItem(InventoryUtils.FLETCHING_KNIFE_ID);

            if (!hasKnifeAvailable) {
                hasSufficientMaterials = false;
                shortageDetails.append("• No knife available (inventory or bank)\n");
            }

            // Check for logs (25 logs per round for standard route)
            int logsNeeded = roundsPlanned * 25;
            int currentLogs = InventoryUtils.getLogCount();
            int logId = InventoryUtils.getLogId();
            int bankLogs = Rs2Bank.count(logId);
            int totalLogsAvailable = currentLogs + bankLogs;

            if (totalLogsAvailable < logsNeeded) {
                hasSufficientMaterials = false;
                String logTypeName = config != null ? config.logType().getDisplayName() : "Yew Logs";
                shortageDetails.append(String.format("• Logs: Have %d, Need %d (%s)\n",
                    totalLogsAvailable, logsNeeded, logTypeName));
            }

            // Log material status
            if (hasSufficientMaterials) {
                Microbot.log("Material check passed - sufficient materials for " + roundsPlanned + " rounds");
            } else {
                Microbot.log("=== MATERIAL SHORTAGE DETECTED ===");
                Microbot.log("Standard Route Requirements:");
                Microbot.log(shortageDetails.toString().trim());
                Microbot.log("==================================");
                Microbot.showMessage("Material Shortage: " + shortageDetails.toString().trim());
            }

            return hasSufficientMaterials;

        } catch (Exception e) {
            Microbot.log("Error checking bank materials: " + e.getMessage());
            return false;
        }
    }

    /**
     * Handle critical material shortage by setting game state to STOPPING
     * @param gameSession the game session to update
     * @param missingItem description of what material is missing
     */
    private static void handleCriticalMaterialShortage(GameSession gameSession, String missingItem) {
        // Log critical error with clear formatting
        Microbot.log("=== CRITICAL MATERIAL SHORTAGE DETECTED ===");
        Microbot.log("Issue: " + missingItem);
        Microbot.log("Bot Status: Stopping due to insufficient materials");
        Microbot.log("Action Required: Please restock materials in bank before restarting");
        Microbot.log("=============================================");

        Microbot.showMessage("Critical Material Shortage: " + missingItem);

        // Update game session state
        gameSession.setState(GameState.STOPPING);
    }


    /**
     * Check if we have sufficient materials in bank for extended route
     * @param gameSession the game session that tracks log basket contents
     * @param roundsPlanned number of extended route rounds planned
     * @return true if bank has sufficient materials for extended route
     */
    public static boolean hasSufficientMaterialsForExtendedRoute(net.runelite.client.plugins.custom.customvaletotems.models.GameSession gameSession, int roundsPlanned) {
        try {
            if (!Rs2Bank.isOpen() && !openBank()) {
                Microbot.log("Unable to check materials for extended route - cannot open bank");
                return false;
            }

            boolean hasSufficientMaterials = true;
            StringBuilder shortageDetails = new StringBuilder();

            // Check for knife
            boolean hasKnifeAvailable = InventoryUtils.hasKnife() ||
                Rs2Bank.hasItem(InventoryUtils.KNIFE_ID) ||
                Rs2Bank.hasItem(InventoryUtils.FLETCHING_KNIFE_ID);

            if (!hasKnifeAvailable) {
                hasSufficientMaterials = false;
                shortageDetails.append("• No knife available (inventory or bank)\n");
            }

            // Check for log basket
            boolean hasLogBasket = InventoryUtils.hasLogBasket();
            if (!hasLogBasket) {
                hasSufficientMaterials = false;
                shortageDetails.append("• No log basket available (inventory or bank)\n");
            }

            // Check for logs (40 logs per extended route round - 8 totems * 5 each)
            int logsNeeded = roundsPlanned * 40;
            int currentLogs = InventoryUtils.getLogCount();
            int logId = InventoryUtils.getLogId();
            int bankLogs = Rs2Bank.count(logId);
            int logBasketLogs = InventoryUtils.getLogBasketLogCount(gameSession);
            int totalLogsAvailable = currentLogs + bankLogs + logBasketLogs;

            if (totalLogsAvailable < logsNeeded) {
                hasSufficientMaterials = false;
                String logTypeName = config != null ? config.logType().getDisplayName() : "Yew Logs";
                shortageDetails.append(String.format("• Logs: Have %d (inventory: %d, bank: %d, basket: %d), Need %d (%s)\n",
                    totalLogsAvailable, currentLogs, bankLogs, logBasketLogs, logsNeeded, logTypeName));
            }

            // Log material status
            if (hasSufficientMaterials) {
                Microbot.log("Extended route material check passed - sufficient materials");
            } else {
                Microbot.log("=== MATERIAL SHORTAGE DETECTED (EXTENDED ROUTE) ===");
                Microbot.log("Extended Route Requirements (8 totems per round):");
                Microbot.log(shortageDetails.toString().trim());
                Microbot.log("==================================================");
                Microbot.showMessage("Material Shortage: " + shortageDetails.toString().trim());
            }

            return hasSufficientMaterials;

        } catch (Exception e) {
            Microbot.log("Error checking bank materials for extended route: " + e.getMessage());
            return false;
        }
    }

    /**
     * Withdraw log basket from bank if available and not in inventory
     * @return true if log basket is now available (either was in inventory or successfully withdrawn)
     */
    public static boolean ensureLogBasketAvailable() {
        try {
            // Check if already in inventory
            if (InventoryUtils.hasLogBasket()) {
                return true;
            }
            
            // Check if bank is open
            if (!Rs2Bank.isOpen() && !openBank()) {
                return false;
            }
            
            // Check if log basket is in bank and withdraw it
            if (Rs2Bank.hasItem(InventoryUtils.LOG_BASKET_ID)) {
                Microbot.log("Withdrawing log basket from bank for extended route");
                if (!Rs2Bank.withdrawOne(InventoryUtils.LOG_BASKET_ID)) {
                    Microbot.log("Failed to queue log basket withdrawal");
                    return false;
                }
                return sleepUntil(InventoryUtils::hasLogBasket, 3000);
            }

            Microbot.log("No log basket found in bank");
            return false;
            
        } catch (Exception e) {
            Microbot.log("Error ensuring log basket availability: " + e.getMessage());
            return false;
        }
    }

    /**
     * Deposit all items except knife and log basket (for extended route)
     * @return true if successfully deposited items
     */
    public static boolean depositAllExceptKnifeAndLogBasket() {
        try {
            if (!Rs2Bank.isOpen() && !openBank()) {
                return false;
            }

            // Deposit all except knife and log basket
            boolean deposited = Rs2Bank.depositAllExcept(
                InventoryUtils.KNIFE_ID, 
                InventoryUtils.FLETCHING_KNIFE_ID,
                InventoryUtils.LOG_BASKET_ID
            );
            
            if (deposited) {
                Rs2Inventory.waitForInventoryChanges(3000);
            }

            return true;
        } catch (Exception e) {
            Microbot.log("Error depositing items (preserving knife and log basket): " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensure knife is available in inventory (prioritizes fletching knife)
     * @param gameSession the game session to update if critical errors occur
     * @return true if knife is available in inventory
     */
    private static boolean ensureKnifeInInventory(GameSession gameSession) {
        try {
            if (!Rs2Bank.isOpen() && !openBank()) {
                Microbot.log("Cannot ensure knife availability - failed to open bank");
                return false;
            }

            // Check if we already have a knife in inventory
            if (InventoryUtils.hasKnife()) {
                return true;
            }

            // Try to withdraw fletching knife first (prioritized).
            // Rs2Bank.withdrawOne returns true as soon as the click is queued; we must wait
            // on the concrete predicate (hasKnife), not a generic inventory-change wait,
            // or a slow server tick causes a stale "no knife" read right after we withdrew —
            // the code then used to fall through and try the regular knife, which isn't in
            // the bank either, and report a critical shortage on a knife that was in-flight.
            if (Rs2Bank.hasItem(InventoryUtils.FLETCHING_KNIFE_ID)) {
                Microbot.log("Withdrawing Fletching knife from bank (prioritized)");
                if (Rs2Bank.withdrawOne(InventoryUtils.FLETCHING_KNIFE_ID)) {
                    if (sleepUntil(InventoryUtils::hasKnife, 3000)) {
                        Microbot.log("Successfully withdrew Fletching knife");
                        return true;
                    }
                    Microbot.log("Fletching knife withdrawal timed out");
                    return false;
                }
                Microbot.log("Failed to queue Fletching knife withdrawal");
            }

            // Try to withdraw regular knife as fallback
            if (Rs2Bank.hasItem(InventoryUtils.KNIFE_ID)) {
                Microbot.log("Withdrawing regular knife from bank");
                if (Rs2Bank.withdrawOne(InventoryUtils.KNIFE_ID)) {
                    if (sleepUntil(InventoryUtils::hasKnife, 3000)) {
                        Microbot.log("Successfully withdrew regular knife");
                        return true;
                    }
                    Microbot.log("Regular knife withdrawal timed out");
                    return false;
                }
                Microbot.log("Failed to queue regular knife withdrawal");
            }

            // No knife found anywhere - critical error
            handleCriticalMaterialShortage(gameSession, "No knife available (checked inventory and bank for both Fletching knife and regular knife)");
            return false;

        } catch (Exception e) {
            Microbot.log("Error ensuring knife in inventory: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensure knife and log basket are in inventory (for extended route)
     * @param gameSession the game session to update if critical errors occur
     * @return true if both items are available in inventory
     */
    private static boolean ensureKnifeAndLogBasketInInventory(GameSession gameSession) {
        try {
            if (!Rs2Bank.isOpen() && !openBank()) {
                Microbot.log("Cannot ensure knife and log basket availability - failed to open bank");
                return false;
            }

            // Ensure we have a knife (prioritizing fletching knife). Wait on the concrete
            // predicate so a slow inventory update doesn't get misread as "no knife in bank".
            if (!InventoryUtils.hasKnife()) {
                if (Rs2Bank.hasItem(InventoryUtils.FLETCHING_KNIFE_ID)) {
                    Microbot.log("Withdrawing Fletching knife from bank (prioritized)");
                    if (!Rs2Bank.withdrawOne(InventoryUtils.FLETCHING_KNIFE_ID)) {
                        Microbot.log("Failed to queue Fletching knife withdrawal");
                        return false;
                    }
                } else if (Rs2Bank.hasItem(InventoryUtils.KNIFE_ID)) {
                    Microbot.log("Withdrawing regular knife from bank");
                    if (!Rs2Bank.withdrawOne(InventoryUtils.KNIFE_ID)) {
                        Microbot.log("Failed to queue knife withdrawal");
                        return false;
                    }
                } else {
                    handleCriticalMaterialShortage(gameSession, "No knife available (checked inventory and bank for both Fletching knife and regular knife)");
                    return false;
                }
                if (!sleepUntil(InventoryUtils::hasKnife, 3000)) {
                    Microbot.log("Knife withdrawal timed out");
                    return false;
                }
            }

            // Ensure we have a log basket
            if (!InventoryUtils.hasLogBasket()) {
                if (Rs2Bank.hasItem(InventoryUtils.LOG_BASKET_ID)) {
                    if (!Rs2Bank.withdrawOne(InventoryUtils.LOG_BASKET_ID)) {
                        Microbot.log("Failed to queue log basket withdrawal");
                        return false;
                    }
                } else {
                    handleCriticalMaterialShortage(gameSession, "No log basket available (checked inventory and bank)");
                    return false;
                }
                if (!sleepUntil(InventoryUtils::hasLogBasket, 3000)) {
                    Microbot.log("Log basket withdrawal timed out");
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            Microbot.log("Error ensuring knife and log basket in inventory: " + e.getMessage());
            return false;
        }
    }

    private static boolean handleStaminaPotionWithdrawal() {
        if (config == null || !config.useStaminaPotions() || !Rs2Bank.isOpen()) {
            return true;
        }

        if (Rs2Inventory.hasItem(Rs2Potion.getStaminaPotion()) ||
            Rs2Inventory.hasItem(Rs2Potion.getRestoreEnergyPotionsVariants().toArray(String[]::new))) {
            return true;
        }

        InventoryUtils.depositEmptyVials();

        int currentEnergy = Rs2Player.getRunEnergy();
        int threshold = config.staminaThreshold();
        boolean needsDrink = currentEnergy <= threshold && !Rs2Player.hasStaminaBuffActive();

        String potionToWithdraw = findBestPotionInBank();
        if (potionToWithdraw == null) {
            Microbot.log("No stamina or energy potions available in bank");
            return true;
        }

        if (needsDrink) {
            Microbot.log("Run energy low - withdrawing and drinking potion");
            Rs2Bank.withdrawOne(potionToWithdraw);
            Rs2Inventory.waitForInventoryChanges(1800);
            if (Rs2Inventory.hasItem(potionToWithdraw)) {
                Rs2Inventory.interact(potionToWithdraw, "drink");
                Rs2Inventory.waitForInventoryChanges(1800);
                InventoryUtils.depositEmptyVials();
            }
        } else {
            Microbot.log("Withdrawing potion for navigation");
            Rs2Bank.withdrawOne(potionToWithdraw);
            Rs2Inventory.waitForInventoryChanges(1800);
        }

        return true;
    }

    private static String findBestPotionInBank() {
        if (Rs2Bank.hasItem(Rs2Potion.getStaminaPotion())) {
            return Rs2Potion.getStaminaPotion();
        }

        List<String> energyVariants = Rs2Potion.getRestoreEnergyPotionsVariants();
        Rs2ItemModel energyPotion = Rs2Bank.bankItems().stream()
                .filter(item -> energyVariants.stream()
                        .anyMatch(variant -> item.getName().toLowerCase().contains(variant.toLowerCase())))
                .findFirst()
                .orElse(null);

        return energyPotion != null ? energyPotion.getName() : null;
    }

    /**
     * Perform the log basket filling operation according to user specifications:
     * 1. Take inventory full of logs
     * 2. Close bank
     * 3. Fill log basket
     * 4. Open bank
     * 5. Take rest of needed logs to inventory
     * @param gameSession the game session to update log basket tracking
     * @return true if operation completed successfully
     */
    private static boolean performLogBasketFillingOperation(net.runelite.client.plugins.custom.customvaletotems.models.GameSession gameSession) {
        try {
            Microbot.log("Starting log basket filling operation");

            // Step 1: Take inventory full of logs (leaving space for knife and basket)
            int logsToWithdraw = InventoryUtils.getOptimalLogBasketLogAmountForExtendedRoute(gameSession) - InventoryUtils.getLogCount();
            int logId = InventoryUtils.getLogId();
            int logsBeforeFirst = InventoryUtils.getLogCount();

            if (!Rs2Bank.withdrawX(logId, logsToWithdraw)) {
                Microbot.log("Failed to queue log withdrawal for basket-fill step");
                return false;
            }

            int expectedAfterFirst = logsBeforeFirst + logsToWithdraw;
            if (!sleepUntil(() -> InventoryUtils.getLogCount() >= expectedAfterFirst, 3000)) {
                Microbot.log("Log withdrawal timed out in basket-fill step");
                return false;
            }

            // Step 2: Close bank
            Rs2Bank.closeBank();

            sleepGaussian(500,300);

            // Step 3: Fill log basket with logs from inventory
            if (!InventoryUtils.fillLogBasket(gameSession)) {
                Microbot.log("Failed to fill log basket");
                return false;
            }

            // Step 4: Open bank again
            if (!openBank()) {
                Microbot.log("Failed to reopen bank after filling basket");
                return false;
            }

            // Step 5: Take rest of needed logs to inventory
            int logsStillNeeded = InventoryUtils.getOptimalLogAmountForExtendedRoute(gameSession);
            if (logsStillNeeded > 0) {
                Microbot.log("Withdrawing additional " + logsStillNeeded + " logs for extended route");
                int logsBeforeSecond = InventoryUtils.getLogCount();
                if (!Rs2Bank.withdrawX(logId, logsStillNeeded)) {
                    Microbot.log("Failed to queue additional log withdrawal");
                    return false;
                }
                int expectedAfterSecond = logsBeforeSecond + logsStillNeeded;
                if (!sleepUntil(() -> InventoryUtils.getLogCount() >= expectedAfterSecond, 3000)) {
                    Microbot.log("Additional log withdrawal timed out");
                    return false;
                }
            }

            Microbot.log("Log basket filling operation completed successfully");
            return true;

        } catch (Exception e) {
            Microbot.log("Error during log basket filling operation: " + e.getMessage());
            return false;
        }
    }
}
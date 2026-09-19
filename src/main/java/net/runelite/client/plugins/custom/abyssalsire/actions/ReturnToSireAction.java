package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetupsItem;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.poh.PohTeleports;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Between-kills restock + travel loop, armed by {@link LootAction} once looting finishes (when
 * "Restock between kills" is on). Runs as ONE blocking sequence on the tick worker thread — the Rs2
 * helpers (teleport, bank, walk) all block via {@code sleepUntil}, so a linear script fits them
 * better than a per-tick machine. The tick overlap guard skips other ticks while it runs; nothing
 * else needs to run between kills. On any step failure it aborts, logs and clears the flag (the bot
 * goes idle so the problem is visible rather than looping).
 *
 * <p>Route: house tablet -> if eating to full would leave &lt; {@value #MIN_FOOD_AFTER_HEAL} food,
 * POH portal to the Grand Exchange and restock the RANGE setup, then house tablet back -> restore at
 * the pool of Rejuvenation -> fairy ring (last-destination = DIP) -> walk to the barrage spot with the
 * regular walker. Then {@link #execute} resets the context so the fight state machine takes over.
 */
@Slf4j
public class ReturnToSireAction implements SireAction {

    private static final String HOUSE_TABLET = "Teleport to House";
    private static final String POOL_NAME = "Fancy pool of Rejuvenation";
    private static final String GE_PORTAL_NAME = "Grand Exchange Portal"; // POH portal set to the GE
    private static final String FAIRY_RING_NAME = "Fairy ring";
    private static final int DRAMEN_STAFF_ID = 772;
    private static final int MIN_FOOD_AFTER_HEAL = 3;
    private static final int DEFAULT_FOOD_HEAL = 20;
    private static final int STEP_TIMEOUT_MS = 20_000;

    @Override
    public int order() {
        return 800; // after LootAction (700)
    }

    @Override
    public String key() {
        return "return-to-sire";
    }

    @Override
    public boolean needsExecution(SireState state) {
        return state.context().isPrepPending();
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();
        try {
            if (runTrip(ctx)) {
                log.info("[restock] back at the Sire — ready for the next kill");
                return "restock-complete";
            }
            log.error("[restock] trip aborted; the bot is now idle. Check the step above.");
            return "restock-aborted";
        } catch (Exception ex) {
            log.error("[restock] trip threw; aborting.", ex);
            return "restock-error";
        } finally {
            ctx.setPrepPending(false);
            ctx.reset(); // fresh fight; the bootstrap re-seeds phase 1 once the Sire is found
        }
    }

    private boolean runTrip(SireContext ctx) {
        Rs2Prayer.disableAllPrayers();

        if (!teleportHome()) {
            return false;
        }

        if (needResupply()) {
            Rs2InventorySetup range = ctx.getRangeSetup();
            if (range == null) {
                log.warn("[restock] no range inventory setup configured");
                return false;
            }
            log.info("[restock] food low — eating down, then wearing range gear and resupplying at the GE");
            // Eat carried food FIRST: it heals and frees inventory slots so the gear switch has room.
            eatCarriedToFull();
            range.wearEquipment(); // wear the range armour
            sleepUntil(range::doesEquipmentMatch, 3_000);
            if (!pohPortalToGe() || !resupply(range) || !teleportHome()) {
                return false;
            }
        }

        return restoreAtPool() && fairyRingBack() && walkToSpot();
    }

    // ---- Steps ----

    private boolean teleportHome() {
        if (PohTeleports.isInHouse()) {
            return true;
        }
        if (!Rs2Inventory.hasItem(HOUSE_TABLET)) {
            log.warn("[restock] no '{}' tablet in the inventory", HOUSE_TABLET);
            return false;
        }
        log.info("[restock] teleporting to the house");
        Rs2Inventory.interact(HOUSE_TABLET, "Break");
        if (!sleepUntil(PohTeleports::isInHouse, STEP_TIMEOUT_MS)) {
            log.warn("[restock] did not arrive in the house");
            return false;
        }
        sleep(600, 1000);
        return true;
    }

    /** True if, after eating to full, we'd have fewer than {@value #MIN_FOOD_AFTER_HEAL} food left. */
    private boolean needResupply() {
        int missingHp = Math.max(0,
                Rs2Player.getRealSkillLevel(Skill.HITPOINTS) - Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS));
        List<Rs2ItemModel> food = Rs2Inventory.getInventoryFood();
        int eaten = 0;
        int toHeal = missingHp;
        for (Rs2ItemModel item : food) {
            if (toHeal <= 0) {
                break;
            }
            toHeal -= healOf(item);
            eaten++;
        }
        int remaining = food.size() - eaten;
        log.info("[restock] food check — {} food, ~{} to heal {}hp, {} would remain (need >= {})",
                food.size(), eaten, missingHp, remaining, MIN_FOOD_AFTER_HEAL);
        return remaining < MIN_FOOD_AFTER_HEAL;
    }

    private int healOf(Rs2ItemModel item) {
        for (Rs2Food f : Rs2Food.values()) {
            if (f.getId() == item.getId()) {
                return f.getHeal();
            }
        }
        return DEFAULT_FOOD_HEAL;
    }

    private boolean isHpFull() {
        return Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS) >= Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
    }

    /** Eat carried food until HP is full or we run out — heals and frees inventory slots for the switch. */
    private void eatCarriedToFull() {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
        while (!isHpFull()
                && !Rs2Inventory.getInventoryFood().isEmpty()
                && System.currentTimeMillis() < deadline) {
            Rs2Inventory.interact(Rs2Inventory.getInventoryFood().get(0), "Eat");
            sleep(1_200, 1_600);
        }
    }

    /** The setup's food item id (first inventory item matching a known {@link Rs2Food}), or -1. */
    private int setupFoodId(Rs2InventorySetup setup) {
        for (InventorySetupsItem item : setup.getInventoryItems()) {
            if (item == null || item.getId() <= 0) {
                continue;
            }
            for (Rs2Food f : Rs2Food.values()) {
                if (f.getId() == item.getId()) {
                    return item.getId();
                }
            }
        }
        return -1;
    }

    /** POH portal set to the Grand Exchange (found by name; its id varies with the portal frame). */
    private boolean pohPortalToGe() {
        log.info("[restock] POH portal -> Grand Exchange");
        if (!interactObject(GE_PORTAL_NAME, null)) {
            log.warn("[restock] '{}' not found (is a POH portal set to the GE?)", GE_PORTAL_NAME);
            return false;
        }
        if (!sleepUntil(() -> Rs2Player.getWorldLocation()
                .distanceTo(BankLocation.GRAND_EXCHANGE.getWorldPoint()) < 20, STEP_TIMEOUT_MS)) {
            log.warn("[restock] did not arrive at the Grand Exchange");
            return false;
        }
        sleep(600, 1000);
        return true;
    }

    /** Bank at the Grand Exchange and top the RANGE setup back up (mirrors the Zulrah restock). */
    private boolean resupply(Rs2InventorySetup range) {
        Rs2Bank.walkToBankAndUseBank(BankLocation.GRAND_EXCHANGE);
        if (!sleepUntil(Rs2Bank::isOpen, STEP_TIMEOUT_MS)) {
            log.warn("[restock] could not open the Grand Exchange bank");
            return false;
        }

        // Equip the range gear from the inventory; fall back to the bank for anything genuinely missing.
        range.wearEquipment();
        sleepUntil(range::doesEquipmentMatch, 3_000);
        if (!range.doesEquipmentMatch()) {
            range.loadEquipment();
        }
        sleepUntil(range::doesEquipmentMatch, STEP_TIMEOUT_MS);

        // Desired inventory (id -> quantity) from the setup, skipping the rune pouch (handled by the game).
        Map<Integer, Integer> desired = new LinkedHashMap<>();
        for (InventorySetupsItem item : range.getInventoryItems()) {
            if (item == null || item.getId() <= 0 || isRunePouch(item.getName())) {
                continue;
            }
            desired.merge(item.getId(), Math.max(1, item.getQuantity()), Integer::sum);
        }
        // Deposit loot / part-used items; keep the rune pouch and everything that belongs in the setup.
        Rs2Bank.depositAllExcept(item -> item != null
                && (isRunePouch(item.getName()) || desired.containsKey(item.getId())));
        sleep(400, 800);

        // Eat to full using the setup's food from the bank (like the Zulrah restock), then deposit all
        // food so the top-up loop below refills the carried food to exactly the setup target.
        int foodId = setupFoodId(range);
        if (foodId > 0 && !isHpFull()) {
            log.info("[restock] HP below full — eating to full at the bank");
            Rs2Bank.withdrawX(foodId, 8);
            sleepUntil(() -> Rs2Inventory.hasItem(foodId), 3_000);
            long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
            while (!isHpFull() && Rs2Inventory.hasItem(foodId) && System.currentTimeMillis() < deadline) {
                Rs2Inventory.interact(foodId, "Eat");
                sleep(1_200, 1_600);
            }
            Rs2Bank.depositAll(foodId);
            sleep(300, 600);
        }

        // Top each setup item up to its target quantity.
        for (Map.Entry<Integer, Integer> want : desired.entrySet()) {
            if (!Rs2Bank.withdrawDeficit(want.getKey(), want.getValue())) {
                log.warn("[restock] could not top up item id {} to {}", want.getKey(), want.getValue());
            }
            Rs2Inventory.waitForInventoryChanges(1800);
        }
        Rs2Bank.closeBank();

        if (!range.doesEquipmentMatch()) {
            log.warn("[restock] range gear not fully on after restock");
            return false;
        }
        for (Integer id : desired.keySet()) {
            if (!Rs2Inventory.hasItem(id)) {
                log.warn("[restock] restock incomplete — missing item id {} (not enough in the bank?)", id);
                return false;
            }
        }
        return true;
    }

    private boolean restoreAtPool() {
        // The Fancy pool of Rejuvenation restores prayer/spec/run energy but NOT hitpoints (HP is topped
        // up by eating during the fight), so we only wait on prayer here.
        if (isPrayerFull()) {
            return true;
        }
        log.info("[restock] restoring prayer at the pool of Rejuvenation");
        if (!interactObject(POOL_NAME, null)) {
            return false;
        }
        sleepUntil(this::isPrayerFull, STEP_TIMEOUT_MS);
        return true;
    }

    private boolean isPrayerFull() {
        return Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER);
    }

    private boolean fairyRingBack() {
        log.info("[restock] fairy ring (last-destination = DIP) back to the Sire");
        if (Rs2Inventory.hasItem(DRAMEN_STAFF_ID)) {
            Rs2Inventory.wield(DRAMEN_STAFF_ID);
            sleep(300, 600);
        }
        if (!interactObject(FAIRY_RING_NAME, "Last-destination")) {
            log.warn("[restock] POH fairy ring not usable (is DIP the last code?)");
            return false;
        }
        if (!sleepUntil(() -> !PohTeleports.isInHouse(), STEP_TIMEOUT_MS)) {
            log.warn("[restock] fairy ring didn't fire (still in the house)");
            return false;
        }
        sleepUntil(() -> !Rs2Player.isAnimating(), STEP_TIMEOUT_MS);
        sleep(600, 1000);
        return true;
    }

    private boolean walkToSpot() {
        log.info("[restock] walking to the barrage spot (regular walker)");
        Rs2Walker.walkTo(SireConstants.ORIGINAL_POSITION);
        return sleepUntil(
                () -> Rs2Player.getWorldLocation().distanceTo(SireConstants.ORIGINAL_POSITION) <= 3,
                STEP_TIMEOUT_MS);
    }

    // ---- helpers ----

    private boolean interactObject(String name, String action) {
        Rs2TileObjectModel obj = Microbot.getRs2TileObjectCache()
                .query().withName(name).nearestOnClientThread();
        if (obj == null) {
            log.warn("[restock] object '{}' not found nearby", name);
            return false;
        }
        if (!Rs2Camera.isTileOnScreen(obj)) {
            Rs2Camera.turnTo(obj);
        }
        return action == null ? obj.click() : obj.click(action);
    }

    private static boolean isRunePouch(String name) {
        return name != null && name.toLowerCase().contains("rune pouch");
    }
}

package net.runelite.client.plugins.custom.dashingkebbit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.poh.PohTeleports;
import net.runelite.client.plugins.microbot.util.poh.data.JewelleryBox;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.shared.LocationService;

import java.util.concurrent.TimeUnit;

/**
 * Hunts Dashing kebbits at the Piscatoris Falconry for their fur (feeds golem crafting). Deliberately
 * kept simple: one blocking decision per scheduler pass, driven by {@code sleepUntil}, instead of an
 * action/tick pipeline.
 *
 * <p>On arrival in the pen we first walk to Matthias ({@link #MATTHIAS_POINT}) and Quick-falcon to rent a
 * falcon, then hunt. The falcon is lost whenever we leave the pen, so we re-rent after every bank trip.
 *
 * <p>Hunt loop, in priority order:
 * <ol>
 *   <li>a caught falcon is on the ground ({@link #GYR_FALCON_ID}) -> retrieve it (collects the fur/meat
 *       into the pouches and drops a Bones into the inventory),</li>
 *   <li>the inventory is full of Bones -> drop them all,</li>
 *   <li>otherwise throw the falcon at the nearest Dashing kebbit ({@link #DASHING_KEBBIT_ID}).</li>
 * </ol>
 * If throwing finds no "Catch" option we've lost our falcon, so we flag for a re-rent from Matthias.
 *
 * <p>When the inventory is full with no Bones left to drop, the fur/meat pouches have overflowed, so we
 * bank: Teleport to House -> jewellery box to Castle Wars -> deposit the loot and empty both pouches ->
 * re-equip the Dramen staff -> the loop then travels back to the pen and resumes.
 */
@Slf4j
public class DashingKebbitScript extends Script {

    // --- NPC ids (verified live) ---
    private static final int DASHING_KEBBIT_ID = 5533;
    private static final int GYR_FALCON_ID = 1343;   // your thrown falcon, sitting on its catch
    private static final int MATTHIAS_ID = 1341;     // the falconer you rent from

    // --- Item ids (open pouches carried while hunting) ---
    private static final int FUR_POUCH_OPEN = 29470;
    private static final int MEAT_POUCH_OPEN = 29464;
    private static final String DRAMEN_STAFF = "Dramen staff";

    // --- Menu actions ---
    private static final String ACTION_CATCH = "Catch";
    private static final String ACTION_RETRIEVE = "Retrieve";
    private static final String ACTION_QUICK_FALCON = "Quick-falcon";
    private static final String ACTION_EMPTY = "Empty";

    // --- Tiles ---
    private static final WorldPoint CATCH_CENTER = new WorldPoint(2371, 3577, 0);
    /** Stand next to Matthias here to rent a falcon before catching. */
    private static final WorldPoint MATTHIAS_POINT = new WorldPoint(2374, 3606, 0);

    // LocationService is a shared @RequiredArgsConstructor/@Singleton whose only dependency (the
    // tile-object cache) is a final @Inject field; Lombok leaves the generated constructor un-annotated,
    // so Guice can't build it directly. Build it lazily from Microbot.getRs2TileObjectCache() instead.
    private LocationService locationService;

    private LocationService locationService() {
        if (locationService == null) {
            locationService = new LocationService(Microbot.getRs2TileObjectCache());
        }
        return locationService;
    }

    @Getter
    private int catches = 0;
    @Getter
    private String status = "Starting";

    /** Consecutive throws that found no "Catch" option -> we probably have no falcon. */
    private int noCatchStreak = 0;

    /** True once we've rented a falcon for the current visit. Reset whenever we leave the pen (the falcon
     *  is lost on teleport), so we always rent from Matthias again before catching. */
    private boolean falconReady = false;

    public boolean run(DashingKebbitConfig config) {
        catches = 0;
        noCatchStreak = 0;
        falconReady = false;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run() || !Microbot.isLoggedIn()) {
                    return;
                }

                // Pouches overflowed (inventory full, no bones left to drop) -> bank run.
                if (needsBanking()) {
                    falconReady = false;
                    bankFursAndMeat();
                    return;
                }

                if (!inFalconryArea()) {
                    falconReady = false; // falcon is lost the moment we leave the pen
                    if (config.travelToFalconry()) {
                        rentFalcon();
                        travelToFalconry();
                    } else {
                        setStatus("Not in the falconry area");
                    }
                    return;
                }

                // Always rent from Matthias first: walk to his spot, Quick-falcon, then catch.
                if (config.rentFalcon() && !falconReady) {
                    rentFalcon();
                    return;
                }

                hunt(config);
            } catch (Exception ex) {
                log.error("[dashingkebbit] error during loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private void hunt(DashingKebbitConfig config) {
        // 1. Collect a caught falcon first (this is what actually banks the fur/meat and yields a Bones).
        Rs2NpcModel falcon = Rs2Npc.getNpc(GYR_FALCON_ID);
        if (falcon != null && isNear(falcon, 12)) {
            setStatus("Retrieving falcon");
            if (Rs2Npc.interact(falcon, ACTION_RETRIEVE)) {
                noCatchStreak = 0;
                catches++;
                sleepUntil(() -> !isRetrievableFalconPresent(), 4000);
            }
            return;
        }

        // 2. Inventory full of Bones -> dump them and keep hunting.
        if (Rs2Inventory.isFull()) {
            setStatus("Dropping bones");
            Rs2Inventory.dropAll(i -> i.getName() != null && i.getName().equalsIgnoreCase("Bones"));
            sleepUntil(() -> !Rs2Inventory.isFull(), 5000);
            return;
        }

        // 3. Throw the falcon at the nearest Dashing kebbit.
        Rs2NpcModel kebbit = Rs2Npc.getNpc(DASHING_KEBBIT_ID);
        if (kebbit == null) {
            setStatus("Waiting for a Dashing kebbit");
            return;
        }

        setStatus("Catching Dashing kebbit");
        if (Rs2Npc.interact(kebbit, ACTION_CATCH)) {
            noCatchStreak = 0;
            // Throw resolves within a couple ticks: either we start the throw animation or the falcon
            // lands on its catch. Wait briefly so we don't spam clicks while the falcon is in flight.
            sleepUntil(() -> Rs2Player.isAnimating() || isRetrievableFalconPresent(), 3000);
        } else {
            // No "Catch" option means we have no falcon out to throw. A brief streak avoids a false
            // trigger from a kebbit that momentarily wasn't catchable; then flag for a re-rent.
            noCatchStreak++;
            if (config.rentFalcon() && noCatchStreak >= 3) {
                falconReady = false;
                noCatchStreak = 0;
            }
        }
    }

    /**
     * Walks to the spot next to Matthias ({@link #MATTHIAS_POINT}) and rents a falcon via Quick-falcon
     * (500 coins), clearing the confirmation dialogue. Sets {@link #falconReady} so we move on to catching.
     */
    private void rentFalcon() {
        setStatus("Getting a falcon from Matthias");
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null || me.distanceTo(MATTHIAS_POINT) > 4) {
            Rs2Walker.walkTo(MATTHIAS_POINT, 2);
            sleepUntil(() -> {
                WorldPoint p = Rs2Player.getWorldLocation();
                return p != null && p.distanceTo(MATTHIAS_POINT) <= 4;
            }, 10000);
        }

        Rs2NpcModel matthias = Rs2Npc.getNpc(MATTHIAS_ID);
        if (matthias == null) {
            return;
        }
        if (Rs2Npc.interact(matthias, ACTION_QUICK_FALCON)) {
            // Quick-falcon shows a short "the falconer gives you a glove..." continue dialogue.
            sleepUntil(Rs2Dialogue::isInDialogue, 4000);
            while (Rs2Dialogue.isInDialogue()) {
                Rs2Dialogue.clickContinue();
                sleep(600, 1000);
            }
            falconReady = true;

            // Matthias stands north of the pen; run down to the catch spot before we start throwing.
            setStatus("Walking to the catch spot");
            Rs2Walker.walkTo(CATCH_CENTER, 4);
            sleepUntil(() -> {
                WorldPoint p = Rs2Player.getWorldLocation();
                return p != null && p.distanceTo(CATCH_CENTER) <= 6;
            }, 10000);
        }
    }

    /** Teleport to House -> fairy ring (AKS) -> stile -> pen. The walker handles the ring code and the stile. */
    private void travelToFalconry() {
        if (nearFalconry()) {
            setStatus("Walking to the Matthias");
            Rs2Walker.walkTo(CATCH_CENTER, 5);
            sleepUntil(this::inFalconryArea, 15000);
            return;
        }
        if (!PohTeleports.isInHouse()) {
            setStatus("Teleporting to house");
            locationService().teleportToHouse();
        }
        setStatus("Fairy ring to Piscatoris");
        // From inside the POH the walker configures the fairy ring (AKS) and paths across the stile.
        Rs2Walker.walkTo(CATCH_CENTER, 5);
        sleepUntil(() -> nearFalconry() || inFalconryArea(), 20000);
    }

    // --- Banking ---

    /** Time to bank: inventory full and no Bones left to drop, i.e. the fur/meat pouches have overflowed. */
    private boolean needsBanking() {
        return Rs2Inventory.isFull() && Rs2Inventory.count("Bones") == 0;
    }

    /**
     * Teleport to House -> jewellery box to Castle Wars -> deposit the loot, empty both pouches, keep the
     * essentials, re-equip the Dramen staff. One blocking pass; if a step isn't ready yet it returns and
     * the next scheduler pass continues from where we are.
     */
    private void bankFursAndMeat() {
        // 1. Get to the Castle Wars bank via the POH jewellery box.
        if (!Rs2Bank.isOpen() && !isAtCastleWars()) {
            if (!PohTeleports.isInHouse()) {
                setStatus("Teleporting to house to bank");
                locationService().teleportToHouse();
                return;
            }
            setStatus("Jewellery box -> Castle Wars");
            JewelleryBox.CASTLE_WARS.execute();
            sleepUntil(() -> !PohTeleports.isInHouse(), 8000);
            return;
        }

        // 2. Open the bank.
        if (!Rs2Bank.isOpen()) {
            setStatus("Opening the bank");
            Rs2Bank.walkToBankAndUseBank(BankLocation.CASTLE_WARS);
            sleepUntil(Rs2Bank::isOpen, 8000);
            return;
        }

        // 3. Deposit the loot and empty both pouches into the bank; keep the pouches, coins and staff.
        setStatus("Banking furs & meat");
        Rs2Bank.depositAllExcept("pouch", "Coins", DRAMEN_STAFF);
        sleep(400, 700);
        Rs2Inventory.interact(FUR_POUCH_OPEN, ACTION_EMPTY);
        sleep(600, 900);
        Rs2Inventory.interact(MEAT_POUCH_OPEN, ACTION_EMPTY);
        sleep(600, 900);
        Rs2Bank.depositAllExcept("pouch", "Coins", DRAMEN_STAFF);
        sleep(400, 700);
        Rs2Bank.closeBank();

        // 4. Make sure the Dramen staff is equipped for the fairy ring on the way back.
        if (!Rs2Equipment.isWearing(DRAMEN_STAFF, false) && Rs2Inventory.contains(DRAMEN_STAFF, false)) {
            Rs2Inventory.wield(DRAMEN_STAFF);
        }
    }

    // --- Location helpers ---

    private boolean inFalconryArea() {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getPlane() == 0 && p.distanceTo(CATCH_CENTER) <= 30;
    }

    private boolean isAtCastleWars() {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getPlane() == 0
                && p.distanceTo(BankLocation.CASTLE_WARS.getWorldPoint()) <= 15;
    }

    /** Broad Piscatoris Falconry landmass check, so we don't re-teleport home once we've landed there. */
    private boolean nearFalconry() {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getPlane() == 0
                && p.getX() >= 2300 && p.getX() <= 2400
                && p.getY() >= 3555 && p.getY() <= 3640;
    }

    private boolean isRetrievableFalconPresent() {
        Rs2NpcModel falcon = Rs2Npc.getNpc(GYR_FALCON_ID);
        return falcon != null && isNear(falcon, 12);
    }

    private boolean isNear(Rs2NpcModel npc, int tiles) {
        WorldPoint me = Rs2Player.getWorldLocation();
        WorldPoint them = npc.getWorldLocation();
        return me != null && them != null && me.distanceTo(them) <= tiles;
    }

    private void setStatus(String s) {
        this.status = s;
        Microbot.status = s;
    }
}

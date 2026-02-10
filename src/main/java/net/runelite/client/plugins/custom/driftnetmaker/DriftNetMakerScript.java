package net.runelite.client.plugins.custom.driftnetmaker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.util.AbstractScript;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity.MODERATE;

@Slf4j
public class DriftNetMakerScript extends AbstractScript {

    //private static final WorldPoint IN_FRONT_OF_LOOM = new WorldPoint(1370, 3361, 0); //AUBURN
    private static final WorldPoint IN_FRONT_OF_LOOM = new WorldPoint(3731, 3822, 0);

    static {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.activateAntiban();
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyCraftingSetup();

        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.simulateFatigue = true;
        Rs2AntibanSettings.simulateAttentionSpan = true;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.nonLinearIntervals = true;
        Rs2AntibanSettings.dynamicActivity = true;
        Rs2AntibanSettings.profileSwitching = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.moveMouseOffScreen = true;
        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.04;
        Rs2Antiban.setActivityIntensity(MODERATE);
    }

    private final AtomicBoolean isCreatingDriftNets = new AtomicBoolean(false);

    @Override
    public void tick() {
        State state = getState();
        log.info("State: {}", state);
        switch (state) {
            case BANKING:
                handleBanking();
                break;
            case GOING_TO_LOOM:
                handleGoingToLoom();
                break;
            case INTERACTING_WITH_LOOM:
                handleInteractingWithLoom();
                break;
            default:
                break;
        }
    }

    @Override
    public void shutdown() {
        log.info("Shutting down.");
        super.shutdown();
        if (this.mainScheduledFuture != null) {
            mainScheduledFuture.cancel(true);
            mainScheduledFuture = null;
        }
    }

    @Override
    public int getTickDelay() {
        return 600;
    }

    private State getState() {
        if (!Rs2Inventory.contains("Jute fibre") && !Rs2Player.isMoving()) {
            isCreatingDriftNets.set(false);
            return State.BANKING;
        } else if (isCreatingDriftNets.get() && Rs2Inventory.contains("Jute fibre") && !Rs2Player.isMoving() && !Rs2Player.isAnimating()) {
            isCreatingDriftNets.set(false);
            return State.INTERACTING_WITH_LOOM;
        } else if (isCreatingDriftNets.get()) {
            return State.CREATING;
        } else if (Rs2Inventory.contains("Jute fibre")) {
            return State.INTERACTING_WITH_LOOM;
        } else {
            return null;
            //return State.GOING_TO_LOOM;
        }
    }

    private void handleBanking() {
        log.info("Going to bank");
        //Rs2Bank.walkToBank(BankLocation.AUBURNVALE);
        //Rs2Bank.walkToBank(BankLocation.FOSSIL_ISLAND);
        //sleepUntil(() -> !Rs2Player.isMoving());
        Rs2Bank.openBank();
        sleepUntil(Rs2Bank::isOpen);
        Rs2Bank.depositAll();
        Rs2Bank.withdrawAll("Jute fibre");
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        sleep(1000, 1500);
    }

    private void handleGoingToLoom() {
        log.info("Going to loom");
        Rs2Walker.walkTo(IN_FRONT_OF_LOOM);
        sleepUntil(() -> Rs2Player.distanceTo(IN_FRONT_OF_LOOM) < 5);
    }

    private void handleInteractingWithLoom() {
        log.info("Creating drift nets");
        isCreatingDriftNets.set(true);

        Rs2TileObjectModel loom = rs2TileObjectCache
                .query()
                .withName("Loom")
                .nearestOnClientThread(40);

        Microbot.getClientThread().invoke(() -> loom.click("Weave"));
        sleepUntil(() -> Rs2Player.distanceTo(IN_FRONT_OF_LOOM) < 2);
        sleep(3500, 4500);
        Rs2Keyboard.keyPress('2');
        sleep(1800, 2400);
    }

    private enum State {
        BANKING,
        GOING_TO_LOOM,
        INTERACTING_WITH_LOOM,
        CREATING
    }

}

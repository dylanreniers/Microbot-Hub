package net.runelite.client.plugins.custom.gotr.services;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.client.plugins.custom.gotr.GotrConstants;
import net.runelite.client.plugins.custom.gotr.GotrState;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Inject;
import javax.inject.Singleton;

import static net.runelite.client.plugins.microbot.Microbot.log;

/**
 * Service for handling mining operations in GOTR
 */
@Singleton
public class MiningService {

    private final LocationService locationService;
    private final TimerService timerService;
    private final PouchService pouchService;

    // State management methods for plugin integration
    @Getter
    @Setter
    private NPC greatGuardian;

    @Inject
    public MiningService(LocationService locationService, TimerService timerService, PouchService pouchService) {
        this.locationService = locationService;
        this.timerService = timerService;
        this.pouchService = pouchService;
    }

    /**
     * Mines huge guardian remains in portal areas
     *
     * @return true if mining operation was performed or in progress
     */
    public boolean mineHugeGuardianRemains() {
        if (!locationService.isInHugeMine()) {
            return false;
        }

         /* if (isGuardianPowerDepleted()) {
            handlePowerDepletion();
            return false;
        } */

        if (Rs2Inventory.isFull()) {
            handleFullInventoryInHugeMine();
        } else {
            performHugeMining();
        }

        return true;
    }

    /**
     * Mines guardian remains based on agility level and current situation
     */
    public void mineGuardianRemains(GotrState currentState) {
        if (Microbot.getClient().hasHintArrow() || Rs2Inventory.isFull()) {
            return;
        }

        if (locationService.isInHugeMine()) {
            leaveHugeMine();
            return;
        }

        if (shouldUseLargeMine()) {
            mineLargeGuardianRemains();
        } else {
            mineGuardianParts();
        }
    }

    /**
     * Enters the large mine if conditions are met
     */
    public boolean enterLargeMine() {
        if (locationService.isInLargeMine()) {
            return false;
        }

        if (Rs2Walker.walkTo(GotrConstants.LARGE_MINE_ENTRANCE, 20)) {
            log("Traveling to large mine...");
            if (Rs2GameObject.interact(GotrConstants.RUBBLE_ENTER_ID)) {
                Rs2Player.waitForAnimation();
                Global.sleepUntil(locationService::isInLargeMine, 5000);

                if (locationService.isInLargeMine()) {
                    Global.sleep(Rs2Random.randomGaussian(Rs2Random.between(2000, 2400), Rs2Random.between(100, 300)));
                    log("Interacting with large guardian remains...");
                    Rs2GameObject.interact(GotrConstants.LARGE_GUARDIAN_REMAINS_ID);
                    Global.sleepGaussian(1200, 150);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Leaves the large mine
     */
    public boolean leaveLargeMine() {
        if (!locationService.isInLargeMine()) {
            return false;
        }

        if (Rs2GameObject.interact(GotrConstants.RUBBLE_EXIT_ID)) {
            Rs2Player.waitForAnimation();
            log("Leaving large mine...");
            return true;
        }
        return false;
    }

    /**
     * Leaves the huge mine (portal area)
     */
    public void leaveHugeMine() {
        if (Rs2GameObject.interact(GotrConstants.HUGE_MINE_EXIT_ID)) {
            log("Leave huge mine...");
            Global.sleepUntil(() -> !locationService.isInHugeMine(), 5000);
        }
    }

    private boolean isGuardianPowerDepleted() {
        // This would need to be implemented with the power checking logic
        // For now, returning a placeholder
        return false; // TODO: Implement power checking
    }

    private void handlePowerDepletion() {
        pouchService.repairPouches();
        leaveHugeMine();
    }

    private void handleFullInventoryInHugeMine() {
        if (Rs2Inventory.allPouchesFull()) {
            if (Rs2Inventory.hasItem(GotrConstants.GUARDIAN_STONE)) {
                log("Inventory and pouches full, leaving huge mine...");
            }
            leaveHugeMine();
        } else {
            Rs2Inventory.fillPouches();
            Global.sleep(Rs2Random.randomGaussian(Rs2Random.between(600, 1200), Rs2Random.between(100, 300)));

            if (!Rs2Inventory.isFull()) {
                performHugeMining();
            }
        }
    }

    private void performHugeMining() {
        Rs2GameObject.interact(GotrConstants.HUGE_GUARDIAN_REMAINS_ID);
        Rs2Player.waitForAnimation();
    }

    private boolean shouldUseLargeMine() {
        return Rs2Player.getSkillRequirement(Skill.AGILITY, GotrConstants.AGILITY_LEVEL_FOR_LARGE_MINE)
                && timerService.getTimeSincePortal() < GotrConstants.PORTAL_TIME_THRESHOLD
                && !Rs2Inventory.hasItem(GotrConstants.GUARDIAN_ESSENCE);
    }

    private void mineLargeGuardianRemains() {
        if (!locationService.isInLargeMine() && !locationService.isInHugeMine()) {
            if (!Rs2Inventory.hasItem(GotrConstants.GUARDIAN_FRAGMENTS) || timerService.getStartTimer() == -1) {
                enterLargeMine();
                return;
            }
        }

        if (locationService.isInLargeMine() && !Rs2Player.isAnimating() && timerService.getStartTimer() != -1) {
            prepareForMining();
            Rs2GameObject.interact(GotrConstants.LARGE_GUARDIAN_REMAINS_ID);
            Global.sleepGaussian(1200, 150);
        }
    }

    private void mineGuardianParts() {
        if (!Rs2Player.isAnimating() && timerService.getStartTimer() != -1) {
            if (locationService.isInLargeMine()) {
                leaveLargeMine();
                return;
            }

            prepareForMining();
            Rs2GameObject.interact(GotrConstants.GUARDIAN_PARTS_ID);
            Global.sleepGaussian(1200, 150);
        }
    }

    private void prepareForMining() {
        if (Rs2Equipment.isWearing("dragon pickaxe")) {
            Rs2Combat.setSpecState(true, 1000);
        }

        if (Rs2Random.between(1, 20) == 2) {
            pouchService.checkPouches();
        }

        pouchService.repairPouches();
    }

    public void resetState() {
        this.greatGuardian = null;
    }
}

package net.runelite.client.plugins.custom.microhunter.scripts;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.ItemID;
import net.runelite.api.ObjectID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.microhunter.AutoHunterConfig;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.util.AbstractScript;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class AutoChinScript extends AbstractScript {

    private static final List<Integer> BOXES_IDS = List.of(
            ObjectID.SHAKING_BOX_9384,
            ObjectID.SHAKING_BOX_9383,
            ObjectID.SHAKING_BOX_9382,
            ObjectID.SHAKING_BOX,
            ObjectID.BOX_TRAP_9385
    );
    public static final int[] BOX_ID_ARRAY = BOXES_IDS.stream().mapToInt(i -> i).toArray();
    private static final List<Integer> TRAP_IDS = Arrays.asList(
            ObjectID.BOX_TRAP_9380,
            ObjectID.BOX_TRAP_9385,
            ObjectID.SHAKING_BOX_9384,
            ObjectID.SHAKING_BOX_9383,
            ObjectID.SHAKING_BOX_9382,
            ObjectID.SHAKING_BOX
    );

    @Inject
    private AutoHunterConfig config;

    private final List<WorldPoint> boxTiles = new ArrayList<>();
    private final List<WorldPoint> allBoxesOriginalPoints = new ArrayList<>();
    private boolean pickedUpTraps = true;
    private boolean isExecutingTickManipulation;

    private final ArrayList<GameObject> gameObjects = new ArrayList<>();

    @Override
    public void tick() {
        if (isExecutingTickManipulation) {
            log.info("Executing tick manipulation");
            return;
        }

        if (!takingBreak()) {
            if (pickedUpTraps) {
                layTraps();
                pickedUpTraps = false;
            }

            switch (getState()) {
                case DROPPING:
                    handleDroppingState();
                    break;
                case CATCHING:
                    handleCatchingState();
                    break;
                case LAYING:
                    handleLayingState();
                    break;
                case TICK_MANIPULATION:
                    handleTickManipulationState();
                    break;
            }
        }
    }

    @Override
    public void initialize() {
        allBoxesOriginalPoints.clear();
        boxTiles.clear();
        int numberOfBoxes = Microbot.getClient().getRealSkillLevel(Skill.HUNTER) / 20 + 1;

        //square pattern
        WorldPoint firstBoxPoint = Rs2Player.getWorldLocation();
        log.info("First box point: {}", firstBoxPoint);

        WorldPoint secondBoxPoint = firstBoxPoint.dx(2);
        log.info("Second box point: {}", firstBoxPoint);

        WorldPoint thirdBoxPoint = secondBoxPoint.dy(2);
        log.info("Third box point: {}", firstBoxPoint);

        WorldPoint fourthBoxPoint = thirdBoxPoint.dx(-2);
        log.info("Fourth box point: {}", firstBoxPoint);

        allBoxesOriginalPoints.add(firstBoxPoint);
        allBoxesOriginalPoints.add(secondBoxPoint);
        allBoxesOriginalPoints.add(thirdBoxPoint);
        allBoxesOriginalPoints.add(fourthBoxPoint);

        if (numberOfBoxes == 5) {
            WorldPoint fifthBoxPoint = firstBoxPoint.dx(1).dy(1); //in the center of the boxes
            allBoxesOriginalPoints.add(fifthBoxPoint);
        }
    }

    @Override
    public void onException(Exception e) {
        Microbot.logStackTrace(this.getClass().getSimpleName(), e);
    }

    @Override
    public void shutdown() {
        pickUpBoxTraps();
        pickedUpTraps = true;
        super.shutdown();
    }

    public void onGameObjectSpawn(GameObject gameObject) {
        if (BOXES_IDS.contains(gameObject.getId())) {
            log.info("Box spawned.");
            gameObjects.add(gameObject);
        }
    }

    private State getState() {
        try {
            Rs2TileItemModel nearestCollapsedBoxTrap = rs2TileItemCache
                    .query()
                    .withId(ItemID.BOX_TRAP)
                    .where(box -> boxTiles.contains(box.getWorldLocation()))
                    .nearestOnClientThread();

            // If there are box traps on the floor, interact with them first
            if (nearestCollapsedBoxTrap != null) {
                return State.LAYING;
            }

            // If our inventory is full of ferrets
            if (Rs2Inventory.emptySlotCount() <= 1 && Rs2Inventory.contains(ItemID.FERRET)) {
                // ferrets have the option release and not drop
                return State.DROPPING;
            }

            for (Integer boxId : BOXES_IDS) {
                Rs2TileObjectModel nearestBoxTrap = rs2TileObjectCache
                        .query()
                        .withId(boxId)
                        .where(box -> boxTiles.contains(box.getWorldLocation()))
                        .nearestOnClientThread();
                if (nearestBoxTrap != null) {
                    if (hasKnifeAndLogs() && config.tickManipulation() && !isExecutingTickManipulation) {
                        return State.TICK_MANIPULATION;
                    }
                    return State.CATCHING;
                }
            }
        } catch (Exception ex) {
            Microbot.log(ex.getMessage());
        }

        return State.IDLE;
    }

    private void handleDroppingState() {
        while (Rs2Inventory.contains(ItemID.FERRET)) {
            Rs2Inventory.interact(ItemID.FERRET, "Release");
            sleep(0, 750);
            if (!Rs2Inventory.contains(ItemID.FERRET)) {
                break;
            }
        }
        sleep(config.minSleepAfterLay(), config.maxSleepAfterLay());
    }

    private void handleCatchingState() {
        if (!gameObjects.isEmpty()) {
            Rs2TileObjectModel nearestBoxTrap = new Rs2TileObjectModel(gameObjects.remove(0));
            if (nearestBoxTrap.click("reset")) {
                log.info("Resetting box trap.");
                sleep(config.minSleepAfterCatch(), config.maxSleepAfterCatch());
            }
        }
    }

    private void handleLayingState() {
        Rs2TileItemModel nearestCollapsedBoxTrap = rs2TileItemCache
                .query()
                .withId(ItemID.BOX_TRAP)
                .where(box -> boxTiles.contains(box.getWorldLocation()))
                .nearestOnClientThread();

        if (nearestCollapsedBoxTrap != null && nearestCollapsedBoxTrap.click("lay")) {
            sleep(config.minSleepAfterLay(), config.maxSleepAfterLay());
        }
    }

    private void layTraps() {
        allBoxesOriginalPoints.forEach(tile -> {
            int offset = 0;
            while (!boxSpotIsAvailable(tile.dx(offset))) {
                log.info("Spot already taken.");
                offset += 1;
            }

            WorldPoint boxTrapLocation = tile.dx(offset);
            log.info("Setting box trap at {}", boxTrapLocation);
            Rs2Walker.walkFastCanvas(boxTrapLocation, true);
            sleep(600, 2000);
            boxTiles.add(boxTrapLocation);
            Rs2Inventory.interact("Box trap", "Lay");
            sleep(2400, 3000);
        });
    }

    private boolean boxSpotIsAvailable(WorldPoint point) {
        Rs2TileObjectModel rs2TileObjectModel = rs2TileObjectCache
                .query()
                .where(object -> object.getWorldLocation().equals(point))
                .firstOnClientThread();
        if (rs2TileObjectModel != null) {
            log.info("Found object id: {}", rs2TileObjectModel.getId());
        }
        return rs2TileObjectModel == null || rs2TileObjectModel.isReachable();
    }

    private boolean takingBreak() {
        int secondsUntilBreak = BreakHandlerScript.breakIn;

        if (secondsUntilBreak > 0 && secondsUntilBreak <= 60) {
            if (!pickedUpTraps) {
                pickUpBoxTraps();
                boxTiles.clear();
                pickedUpTraps = true;
            }

            return true;
        }

        return secondsUntilBreak > 60;
    }

    private void pickUpBoxTraps() {
        TRAP_IDS.forEach(trapId -> rs2TileObjectCache
                .query()
                .withId(trapId)
                .where(object -> boxTiles.contains(object.getWorldLocation()))
                .toListOnClientThread()
                .forEach(trap -> {
                    WorldPoint location = trap.getWorldLocation();
                    if (Rs2Player.getWorldLocation().distanceTo(location) < 8) {
                        if (trap.click("Dismantle") || trap.click("Release")) {
                            sleep(1000, 3000);
                        }
                    }
                }));
    }

    private boolean hasKnifeAndLogs() {
        return Rs2Inventory.contains("Knife") && Rs2Inventory.contains("Teak logs");
    }

    private void handleTickManipulationState() {
        Rs2TileObjectModel nearestBoxTrap = rs2TileObjectCache
                .query()
                .withIds(BOXES_IDS.stream().mapToInt(i->i).toArray())
                .where(box -> boxTiles.contains(box.getWorldLocation()))
                .nearestOnClientThread();

        if (nearestBoxTrap != null) {

            Rs2Walker.walkFastCanvas(nearestBoxTrap.getWorldLocation(), true);
            sleepUntil(() -> Rs2Player.getWorldLocation().equals(nearestBoxTrap.getWorldLocation()));

            if (nearestBoxTrap.click("check")) {
                log.info("Checking first trap");
                Rs2Inventory.waitForInventoryChanges(2400);
                sleep(600, 1200);
            }

            if (Rs2Inventory.interact("Knife", "Use")) {
                sleep(50, 150);
                Rs2Inventory.interact("Teak logs");
                log.info("Using knife on logs");
                sleep(600);
                Rs2Walker.walkFastCanvas(nearestBoxTrap.getWorldLocation(), true);
                sleep(600);
                Rs2Inventory.interact("Box trap", "Lay");
                log.info("Laying box trap");

                Rs2TileObjectModel box = rs2TileObjectCache
                        .query()
                        .withIds(BOX_ID_ARRAY)
                        .where(potentialBox -> boxTiles.contains(potentialBox.getWorldLocation()))
                        .nearestOnClientThread();

                while (box != null) {
                    sleep(1800);
                    log.info("Walking to next trap");
                    Rs2Walker.walkFastCanvas(box.getWorldLocation(), true);
                    sleep(600);
                    log.info("Checking trap");
                    box.click("check");
                    Rs2Inventory.waitForInventoryChanges(2400);
                    log.info("Laying new trap");
                    Rs2Inventory.interact("Box trap", "Lay");
                    log.info("Sleeping to lay new box for 1800ms");
                    box = rs2TileObjectCache
                            .query()
                            .withIds(BOX_ID_ARRAY)
                            .where(potentialBox -> boxTiles.contains(potentialBox.getWorldLocation()))
                            .nearestOnClientThread();
                    log.info("Has new box? {}", box != null);

                }
            }
        }

        isExecutingTickManipulation = false;
    }

    private enum State {
        IDLE,
        CATCHING,
        DROPPING,
        LAYING,
        TICK_MANIPULATION
    }
}

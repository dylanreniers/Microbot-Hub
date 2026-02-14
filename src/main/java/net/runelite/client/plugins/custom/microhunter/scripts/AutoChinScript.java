package net.runelite.client.plugins.custom.microhunter.scripts;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.ItemID;
import net.runelite.api.ObjectID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.plugins.custom.microhunter.AutoHunterConfig;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.util.AbstractScript;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity.EXTREME;

@Slf4j
public class AutoChinScript extends AbstractScript {

    private static final List<Integer> BOXES_IDS = List.of(
            ObjectID.SHAKING_BOX_9384,
            ObjectID.SHAKING_BOX_9383,
            ObjectID.SHAKING_BOX_9382,
            ObjectID.SHAKING_BOX,
            ObjectID.BOX_TRAP_9385
    );

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

    private boolean pickedUpTraps = true;

    private List<WorldPoint> boxTiles;
    private List<WorldPoint> allBoxesOriginalPoints;

    private ArrayList<GameObject> triggeredTraps;
    private ArrayList<ItemSpawned> boxesOnFloor;
    private AtomicBoolean hasTrapBeenLaid;
    private boolean initiated;
    private double timeSinceLastReset;

    @Override
    public void tick() {
        if (!takingBreak() && initiated) {
            var state = getState();
            log.info("State: {}", state);
            switch (state) {
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
                case RESET:
                    pickUpBoxTraps();
                    layTraps();
                    timeSinceLastReset = System.currentTimeMillis();
                    break;
            }

        }
    }

    @Override
    public void initialize() {
        boxTiles = new ArrayList<>();
        allBoxesOriginalPoints = new ArrayList<>();
        triggeredTraps = new ArrayList<>();
        boxesOnFloor = new ArrayList<>();

        hasTrapBeenLaid = new AtomicBoolean(false);

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

        layTraps();

        if (config.tickManipulation()) {
            Rs2Antiban.setActivityIntensity(EXTREME);
        }

        timeSinceLastReset = System.currentTimeMillis();
    }

    @Override
    public void onException(Exception e) {
        Microbot.logStackTrace(this.getClass().getSimpleName(), e);
    }

    @Override
    public void shutdown() {
        if (Microbot.isLoggedIn()) {
            pickUpBoxTraps();
        }
        initiated = false;
        pickedUpTraps = true;
        super.shutdown();
    }

    @Override
    public int getTickDelay() {
        return 600;
    }

    public void onGameObjectSpawn(GameObject gameObject) {
        if (initiated && BOXES_IDS.contains(gameObject.getId())) {
            log.info("Box spawned.");
            triggeredTraps.add(gameObject);
        } else if (gameObject.getId() == ObjectID.BOX_TRAP_9380) {
            log.info("Trap has been laid");
            hasTrapBeenLaid.set(true);
        }
    }

    public void onItemSpawned(ItemSpawned itemSpawned) {
        if (initiated && itemSpawned.getItem().getId() == ItemID.BOX_TRAP && hasTrapBeenLaid.get()) {
            boxesOnFloor.add(itemSpawned);
        }
    }

    public void onItemDespawned(ItemDespawned itemDespawned) {
        if (initiated && itemDespawned.getItem().getId() == ItemID.BOX_TRAP) {
            var matchingBox = boxesOnFloor.stream().filter(event -> event.getTile().equals(itemDespawned.getTile())).findFirst();
            matchingBox.ifPresent(itemSpawned -> boxesOnFloor.remove(itemSpawned));
        }
    }

    private State getState() {
        try {
            if (timeSinceLastReset + 3600000 < System.currentTimeMillis()) {
                return State.RESET;
            }

            if (!boxesOnFloor.isEmpty()) {
                log.info("boxes on floor not empty");
                return State.LAYING;
            }

            if (Rs2Inventory.emptySlotCount() <= 1 && Rs2Inventory.contains(ItemID.FERRET)) {
                return State.DROPPING;
            }

            if (!triggeredTraps.isEmpty()) {
                log.info("Triggered traps not empty.");
                if (hasKnifeAndLogs() && config.tickManipulation()) {
                    return State.TICK_MANIPULATION;
                }
                return State.CATCHING;
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
        }
        sleep(config.minSleepAfterLay(), config.maxSleepAfterLay());
    }

    private void handleCatchingState() {
        if (!triggeredTraps.isEmpty()) {
            Rs2TileObjectModel nearestBoxTrap = new Rs2TileObjectModel(triggeredTraps.remove(0));
            if (nearestBoxTrap.click("reset")) {
                log.info("Resetting box trap.");
                sleep(config.minSleepAfterCatch(), config.maxSleepAfterCatch());
            }
        }
    }

    private void handleLayingState() {
        if (!boxesOnFloor.isEmpty()) {
            ItemSpawned boxOnFloor = boxesOnFloor.remove(0);
            Rs2TileItemModel nearestCollapsedBoxTrap = new Rs2TileItemModel(boxOnFloor.getTile(), boxOnFloor.getItem());
            if (nearestCollapsedBoxTrap.click("lay")) {
                sleep(config.minSleepAfterLay(), config.maxSleepAfterLay());
            }
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
            sleepUntil(hasTrapBeenLaid::get);
            hasTrapBeenLaid.set(false);
        });

        initiated = true;
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
                            Rs2Inventory.waitForInventoryChanges(8000);
                        }
                    }
                }));
    }

    private boolean hasKnifeAndLogs() {
        return Rs2Inventory.contains("Knife") && Rs2Inventory.contains("Teak logs");
    }

    private void handleTickManipulationState() {
        Rs2TileObjectModel firstBoxTrap = new Rs2TileObjectModel(triggeredTraps.remove(0));

        Rs2Walker.walkFastCanvas(firstBoxTrap.getWorldLocation(), true);
        sleepUntil(() -> Rs2Player.getWorldLocation().equals(firstBoxTrap.getWorldLocation()));

        if (firstBoxTrap.click("check")) {
            log.info("Checking first trap");
            Rs2Inventory.waitForInventoryChanges(3000);
        }

        Rs2Inventory.interact("Knife", "Use");
        sleep(50, 150);
        Rs2Inventory.interact("Teak logs");
        log.info("Using knife on logs");
        sleepUntil(Rs2Player::isAnimating);
        layFirstTrap(firstBoxTrap);

        while (!triggeredTraps.isEmpty()) {
            var box = new Rs2TileObjectModel(triggeredTraps.remove(0));
            layTrap(box, false);
            log.info("Has new box? {}", !triggeredTraps.isEmpty());
        }
    }

    private void layFirstTrap(Rs2TileObjectModel nearestBoxTrap) {
        layTrap(nearestBoxTrap, true);
    }

    private void layTrap(Rs2TileObjectModel box, boolean isFirst) {
        log.info("Walking to next trap");
        Rs2Walker.walkFastCanvas(box.getWorldLocation(), true);
        sleepUntil(() -> Rs2Player.getWorldLocation().equals(box.getWorldLocation()));
        if (!isFirst) {
            log.info("Checking trap");
            box.click("check");
            Rs2Inventory.waitForInventoryChanges(2400);
        }
        log.info("Laying new trap");
        Rs2Inventory.interact("Box trap", "Lay");
        log.info("Sleeping until trap has been laid");
        double time = System.currentTimeMillis();
        hasTrapBeenLaid.set(false);
        sleepUntil(hasTrapBeenLaid::get);
        log.info("Take taken to lay trap: {}", System.currentTimeMillis() - time);
    }

    private enum State {
        IDLE,
        CATCHING,
        DROPPING,
        LAYING,
        TICK_MANIPULATION,
        RESET
    }
}

package net.runelite.client.plugins.microbot.custom.barrows;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.custom.barrows.services.BankService;
import net.runelite.client.plugins.microbot.custom.barrows.services.PuzzleSolverService;
import net.runelite.client.plugins.microbot.custom.barrows.services.TileObjectService;
import net.runelite.client.plugins.microbot.custom.barrows.services.LocationService;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.util.AbstractScript;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class BarrowsScript extends AbstractScript {

    private static final String DRINK = "Drink";
    private static final String SEARCH = "Search";
    private static final String OPEN = "Open";
    private static final String BARROWS_CHEST = "Barrows chest";
    private static final String YEAH_I_M_FEARLESS = "Yeah I'm fearless!";
    private static final String SPADE = "Spade";
    private static final String DIG = "Dig";
    private static final String STRANGE_OLD_MAN = "Strange Old Man";
    private static final String CLIMB_UP = "Climb-up";
    private static final String ATTACK = "Attack";
    private static final String SKELETON = "Skeleton";
    private static final String BLOODWORM = "Bloodworm";
    private static final String PRAYER_POTION = "Prayer potion";
    private static final String MOONLIGHT_MOTH = "Moonlight moth";
    private static final String RELEASE = "Release";
    private static final String UNKNOWN_BROTHER = "Unknown";
    private static final WorldPoint CHEST_LOCATION = new WorldPoint(3552, 9694, 0);

    private enum BarrowsState {
        RESTORE,
        BANKING,
        TRAVEL_TO_BARROWS,
        MOUNDS,
        ENTER_TUNNELS,
        TUNNELS,
        CHEST,
    }

    @Inject
    private BarrowsConfig config;

    private BankService bankService;
    private PuzzleSolverService puzzleSolverService;
    private LocationService locationService;
    private TileObjectService tileObjectService;

    @Getter
    private int chestsOpened = 0;
    @Getter
    private String brotherInTunnel = UNKNOWN_BROTHER;
    @Setter
    private boolean outOfPoweredStaffCharges;

    private int skeletonsKilled;
    private int bloodwormsKilled;

    private final AtomicReference<ScheduledFuture<?>> walkToChestTask = new AtomicReference<>();
    private final Map<BarrowsBrother, Boolean> brotherStatuses = new LinkedHashMap<>();

    @Override
    public void initialize() {
        Microbot.enableAutoRunOn = false;
        bankService = new BankService(config);
        puzzleSolverService = new PuzzleSolverService();
        locationService = new LocationService(rs2TileObjectCache);
        tileObjectService = new TileObjectService(rs2TileObjectCache);

        if (!BarrowsBrother.allBarrowsBrothersAreKilled()) {
            Arrays.stream(BarrowsBrother.values()).forEach(brother -> brotherStatuses.put(brother, brother.hasBeenKilled()));
        } else {
            Arrays.stream(BarrowsBrother.values()).forEach(brother -> brotherStatuses.put(brother, false));
        }
    }

    @Override
    public void onException(Exception ex) {
        if (ex instanceof BarrowsScriptException) {
            BarrowsScriptException barrowsScriptException = (BarrowsScriptException) ex;
            Microbot.showMessage(barrowsScriptException.getMessage());
        }
        log.error("Barrows tick exception", ex);
        mainScheduledFuture.cancel(true);
        shutdown();
    }

    @Override
    public void tick() {
        BarrowsState state = getState();

        if (state != BarrowsState.TUNNELS) {
            cancelWalkToChestTask();
        }

        switch (state) {
            case RESTORE:
                restoreAtFerox();
                break;
            case BANKING:
                bankService.handleBanking(config.magicAttack(), outOfPoweredStaffCharges);
                break;
            case TRAVEL_TO_BARROWS:
                locationService.handleTravelToBarrows();
                break;
            case MOUNDS:
                handleMounds();
                break;
            case ENTER_TUNNELS:
                handleEnterTunnels();
                break;
            case TUNNELS:
                handleTunnels();
                break;
            case CHEST:
                handleChest();
                break;
        }
    }

    private BarrowsState getState() {
        if (sleepUntil(() -> Rs2Widget.hasWidget(BARROWS_CHEST)) || (locationService.needsTravelToBarrows() && !isPrayerAndRunSufficient())) {
            return BarrowsState.RESTORE;
        } else if (locationService.needsTravelToBarrows() && !bankService.bankingRequirementsMet()) {
            return BarrowsState.BANKING;
        } else if (bankService.bankingRequirementsMet() && locationService.needsTravelToBarrows()) {
            return BarrowsState.TRAVEL_TO_BARROWS;
        } else if (locationService.isInBarrowsTunnel()) {
            if (isNearChest()) {
                return BarrowsState.CHEST;
            }
            return BarrowsState.TUNNELS;
        } else if (enoughBrothersKilledToEnterTunnel()) {
            return BarrowsState.ENTER_TUNNELS;
        }

        return BarrowsState.MOUNDS;
    }

    private boolean isNearChest() {
        Optional<Rs2TileObjectModel> chest = tileObjectService.getChest();
        return chest.isPresent() && hasLineOfSight(chest.get()) && Rs2Player.distanceTo(chest.get().getWorldLocation()) < 6;
    }

    private static boolean hasLineOfSight(Rs2TileObjectModel tileObject) {
        if (tileObject == null) {
            log.info("Object is null");
            return false;
        } else {
            WorldPoint point = Rs2Player.getWorldLocation();
            return (new WorldArea(tileObject.getWorldLocation(), 2, 2)).hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), new WorldArea(point.getX(), point.getY(), 2, 2, point.getPlane()));
        }
    }

    private void restoreAtFerox() {
        locationService.teleportToFerox();
        drinkFromPoolOfRefreshment();
        sleep(1200, 1800);
    }

    private void drinkFromPoolOfRefreshment() {
        Optional<Rs2TileObjectModel> poolOfRefreshment = tileObjectService.getPoolOfRefreshmentObject();

        if (poolOfRefreshment.isPresent()) {
            poolOfRefreshment.get().click(DRINK);
            sleepUntil(this::isPrayerAndRunSufficient, 15000); //takes a bit longer to run to the pool
        }  else {
            log.info("Pool of Refreshment not found.");
        }
    }

    private boolean isPrayerAndRunSufficient() {
        return Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER) - 1
                && Rs2Player.getRunEnergy() >= 90;
    }

    private BarrowsBrother getNextBarrowsBrother() {
        return brotherStatuses.entrySet().stream().filter((brother) -> !brother.getValue()).findFirst().map(Map.Entry::getKey).orElse(null);
    }

    private void setBrotherChecked(BarrowsBrother brother) {
        brotherStatuses.put(brother, true);
    }

    private void handleMounds() {
        if (Rs2Player.getWorldLocation().getPlane() == 3) {
            leaveTheMound();
        }

        BarrowsBrother brother = getNextBarrowsBrother();

        log.info("Going for brother: {}" , brother.getName());
        changeEquipment(brother);

        if (!config.magicAttack().isPoweredStaff() && !brother.isAhrim()) {
            setAutoCast();
        }

        if (Rs2Player.getWorldLocation().getPlane() != 3) {
            log.info("Going inside mound.");
            goToTheMound(brother);
            digIntoTheMound(brother);
        }

        activatePrayer(brother);

        if (!brotherInsideOfTunnel(brother)) {
            checkForAndFightBrother(brother);
        }

        leaveTheMound();
        disablePrayer();
        setBrotherChecked(brother);
        sleep(600, 1200);
    }

    private void changeEquipment(BarrowsBrother brother) {
        Rs2InventorySetup inventorySetup;
        if (brother.isNeedsMeleeGear()) {
            inventorySetup = new Rs2InventorySetup(config.inventorySetupMelee(), mainScheduledFuture);
        } else {
            inventorySetup = new Rs2InventorySetup(config.inventorySetupAhrim(), mainScheduledFuture);
        }

        inventorySetup.wearEquipment();
        sleep(600, 1800);
    }

    private void handleEnterTunnels() {

        Rs2InventorySetup inventorySetup = new Rs2InventorySetup(config.inventorySetupTunnels(), mainScheduledFuture);

        /* if (!inventorySetup.doesEquipmentMatch() && !inventorySetup.loadEquipment()) { //TODO: check why this doesn't seem to return a true after equipping everything? Fuziness?
            throw new BarrowsScriptException("Could not load the right equipment for tunnels" );
        } */
        inventorySetup.wearEquipment();
        sleep(600, 1800);

        BarrowsBrother tunnelBrother = BarrowsBrother.getFinalBarrowsBrother();
        if (Rs2Player.getWorldLocation().getPlane() != 3) {
            goToTheMound(tunnelBrother);
            digIntoTheMound(tunnelBrother);
            return;
        }

        tileObjectService.getSarcophagus().ifPresent(sarcophagus -> {
            sarcophagus.click(SEARCH);
            sleepUntil(Rs2Dialogue::isInDialogue);
            dialogueEnterTunnels();
        });
    }

    private void handleChest() {
        Optional<Rs2TileObjectModel> chest = tileObjectService.getChest();

        if (chest.isPresent()) {
            chest.get().click(OPEN);
            if (!everyBrotherWasKilled()) {
                checkForAndFightBrother(BarrowsBrother.getFinalBarrowsBrother());
                disablePrayer();
            }
            sleep(600, 1000);
            chest = tileObjectService.getChest();
            if (chest.isPresent()) {
                chest.get().click(SEARCH);
                chestsOpened++;
                reset();
                sleep(600, 1800);
            }

        }
    }

    private void reset() {
        brotherInTunnel = UNKNOWN_BROTHER;
        bloodwormsKilled = 0;
        skeletonsKilled = 0;
        brotherStatuses.replaceAll((brother, checked) -> false);
    }

    private boolean everyBrotherWasKilled() {
        return Arrays.stream(BarrowsBrother.values()).allMatch(BarrowsBrother::hasBeenKilled);
    }

    private void dialogueEnterTunnels() {
        sleepUntil(() -> Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasContinue());
        Rs2Dialogue.clickContinue();
        sleepUntil(() -> Rs2Dialogue.hasDialogueOption(YEAH_I_M_FEARLESS));
        sleep(300, 600);
        Rs2Keyboard.keyPress('1');
        sleepUntil(locationService::isInBarrowsTunnel);
        sleep(1000, 2000);
    }

    private void digIntoTheMound(BarrowsBrother brother) {
        while (super.isRunning()
                && brother.getMoundArea().contains(Rs2Player.getWorldLocation())
                && Rs2Player.getWorldLocation().getPlane() != 3) {

            if (Rs2Inventory.contains(SPADE) && Rs2Inventory.interact(SPADE, DIG)) {
                sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() == 3);
            }
        }
    }

    private void goToTheMound(BarrowsBrother brother) {
        while (super.isRunning() && !brother.getMoundArea().contains(Rs2Player.getWorldLocation())) {
            List<WorldPoint> tiles = brother.getMoundArea().toWorldPointList();
            WorldPoint randomTile = tiles.get(Rs2Random.between(0, tiles.size() - 1));

            if (Rs2Walker.walkTo(randomTile)) {
                sleepUntil(() -> !Rs2Player.isMoving());
            }

            if (brother.getMoundArea().contains(Rs2Player.getWorldLocation()) && !Rs2Player.isMoving()) {
                return;
            }

            Rs2NpcModel oldMan = rs2NpcCache
                    .query()
                    .withName(STRANGE_OLD_MAN)
                    .where(Rs2NpcModel::hasLineOfSight)
                    .nearestOnClientThread();

            if (oldMan != null && oldMan.getWorldLocation() != null && oldMan.getWorldLocation().equals(randomTile)) {
                while (super.isRunning() && oldMan.getWorldLocation().equals(randomTile)) {
                    randomTile = tiles.get(Rs2Random.between(0, tiles.size() - 1));
                    sleep(250, 500);
                }
            }

            Rs2Walker.walkCanvas(randomTile);
            sleepUntil(() -> !Rs2Player.isMoving());
        }
    }

    private void leaveTheMound() {
        Optional<Rs2TileObjectModel> staircase = tileObjectService.getStaircase();
        if (staircase.isPresent()) {
            if (!hasLineOfSight(staircase.get())) {
                return;
            }
            log.info("Leaving mound.");

            staircase.get().click(CLIMB_UP);
            sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() != 3);
            disablePrayer();
        }
    }

    private void gainPotential() {
        Rs2InventorySetup inventorySetup = new Rs2InventorySetup(config.inventorySetupTunnels(), mainScheduledFuture);

        inventorySetup.wearEquipment();
        Optional<Rs2NpcModel> monsterToAttack = getNearestSkeletonOrBloodworm();

        if (monsterToAttack.isEmpty()) {
            log.info("No monster to account found. Skipping.");
            return;
        }

        log.info("Killing NPC to gain reward potential.");
        if (!Rs2Combat.inCombat()) {
            Rs2Npc.interact(monsterToAttack.get().getId(), ATTACK);
            //Microbot.getClientThread().invoke(() -> monsterToAttack.get().click()); //TODO: check why this doesn't seem to register the actual click? Mouse does move...
        }

        log.info("Waiting until in combat.");
        sleepUntil(Rs2Combat::inCombat, 1800);
        log.info("Player is in combat");
        NPC attackingNpc = (NPC) Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getInteracting());
        if (attackingNpc == null) {
            log.info("No NPC being attacked by player?");
        } else if (stillNeedsToKillNpc(attackingNpc)) {
            log.info("Attacking NPC");
            try {
                sleepUntil(() -> attackingNpc.isDead() || !Rs2Combat.inCombat(), () -> {
                    if (eatFood()) {
                        Rs2Npc.interact(attackingNpc.getId(), ATTACK);
                    }
                }, 60000, 600);
            } catch (Exception e) {
                log.error("isCombat has thrown a timeout exception.");
            }

            registerKill(attackingNpc);
            log.info("NPC killed.");
        } else {
            log.info("Not a skeleton or bloodworm. Continuing to chest.");
        }


        sleep(600, 1800);
    }

    private void registerKill(NPC npc) {
        if (isSkeleton(npc)) {
            skeletonsKilled++;
        } else {
            bloodwormsKilled++;
        }
    }

    private boolean stillNeedsToKillNpc(NPC npc) {
        return isSkeleton(npc) || isBloodworm(npc) && (bloodwormsKilled == 0 || skeletonsKilled < 2);
    }

    private boolean isSkeleton(NPC npc) {
        String name = Microbot.getClientThread().invoke(npc::getName);
        return "skeleton".equalsIgnoreCase(name);
    }

    private boolean isBloodworm(NPC npc) {
        String name = Microbot.getClientThread().invoke(npc::getName);
        return "bloodworm".equalsIgnoreCase(name);
    }

    private Optional<Rs2NpcModel> getNearestSkeletonOrBloodworm() {
        return Optional.ofNullable(
                rs2NpcCache
                        .query()
                        .withNames(SKELETON, BLOODWORM)
                        .where(Rs2NpcModel::hasLineOfSight)
                        .where(npc -> !npc.isDead())
                        .nearestOnClientThread(12)
        );
    }

    private void setAutoCast() {
        if (!config.magicAttack().isPoweredStaff() && Rs2Magic.getCurrentAutoCastSpell() != config.magicAttack().getAutocast()) {
            Rs2Combat.setAutoCastSpell(config.magicAttack().getAutocast(), false);
        }
    }

    private void activatePrayer(BarrowsBrother brother) {
        if (Objects.nonNull(brother) && !Rs2Prayer.isPrayerActive(brother.getWhatToPray())) {
            Rs2Prayer.toggle(brother.getWhatToPray());
        }
    }

    private void disablePrayer() {
        log.info("Disabling prayers");
        sleep(600, 1200);
        Rs2Prayer.disableAllPrayers();
        sleep(0, 750);
    }


    private boolean usePrayerRestorationIfNecessary(BarrowsBrother brother, Rs2NpcModel npc) {
        if (locationService.isInBarrowsTunnel() && !brother.isDharok() && Rs2Inventory.count(config.food().getId()) > 4) {
            //optimize prayer restore -> food is less valuable than prayer restoration
            return false;
        }

        if (npc != null && npc.getHealthPercentage() < 33 && !brother.isDharok()) {
            return false;
        }

        if ((config.shouldPrayAgainstWeakerBrothers() || !brother.isWeakerBrother()) && !BarrowsBrother.allBarrowsBrothersAreKilled()) {
            if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < Rs2Random.between(8, 15)) {
                usePrayerRestoration();
                sleep(600);
                return true;
            }
        }

        return false;
    }

    private static void usePrayerRestoration() {
        Rs2ItemModel restore = Rs2Inventory.get(it ->
                it != null && (it.getName().contains(PRAYER_POTION) || it.getName().contains(MOONLIGHT_MOTH)));

        if (restore == null) {
            log.info("Couldn't find Prayer potion or moonlight moth.");
            return;
        }

        String action = restore.getName().contains(MOONLIGHT_MOTH) ? RELEASE : DRINK;
        log.info("Restoring prayer.");
        Rs2Inventory.interact(restore, action);
        sleep(0, 750);
    }

    private Rs2NpcModel hintNpcModel() {
        Optional<NPC> hintNpc = Microbot.getClientThread().runOnClientThreadOptional(
                () -> Microbot.getClient().getHintArrowNpc()
        );

        return hintNpc.map(Rs2NpcModel::new).orElse(null);
    }

    private void checkForAndFightBrother(BarrowsBrother barrowsBrother) {
        sleepUntil(() -> Objects.nonNull(hintNpcModel()));
        Rs2NpcModel npc = hintNpcModel();
        usePrayerRestorationIfNecessary(barrowsBrother, npc);
        activatePrayer(barrowsBrother);
        changeEquipment(barrowsBrother);
        net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel oldNpcModel = new net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel(npc.getNpc());
        Rs2Npc.interact(oldNpcModel, ATTACK);
        //npc.click("Attack"); //TODO: check why both of these don't seem to work properly. Actual click doesn't work? Or only attacking doesn't?
        //Microbot.getClientThread().invoke(() -> npc.click());

        while (!npc.isDead()) {
            sleep(750, 1500);
            if (usePrayerRestorationIfNecessary(barrowsBrother, npc)) {
                Rs2Npc.interact(oldNpcModel, ATTACK);
            }
            if (eatFood()) {
                Rs2Npc.interact(oldNpcModel, ATTACK);
            }
        }

        log.info("Brother has been killed.");
    }

    private boolean shouldGainMorePotential() {
        if (config.shouldGainRP() && bloodwormsKilled < 1 && skeletonsKilled < 2) {
            var nearestSkeletonOrBloodworm = getNearestSkeletonOrBloodworm();
            return nearestSkeletonOrBloodworm.isPresent();
        }

        return false;
    }

    private void handleTunnels() {
        ensureQuestDone();

        if (handleBrotherIfPresent() || handlePuzzleIfPresent() || handlePotentialIfNeeded()) {
            return;
        }

        handleWalkToChestIfNeeded();
    }

    private void ensureQuestDone() {
        if (Rs2Player.getQuestState(Quest.HIS_FAITHFUL_SERVANTS) != QuestState.FINISHED) {
            throw new BarrowsScriptException("Quest 'His faithful servants' is not finished");
        }
    }

    private boolean handleBrotherIfPresent() {
        if (Objects.isNull(hintNpcModel())) {
            return false;
        }

        log.info("Barrows brother located in tunnel.");
        resetChestWalker();
        checkForAndFightBrother(BarrowsBrother.getFinalBarrowsBrother());
        disablePrayer();
        return true;
    }

    private boolean handlePuzzleIfPresent() {
        if (!puzzleSolverService.isPuzzleOnScreen()) {
            return false;
        }

        log.info("Puzzle is on screen");
        resetChestWalker();
        puzzleSolverService.solvePuzzle();
        return true;
    }

    private boolean handlePotentialIfNeeded() {
        if (!shouldGainMorePotential()) {
            return false;
        }

        log.info("Gaining more reward potential");
        resetChestWalker();
        gainPotential();
        return true;
    }

    private void handleWalkToChestIfNeeded() {
        walkToChest();
    }


    private void walkToChest() {
        if (walkToChestTask.get() != null) {
            return;
        }

        ScheduledFuture<?> future = scheduledExecutorService.schedule(() -> {
            try {
                Rs2Walker.walkTo(CHEST_LOCATION, 2);
            } catch (Exception e) {
                log.error("Walk to chest failed", e);
            } finally {
                walkToChestTask.compareAndSet(
                        walkToChestTask.get(),
                        null
                );
            }
        }, 600, TimeUnit.MILLISECONDS);

        if (!walkToChestTask.compareAndSet(null, future)) {
            log.info("Cancelling future.");
            future.cancel(true);
        }
    }

    private void cancelWalkToChestTask() {
        ScheduledFuture<?> future = walkToChestTask.getAndSet(null);
        if (future != null) {
            log.debug("Cancelling walk to chest");
            future.cancel(true);
        }
    }

    private void resetChestWalker() {
        log.info("Resetting walk to chest");
        cancelWalkToChestTask();
        Rs2Walker.setTarget(null);
    }

    private boolean eatFood() {
        if (Rs2Player.getHealthPercentage() > 70) {
            return false;
        }

        Rs2ItemModel food = Rs2Inventory.get(it -> it != null && it.isFood());
        if (food != null) {
            log.info("Eating food.");
            Rs2Inventory.interact(food, "Eat");
            sleep(600, 800);
            return true;
        }

        return false;
    }

    private boolean enoughBrothersKilledToEnterTunnel() {
        return Arrays.stream(BarrowsBrother.values()).filter(BarrowsBrother::hasBeenKilled).count() == 5;
    }

    private boolean brotherInsideOfTunnel(BarrowsBrother brother) {
        Optional<Rs2TileObjectModel> sarcophagus = tileObjectService.getSarcophagus();

        if (sarcophagus.isPresent() && sarcophagus.get().click(SEARCH)) {
            sleepUntil(() -> Objects.nonNull(hintNpcModel()) || Rs2Dialogue.isInDialogue());

            if (Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasDialogueText("You've found a hidden")) {
                log.info("Found the crypt for brother: {}", brother.getName());
                brotherInTunnel = brother.getName();
                return true;
            }
        }

        return false;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        resetChestWalker();
    }
}

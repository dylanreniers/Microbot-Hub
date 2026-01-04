package net.runelite.client.plugins.microbot.custom.barrows;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.NPC;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemCache;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.custom.barrows.services.BankService;
import net.runelite.client.plugins.microbot.custom.barrows.services.PuzzleSolverService;
import net.runelite.client.plugins.microbot.custom.barrows.services.TileObjectService;
import net.runelite.client.plugins.microbot.custom.barrows.services.LocationService;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldArea;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class BarrowsScript extends Script {

    private static final int TICK_TIMEOUT = 100;
    private static final String DRINK = "Drink";
    private static final String SEARCH = "Search";
    private static final String OPEN = "Open";
    private static final String BARROWS_CHEST = "Barrows chest";
    private static final String YEAH_I_M_FEARLESS = "Yeah I'm fearless!";
    private static final String SPADE = "Spade";
    private static final String DIG = "Dig";
    private static final String STRANGE_OLD_MAN = "Strange Old Man";
    private static final String CLIMB_UP = "Climb-up";
    private static final String TAKE = "Take";
    private static final String ATTACK = "Attack";
    private static final String SKELETON = "Skeleton";
    private static final String BLOODWORM = "Bloodworm";
    private static final String DEATH_RUNE = "Death rune";
    private static final String BLOOD_RUNE = "Blood rune";
    private static final String WRATH_RUNE = "Wrath rune";
    private static final String PRAYER_POTION = "Prayer potion";
    private static final String MOONLIGHT_MOTH = "Moonlight moth";
    private static final String RELEASE = "Release";
    private static final String UNKNOWN_BROTHER = "Unknown";
    private static final String UNKNOWN_RUNE = "unknown";
    private static final WorldPoint CHEST_LOCATION = new WorldPoint(3552, 9694, 0);

    private enum State {
        RESTORE,
        BANKING,
        TRAVEL_TO_BARROWS,
        MOUNDS,
        ENTER_TUNNELS,
        TUNNELS,
        CHEST,
    }

    private boolean usePrayerAgainstWeakerBrother;
    private boolean usingPoweredStaffs;
    private boolean shouldGainRp;
    private String neededRune = UNKNOWN_RUNE;
    private BarrowsConfig config;
    private boolean chestLooted;

    @Getter
    private final Set<String> barrowsPieces = new HashSet<>();
    @Getter
    private int chestsOpened = 0;
    @Getter
    private String brotherInTunnel = UNKNOWN_BROTHER;
    @Setter
    private boolean outOfPoweredStaffCharges;

    @Inject
    private Rs2TileItemCache rs2TileItemCache;
    @Inject
    private Rs2NpcCache rs2NpcCache;
    @Inject
    private Rs2TileObjectCache rs2TileObjectCache;

    private BankService bankService;
    private PuzzleSolverService puzzleSolverService;
    private LocationService locationService;
    private TileObjectService tileObjectService;

    private final AtomicReference<ScheduledFuture<?>> walkToChestTask = new AtomicReference<>();
    private final Map<BarrowsBrother, Boolean> brotherStatuses = new LinkedHashMap<>();

    public boolean run(BarrowsConfig config) {
        Microbot.enableAutoRunOn = false;
        this.config = config;

        bankService = new BankService(config);
        puzzleSolverService = new PuzzleSolverService();
        locationService = new LocationService(rs2TileObjectCache);
        tileObjectService = new TileObjectService(rs2TileObjectCache);

        this.usePrayerAgainstWeakerBrother = config.shouldPrayAgainstWeakerBrothers();
        if (barrowsPieces.isEmpty()) {
            barrowsPieces.add("Nothing yet.");
        }

        updateCombatMode();

        if (!BarrowsBrother.allBarrowsBrothersAreKilled()) {
            Arrays.stream(BarrowsBrother.values()).forEach(brother -> brotherStatuses.put(brother, brother.hasBeenKilled()));
        } else {
            Arrays.stream(BarrowsBrother.values()).forEach(brother -> brotherStatuses.put(brother, false));
        }

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() ->
        {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }

                tick();
            } catch (Exception ex) {
                if (ex instanceof BarrowsScriptException) {
                    BarrowsScriptException barrowsScriptException = (BarrowsScriptException) ex;
                    Microbot.showMessage(barrowsScriptException.getMessage());
                }
                log.error("Barrows tick exception", ex);
                mainScheduledFuture.cancel(true);
                shutdown();
            }
        }, 0, TICK_TIMEOUT, TimeUnit.MILLISECONDS);

        return true;
    }

    private void tick() {
        State state = getState();

        if (state != State.TUNNELS) {
            cancelWalkToChestTask();
        }

        switch (state) {
            case RESTORE:
                restoreAtFerox();
                break;
            case BANKING:
                bankService.handleBanking(neededRune, usingPoweredStaffs, outOfPoweredStaffCharges);
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

    private State getState() {
        if (chestLooted || (isNearFerox() && !isPrayerAndRunSufficient())) {
            return State.RESTORE;
        } else if (isNearFerox() && isPrayerAndRunSufficient() && !bankService.bankingRequirementsMet(neededRune, usingPoweredStaffs)) {
            return State.BANKING;
        } else if (bankService.bankingRequirementsMet(neededRune, usingPoweredStaffs) && locationService.needsTravelToBarrows()) {
            return State.TRAVEL_TO_BARROWS;
        } else if (locationService.isInBarrowsTunnel()) {
            if (isNearChest()) {
                return State.CHEST;
            }
            return State.TUNNELS;
        } else if (enoughBrothersKilledToEnterTunnel()) {
            return State.ENTER_TUNNELS;
        }

        return State.MOUNDS;
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

    private static boolean isNearFerox() {
        return Rs2Player.getWorldLocation().distanceTo(BankLocation.FEROX_ENCLAVE.getWorldPoint()) <= 50;
    }

    private void restoreAtFerox() {
        chestLooted = false;
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

        if (!usingPoweredStaffs) {
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
                sleepUntil(() -> Rs2Widget.hasWidget(BARROWS_CHEST));
                recordBarrowsPiece();
                brotherInTunnel = UNKNOWN_BROTHER;
                chestsOpened++;
                chestLooted = true;
                brotherStatuses.replaceAll((brother, checked) -> false);
                sleep(600, 1800);
            }

        }
    }

    private void updateCombatMode() {
        usingPoweredStaffs = isWearingPoweredStaff();

        if (!usingPoweredStaffs) {
            resolveNeededRuneIfUnknown();

            if (Rs2Magic.getSpellbook() != Rs2Spellbook.MODERN) {
                swapTheSpellbook();
            }
        }

        shouldGainRp = config.shouldGainRP();
    }

    private static boolean isWearingPoweredStaff() {
        Rs2ItemModel weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON);
        if (weapon == null || weapon.getName() == null) {
            return false;
        }

        String name = weapon.getName();
        return name.contains("Trident of the")
                || name.contains("Tumeken's")
                || name.contains("sceptre")
                || name.contains("Sanguinesti")
                || name.contains("Crystal staff");
    }

    private void resolveNeededRuneIfUnknown() {
        if (!UNKNOWN_RUNE.equalsIgnoreCase(neededRune)) {
            return;
        }

        /* int magicLvl = Rs2Player.getRealSkillLevel(Skill.MAGIC);

        if (magicLvl >= 81) {
            neededRune = "Wrath rune";
        } else if (magicLvl >= 62) {
            neededRune = "Blood rune";
        } else {
            neededRune = "Death rune";
        } */

        neededRune = "Death rune";
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

    private void lootChampionScroll() {
        Rs2TileItemModel scroll = rs2TileItemCache
                .query()
                .where(x -> x.getId() == ItemID.CHAMPIONS_CHALLENGE_SKELETON)
                .nearestOnClientThread();

        if (scroll == null) {
            return;
        }

        scroll.click(TAKE);
        sleepUntil(() -> Rs2Inventory.contains(scroll.getId()));
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
        } else {
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
        }

        log.info("NPC killed.");

        sleep(600, 1800);
        lootChampionScroll();
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

    private void swapTheSpellbook() {
        if (Rs2Magic.getSpellbook() == Rs2Spellbook.MODERN) {
            return;
        }

        WorldPoint swapLocation = Rs2Magic.getSpellbook().getSwitchLocation();
        if (Rs2Player.getWorldLocation().distanceTo(swapLocation) > 5) {
            Rs2Walker.walkTo(swapLocation);
        }

        Rs2Spellbook.MODERN.switchTo();
    }

    private void setAutoCast() {
        if (WRATH_RUNE.equals(neededRune)) {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_SURGE) {
                log.info("Setting autocast to wind surge.");
                Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_SURGE, false);
            }
        } else if (BLOOD_RUNE.equals(neededRune)) {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_WAVE) {
                log.info("Setting autocast to wind wave.");
                Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_WAVE, false);
            }
        } else if (DEATH_RUNE.equals(neededRune)) {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_BLAST) {
                log.info("Setting autocast to wind blast.");
                Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_BLAST, false);
            }
        }
    }

    private void activatePrayer(BarrowsBrother brother) {
        if (Objects.nonNull(brother) && !Rs2Prayer.isPrayerActive(brother.whatToPray)) {
            Rs2Prayer.toggle(brother.whatToPray);
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

        if ((this.usePrayerAgainstWeakerBrother || !brother.isWeakerBrother()) && !BarrowsBrother.allBarrowsBrothersAreKilled()) {
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
        int rp = Microbot.getVarbitValue(VarbitID.BARROWS_KILLED_MONSTER);
        if (shouldGainRp && ((everyBrotherWasKilled() && rp < 840) || (BarrowsBrother.getNumberOfBarrowsBrothersKilled() == 5 && rp < 750))) {
            var nearestSkeletonOrBloodworm = getNearestSkeletonOrBloodworm();
            return nearestSkeletonOrBloodworm.isPresent();
        }

        return false;
    }

    private void handleTunnels() {
        if (Rs2Player.getQuestState(Quest.HIS_FAITHFUL_SERVANTS) != QuestState.FINISHED) {
            throw new BarrowsScriptException("Quest 'His faithful servants' is not finished");
        }

        if (hintNpcModel() != null) {
            log.info("Barrows brother located in tunnel.");
            resetChestWalker();
            checkForAndFightBrother(BarrowsBrother.getFinalBarrowsBrother());
            disablePrayer();
        } else if (puzzleSolverService.isPuzzleOnScreen()) {
            log.info("Puzzle is on screen");
            resetChestWalker();
            puzzleSolverService.solvePuzzle();
        } else if (shouldGainMorePotential()) {
            log.info("Gaining more reward potential");
            resetChestWalker();
            gainPotential();
        } else if (!chestLooted && !puzzleSolverService.isPuzzleOnScreen()) {
            walkToChest();
        }
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
            sleepUntil(() -> hintNpcModel() != null || Rs2Dialogue.isInDialogue());

            if (Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasDialogueText("You've found a hidden")) {
                log.info("Found the crypt for brother: {}", brother.getName());
                brotherInTunnel = brother.getName();
                return true;
            }
        }

        return false;
    }

    private void recordBarrowsPiece() {
        Rs2ItemModel piece = Rs2Inventory.get(it -> it != null && it.getName().contains("'s"));
        if (piece != null) {
            barrowsPieces.add(piece.getName());
            barrowsPieces.remove("Nothing yet.");
        }
    }

    @Getter
    public enum BarrowsBrother {
        //Note: this is the order in which the brothers are killed.
        DHAROK("Dharok the Wretched", new Rs2WorldArea(3573, 3296, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_DHAROK, true),
        KARIL("Karil the Tainted", new Rs2WorldArea(3564, 3274, 3, 3, 0), Rs2PrayerEnum.PROTECT_RANGE, VarbitID.BARROWS_KILLED_KARIL, true),
        AHRIM("Ahrim the Blighted", new Rs2WorldArea(3563, 3288, 3, 3, 0), Rs2PrayerEnum.PROTECT_MAGIC, VarbitID.BARROWS_KILLED_AHRIM, false),
        GUTHAN("Guthan the Infested", new Rs2WorldArea(3575, 3280, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_GUTHAN, true),
        TORAG("Torag the Corrupted", new Rs2WorldArea(3552, 3282, 2, 2, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_TORAG, true),
        VERAC("Verac the Defiled", new Rs2WorldArea(3556, 3297, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_VERAC, true);

        private final String name;
        private final Rs2WorldArea moundArea;
        private final Rs2PrayerEnum whatToPray;
        private final int varbit;
        private final boolean needsMeleeGear;

        BarrowsBrother(String name, Rs2WorldArea moundArea, Rs2PrayerEnum whatToPray, int varbit, boolean needsMeleeGear) {
            this.name = name;
            this.moundArea = moundArea;
            this.whatToPray = whatToPray;
            this.varbit = varbit;
            this.needsMeleeGear = needsMeleeGear;
        }

        public static BarrowsBrother getFirstBarrowsBrother() {
            return BarrowsBrother.values()[0];
        }

        public static boolean allBarrowsBrothersAreKilled() {
            return Arrays.stream(BarrowsBrother.values()).filter(BarrowsBrother::hasBeenKilled).count() == 6;
        }

        public static int getNumberOfBarrowsBrothersKilled() {
            return (int) Arrays.stream(BarrowsBrother.values()).filter(BarrowsBrother::hasBeenKilled).count();
        }

        public static BarrowsBrother getFinalBarrowsBrother() {
            return Arrays.stream(BarrowsBrother.values()).filter((brother) -> !brother.hasBeenKilled()).findFirst().orElse(null);
        }

        public boolean hasBeenKilled() {
            return Microbot.getVarbitValue(this.varbit) == 1;
        }

        public boolean isDharok() {
            return this.name.startsWith("Dharok");
        }

        public boolean isAhrim() {
            return this.name.startsWith("Ahrim");
        }

        public boolean isKaril() {
            return this.name.startsWith("Karil");
        }

        public boolean isWeakerBrother() {
            return !isDharok() && !isAhrim() && !isKaril();
        }
    }

    public static class BarrowsScriptException extends RuntimeException {
        public BarrowsScriptException(String message) {
            super(message);
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        resetChestWalker();
    }
}

package net.runelite.client.plugins.microbot.barrows;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.IEntity;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemCache;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetupsItem;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldArea;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldPoint;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class BarrowsScript extends Script {

    private enum State {
        BANKING,
        TRAVEL_TO_BARROWS,
        MOUNDS,
        ENTER_TUNNELS,
        TUNNELS,
        CHEST,
    }

    private static final List<Integer> PUZZLE_WIDGET_IDS = List.of(1638413, 1638415, 1638417);
    private static final List<Integer> PUZZLE_MODELS_IDS = List.of(6725, 6731, 6713, 6719);
    private static final int POH_PORTAL_ID = 4525;
    private static final int BARROWS_PORTAL_ID = 37591;
    private static final int CHEST_ID = 20973;
    private static final String UNKNOWN_BROTHER = "Unknown";
    private static final String UNKNOWN_RUNE = "unknown";
    private static final int BARROWS_MIN_Y = 9600;
    private static final int BARROWS_MAX_Y = 9730;
    private static final WorldPoint CHEST_LOCATION = new WorldPoint(3552, 9694, 0);

    private boolean usePrayerAgainstWeakerBrother;
    private State state;
    private boolean usingPoweredStaffs;
    private boolean shouldAttackSkeleton;
    private String neededRune = UNKNOWN_RUNE;
    private ScheduledFuture<?> walkToChestFuture;

    @Getter
    private final List<String> barrowsPieces = new ArrayList<>();
    @Getter
    private int chestsOpened = 0;
    @Setter
    private boolean outOfPoweredStaffCharges;
    @Getter
    private String brotherInTunnel = UNKNOWN_BROTHER;

    @Inject
    private Rs2TileItemCache rs2TileItemCache;

    @Inject
    private Rs2NpcCache rs2NpcCache;
    @Inject
    private Rs2TileObjectCache rs2TileObjectCache;

    public boolean run(BarrowsConfig config) {
        Microbot.enableAutoRunOn = false;

        this.usePrayerAgainstWeakerBrother = config.shouldPrayAgainstWeakerBrothers();
        if (barrowsPieces.isEmpty()) {
            barrowsPieces.add("Nothing yet.");
        }

        setState(getInitialState());
        //setState(State.ENTER_TUNNELS);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() ->
        {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }

                if (super.isRunning()) {
                    tick(config);
                }
            } catch (Exception ex) {
                if (ex instanceof BarrowsScriptException) {
                    BarrowsScriptException barrowsScriptException = (BarrowsScriptException) ex;
                    Microbot.showMessage(barrowsScriptException.getMessage());
                }
                log.error("Barrows tick exception", ex);
                mainScheduledFuture.cancel(true);
                shutdown();
            }
        }, 0, Rs2Random.between(200, 750), TimeUnit.MILLISECONDS);

        return true;
    }

    private void tick(BarrowsConfig config) {
        updateCombatMode(config);

        switch (state) {
            case BANKING:
                handleBanking(config);
                break;
            case TRAVEL_TO_BARROWS:
                handleTravelToBarrows();
                break;
            case MOUNDS:
                handleMounds(config);
                break;
            case ENTER_TUNNELS:
                handleEnterTunnels();
                break;
            case TUNNELS:
                handleTunnels(config);
                break;
            case CHEST:
                handleChest(config);
                break;
        }
    }

    private static boolean hasLineOfSight(Rs2TileObjectModel tileObject) {
        if (tileObject == null) {
            return false;
        } else {
            WorldPoint point = Rs2Player.getWorldLocation();
            return (new WorldArea(tileObject.getWorldLocation(), 2, 2)).hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), new WorldArea(point.getX(), point.getY(), 2, 2, point.getPlane()));
        }
    }

    private State getInitialState() {
        if (isInBarrowsTunnel()) {
            GameObject chest = Rs2GameObject.getGameObject(CHEST_ID);
            if (Rs2GameObject.hasLineOfSight(chest) && Rs2Player.distanceTo(chest.getWorldLocation()) < 4) {
                return State.CHEST;
            }
            return State.TUNNELS;
        }

        if (!UNKNOWN_BROTHER.equals(brotherInTunnel) && enoughBrothersKilledToTunnel()) {
            return State.ENTER_TUNNELS;
        }

        if (needsTravelToBarrows()) {
            return State.TRAVEL_TO_BARROWS;
        }

        return State.MOUNDS;
    }

    private void teleportToFerox() {
        Rs2Equipment.interact(EquipmentInventorySlot.RING, "Ferox Enclave");
        sleepUntil(Rs2Player::isAnimating);
        sleepUntil(() -> !Rs2Player.isAnimating());
    }

    private void restoreAtFerox() {
        if (isPrayerAndRunSufficient()) {
            return;
        }

        /*
        Rs2TileObjectModel poolOfRefreshment = rs2TileObjectCache.query()
                .where(object -> object.getName().equalsIgnoreCase("pool of refreshment"))
                .first();

        if (poolOfRefreshment != null) {
            poolOfRefreshment.click("Drink");
            sleepUntil(this::isPrayerAndRunSufficient);
        }
         */

        Rs2GameObject.interact("Pool of Refreshment", "Drink");
        sleepUntil(this::isPrayerAndRunSufficient);
    }

    private void teleportToHouse() {
        log.info("Teleporting to house.");
        Rs2Magic.cast(MagicAction.TELEPORT_TO_HOUSE);
        sleepUntil(Rs2Player::isAnimating);
        sleepUntil(() -> !Rs2Player.isAnimating());
        sleep(600, 1200);
        log.info("Waiting until POH portal can be found.");
        sleepUntil(() -> rs2TileObjectCache.query()
                .where(IEntity::isReachable)
                .where(object -> object.getId() == POH_PORTAL_ID)
                .first() == null);
    }


    private boolean isPrayerAndRunSufficient() {
        return Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= Rs2Player.getRealSkillLevel(Skill.PRAYER) - 1
                && Rs2Player.getRunEnergy() >= 90;
    }

    private void handleTravelToBarrows() {
        teleportToHouse();

        log.info("Looking for Barrows portal.");
        GameObject barrowsPortal = Rs2GameObject.getGameObjects().stream().filter(
                gameObject -> gameObject.getId() == BARROWS_PORTAL_ID
        ).findFirst().orElse(null);

        // Rs2TileObjectModel barrowsPortal = rs2TileObjectCache.query().within(40).where(rs2TileObjectModel -> {
        /* Rs2TileObjectModel barrowsPortal = new Rs2TileObjectQueryable()
                .fromWorldView()
                .where(IEntity::isReachable)
                .withName("Barrows Portal")
                .within(40)
                .nearest();
        */
        if (barrowsPortal == null) {
            log.info("Barrows Portal not found.");
            throw new BarrowsScriptException("No barrows portal found in POH.");
        }

        log.info("Entering barrows portal.");
        Rs2GameObject.interact(barrowsPortal, "Enter");

        //barrowsPortal.click("Enter");

        sleepUntil(Rs2Player::isMoving); //TODO: better would be to check the coordinates of the player
        sleepUntil(() -> !Rs2Player.isMoving());
        sleep(600, 1800);
        setState(State.MOUNDS);
    }

    private void handleMounds(BarrowsConfig config) {
        if (Rs2Player.getWorldLocation().getPlane() == 3) {
            leaveTheMound();
        }

        for (BarrowsBrother brother : BarrowsBrother.values()) {

            if (!BarrowsBrother.allBarrowsBrothersAreKilled() && brother.hasBeenKilled()) {
                // The varbit for the barrows brothers only gets reset after you've killed 1 brother,
                // meaning if you start a new run after you've completed one, it would say they are "all dead"
                log.info("Brother was already killed. Skipping to next brother.");
                continue;
            }

            log.info("Going for brother: {}" , brother.getName());
            changeEquipment(config, brother);

            if (!usingPoweredStaffs && !brother.isAhrim()) {
                setAutoCast();
            }

            if (Rs2Player.getWorldLocation().getPlane() != 3) {
                log.info("Going inside mound.");
                goToTheMound(brother);
                digIntoTheMound(brother);
            }

            activatePrayer(brother);

            if (!brotherInsideOfTunnel(brother)) {
                checkForAndFightBrother(config, brother);
            }

            sleep(600, 1200);
            leaveTheMound();
        }

        setState(State.ENTER_TUNNELS);
    }

    private void changeEquipment(BarrowsConfig config, BarrowsBrother brother) {
        Rs2InventorySetup inventorySetup;
        if (brother.isNeedsMeleeGear()) {
            inventorySetup = new Rs2InventorySetup(config.inventorySetupMelee(), mainScheduledFuture);
        } else {
            inventorySetup = new Rs2InventorySetup(config.inventorySetupAhrim(), mainScheduledFuture);
        }

        inventorySetup.wearEquipment();
        sleep(600, 1800);
        //TODO: check why this doesn't really seem to work? Some further debugging might be needed
        /* if (!inventorySetup.doesEquipmentMatch()) {
            throw new BarrowsScriptException("Could not load the right equipment for " + brother.getName());
        } else {
            log.info("Wearing correct equipment for {}", brother.getName());
        } */
    }

    private void handleEnterTunnels() {
        if (UNKNOWN_BROTHER.equals(brotherInTunnel)) {
            setState(State.MOUNDS);
            return;
        }

        Optional<BarrowsBrother> tunnelBrotherOptional = findBrotherByName(brotherInTunnel);
        if (tunnelBrotherOptional.isPresent()) {
            BarrowsBrother tunnelBrother = tunnelBrotherOptional.get();
            if (Rs2Player.getWorldLocation().getPlane() != 3) {
                goToTheMound(tunnelBrother);
                digIntoTheMound(tunnelBrother);
                return;
            }

            /* Rs2TileObjectModel sarcophagus = rs2TileObjectCache.query().where(rs2TileObjectModel -> "Sarcophagus".equals(rs2TileObjectModel.getName())).first();

            if (sarcophagus != null) {
                sarcophagus.click("Search");
                sleepUntil(Rs2Dialogue::isInDialogue);
            } */

            Rs2GameObject.interact("Sarcophagus", "Search");

            dialogueEnterTunnels();
            setState(State.TUNNELS);
        } else {
            brotherInTunnel = UNKNOWN_BROTHER;
            setState(State.MOUNDS);
        }

    }

    private void handleChest(BarrowsConfig config) {
        GameObject chest = Rs2GameObject.getGameObject(CHEST_ID);
        Rs2GameObject.interact(chest, "Open");
        if (!everyBrotherWasKilled()) {
            checkForAndFightBrother(config, BarrowsBrother.getFinalBarrowsBrother());
        } else {
            sleepUntil(() -> !super.isRunning());
        }

        Rs2GameObject.interact(chest, "Search");
        sleepUntil(() -> Rs2Widget.hasWidget("Barrows chest"));
        recordBarrowsPiece();
        brotherInTunnel = UNKNOWN_BROTHER;
        chestsOpened++;
        sleep(600, 1800);
        setState(State.BANKING);
    }

    private void handleBanking(BarrowsConfig config) {
        teleportToFerox();
        restoreAtFerox();
        bankRefill(config);
        closeBank();
    }

    private boolean isInBarrowsTunnel() {
        int y = Rs2Player.getWorldLocation().getY();
        return y > BARROWS_MIN_Y && y < BARROWS_MAX_Y;
    }

    private void updateCombatMode(BarrowsConfig config) {
        usingPoweredStaffs = isWearingPoweredStaff();

        if (!usingPoweredStaffs) {
            resolveNeededRuneIfUnknown();

            if (Rs2Magic.getSpellbook() != Rs2Spellbook.MODERN) {
                swapTheSpellbook();
            }
        }

        shouldAttackSkeleton = config.shouldGainRP();
    }

    private boolean needsTravelToBarrows() {
        if (isInBarrowsTunnel()) {
            return false;
        }

        WorldPoint barrowsArea = new WorldPoint(3573, 3296, 0);
        return Rs2Player.getWorldLocation().distanceTo(barrowsArea) > 60;
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

    public void checkForWorldMap() {
        var widget = Rs2Widget.getWidget(38993938);
        if (widget != null && widget.getText() != null && widget.getText().contains("Key")) {
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
        }
    }

    public void closeBank() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
        }
    }

    public boolean everyBrotherWasKilled() {
        return Arrays.stream(BarrowsBrother.values()).allMatch(BarrowsBrother::hasBeenKilled);
    }

    public void dialogueEnterTunnels() {
        sleepUntil(() -> Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasContinue());
        Rs2Dialogue.clickContinue();
        sleepUntil(() -> Rs2Dialogue.hasDialogueOption("Yeah I'm fearless!"));
        sleep(300, 600);
        Rs2Dialogue.clickOption("Yeah I'm fearless!");
        sleepUntil(this::isInBarrowsTunnel);
        sleep(1000, 2000);
    }

    public void digIntoTheMound(BarrowsBrother brother) {
        while (super.isRunning()
                && brother.getHumpWP().contains(Rs2Player.getWorldLocation())
                && Rs2Player.getWorldLocation().getPlane() != 3) {
            checkForWorldMap();

            if (Rs2Inventory.contains("Spade") && Rs2Inventory.interact("Spade", "Dig")) {
                sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() == 3);
            }
        }
    }

    public void goToTheMound(BarrowsBrother brother) {
        while (super.isRunning() && !brother.getHumpWP().contains(Rs2Player.getWorldLocation())) {
            checkForWorldMap();

            antiPatternDropVials();

            List<WorldPoint> tiles = brother.getHumpWP().toWorldPointList();
            WorldPoint randomTile = tiles.get(Rs2Random.between(0, tiles.size() - 1));

            if (Rs2Walker.walkTo(randomTile)) {
                sleepUntil(() -> !Rs2Player.isMoving());
            }

            if (brother.getHumpWP().contains(Rs2Player.getWorldLocation()) && !Rs2Player.isMoving()) {
                return;
            }

            // Strange Old Man blocking
            Rs2NpcModel oldMan = rs2NpcCache.query().where(npc ->
                            "Strange Old Man".equals(npc.getName())
                                    && npc.hasLineOfSight())
                    .nearest();

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

    public void leaveTheMound() {
        log.info("Leaving mound.");
        Rs2GameObject.interact("Staircase", "Climb-up");
        /* Rs2TileObjectModel staircase = rs2TileObjectCache.query().where(rs2TileObjectModel -> rs2TileObjectModel.getName().equals("Staircase")).nearest();

        if (!hasLineOfSight(staircase)) {
            return;
        }


        if (Rs2GameObject.interact("Sarcophagus", "Search")) {
            sleepUntil(() -> hintNpcModel() != null || Rs2Dialogue.isInDialogue());
        }
        staircase.click("Climb-up");
        */
        sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() != 3);
        disablePrayer();
    }

    public void lootChampionScroll() {
        Rs2TileItemModel scroll = rs2TileItemCache.query()
                .where(x -> x.getId() == ItemID.CHAMPIONS_CHALLENGE_SKELETON)
                .nearest();

        if (scroll == null) {
            return;
        }

        scroll.click("Take");
        sleepUntil(() -> Rs2Inventory.contains(scroll.getId()));
    }

    public void gainPotential() {
        Optional<net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel> monsterToAttack = getNearestSkeletonOrBloodworm();

        if (monsterToAttack.isEmpty()) {
            log.info("No monster to account found. Skipping.");
            return;
        }

        log.info("Killing NPC to gain reward potential.");
        if (!Rs2Player.isInCombat()) {
            Rs2Npc.interact(monsterToAttack.get(), "attack");
            //Microbot.getClientThread().invoke(() -> monsterToAttack.int());
        }

        log.info("Waiting until in combat.");
        sleepUntil(Rs2Player::isInCombat);
        log.info("Waiting until interacting.");
        sleepUntil(Rs2Player::isInteracting);

        Actor interacting = Rs2Player.getInteracting();

        if (Objects.nonNull(interacting)) {
            while (!interacting.isDead()) {
                sleep(750, 1500);
                eatFood();
                antiPatternDropVials();
            }
        } else {
            log.info("Interacting is null?");
        }


        log.info("NPC killed.");

        sleep(600, 1800);
        lootChampionScroll();
    }

    private Optional<net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel> getNearestSkeletonOrBloodworm() {
        Rs2WorldPoint playerLocation = new Rs2WorldPoint(Microbot.getClient().getLocalPlayer().getWorldLocation());
        boolean isInstance = Microbot.getClient().getTopLevelWorldView().getScene().isInstance();
        var rs2Npc = Rs2Npc.getNpcs()
                .filter(Rs2Npc::hasLineOfSight)
                .filter(npc -> "skeleton".equalsIgnoreCase(npc.getName()) || "bloodworm".equalsIgnoreCase(npc.getName()))
                .min(Comparator.comparingInt((value) -> playerLocation.distanceToPath(isInstance ? Rs2WorldPoint.toLocalInstance(value.getWorldLocation()) : value.getWorldLocation())))
                .orElse(null);
        return Optional.ofNullable(rs2Npc);

        /*
        return rs2NpcCache.query().where(npc ->
                        ("skeleton".equalsIgnoreCase(npc.getName()) || "bloodworm".equalsIgnoreCase(npc.getName()))
                                && npc.hasLineOfSight()
                                && !npc.isDead())
                .nearest();
         */
    }

    public void swapTheSpellbook() {
        if (Rs2Magic.getSpellbook() == Rs2Spellbook.MODERN) {
            return;
        }

        WorldPoint swapLocation = Rs2Magic.getSpellbook().getSwitchLocation();
        if (Rs2Player.getWorldLocation().distanceTo(swapLocation) > 5) {
            Rs2Walker.walkTo(swapLocation);
        }

        Rs2Spellbook.MODERN.switchTo();
    }

    public void setAutoCast() {
        if ("Wrath rune".equals(neededRune)) {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_SURGE) {
                log.info("Setting autocast to wind surge.");
                Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_SURGE, false);
            }
        } else if ("Blood rune".equals(neededRune)) {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_WAVE) {
                log.info("Setting autocast to wind wave.");
                Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_WAVE, false);
            }
        } else if ("Death rune".equals(neededRune)) {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_BLAST) {
                log.info("Setting autocast to wind blast.");
                Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_BLAST, false);
            }
        }
    }

    public void activatePrayer(BarrowsBrother brother) {
        antiPatternEnableWrongPrayer(brother);

        if (!Rs2Prayer.isPrayerActive(brother.whatToPray)) {
            usePrayerRestorationIfNecessary(brother);
            if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) > 0) {
                Rs2Prayer.toggle(brother.whatToPray);
            }
        }
    }

    public void antiPatternEnableWrongPrayer(BarrowsBrother brother) {
        if (Rs2Prayer.isPrayerActive(brother.whatToPray)) {
            return;
        }

        if (Rs2Random.between(0, 100) > Rs2Random.between(1, 4)) {
            return;
        }

        Rs2PrayerEnum wrongPrayer;
        int random = Rs2Random.between(0, 100);

        if (random <= 50) {
            wrongPrayer = Rs2PrayerEnum.PROTECT_MELEE;
        } else if (random < 75) {
            wrongPrayer = Rs2PrayerEnum.PROTECT_RANGE;
        } else {
            wrongPrayer = Rs2PrayerEnum.PROTECT_MAGIC;
        }

        usePrayerRestorationIfNecessary(brother);
        Rs2Prayer.toggle(wrongPrayer);
        sleep(0, 750);
    }

    public void antiPatternDropVials() {
        if (Rs2Random.between(0, 100) > Rs2Random.between(1, 25)) {
            return;
        }

        Rs2ItemModel drop = Rs2Inventory.get(it ->
                it != null && (it.getName().contains("Vial") || it.getName().contains("Butterfly jar")));

        if (drop != null && Rs2Inventory.contains(drop.getName())) {
            if (Rs2Inventory.drop(drop.getName())) {
                sleep(0, 750);
            }
        }
    }

    public void disablePrayer() {
        log.info("Disabling prayers");
        sleep(600, 1200);
        Rs2Prayer.disableAllPrayers();
        sleep(0, 750);
    }

    public void usePrayerRestorationIfNecessary(BarrowsBrother brother) {
        log.info("All brothers killed? {}", BarrowsBrother.allBarrowsBrothersAreKilled());
        log.info("Weaker brother? {}", brother.isWeakerBrother());
        log.info("Prayer? {}", Rs2Player.getBoostedSkillLevel(Skill.PRAYER));
        if ((this.usePrayerAgainstWeakerBrother || !brother.isWeakerBrother()) && !BarrowsBrother.allBarrowsBrothersAreKilled()) {
            if (!isInBarrowsTunnel() && Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < Rs2Random.between(8, 15)) {
                usePrayerRestoration();
            } else if (isInBarrowsTunnel() && !brother.isWeakerBrother()) {
                if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < Rs2Random.between(8, 15)) {
                    usePrayerRestoration();
                }
            }
        }
    }

    private static void usePrayerRestoration() {
        Rs2ItemModel restore = Rs2Inventory.get(it ->
                it != null && (it.getName().contains("Prayer potion") || it.getName().contains("Moonlight moth")));

        if (restore == null) {
            log.info("Couldn't find Prayer potion or moonlight moth.");
            return;
        }

        String action = restore.getName().contains("Moonlight moth") ? "Release" : "Drink";
        log.info("Restoring prayer.");
        Rs2Inventory.interact(restore, action);
        sleep(0, 750);
    }

    public Rs2NpcModel hintNpcModel() {
        Optional<NPC> hintNpc = Microbot.getClientThread().runOnClientThreadOptional(
                () -> Microbot.getClient().getHintArrowNpc()
        );

        return hintNpc.map(Rs2NpcModel::new).orElse(null);
    }

    public void checkForAndFightBrother(BarrowsConfig config, BarrowsBrother barrowsBrother) {
        changeEquipment(config, barrowsBrother);

        activatePrayer(barrowsBrother);

        Rs2NpcModel npc = hintNpcModel();
        Rs2Npc.interact(npc.getId(), "attack");
        //Microbot.getClientThread().invoke(() -> npc.click());
        sleep(750, 1500);

        while (!npc.isDead()) {
            sleep(750, 1500);
            usePrayerRestorationIfNecessary(barrowsBrother);
            eatFood();
            antiPatternDropVials();
        }

        log.info("Brother has been killed.");
        disablePrayer();
    }

    private boolean shouldGainMorePotential() {
        int rp = Microbot.getVarbitValue(VarbitID.BARROWS_KILLED_MONSTER);
        log.info("Reward potential: {}", rp);
        if (shouldAttackSkeleton && (everyBrotherWasKilled() && rp < 870 || BarrowsBrother.getNumberOfBarrowsBrothersKilled() == 5 && rp < 790)) {
            return getNearestSkeletonOrBloodworm().isPresent();
        }

        return false;
    }

    private void handleTunnels(BarrowsConfig config) {
        if (Rs2Player.getQuestState(Quest.HIS_FAITHFUL_SERVANTS) != QuestState.FINISHED) {
            throw new BarrowsScriptException("Quest 'His faithful servants' is not finished");
        }

        Rs2InventorySetup inventorySetup = new Rs2InventorySetup(config.inventorySetupTunnels(), mainScheduledFuture);

        /* if (!inventorySetup.doesEquipmentMatch() && !inventorySetup.loadEquipment()) {
            throw new BarrowsScriptException("Could not load the right equipment for tunnels" );
        } */
        inventorySetup.wearEquipment();
        sleep(600, 1800);

        if (isInBarrowsTunnel()) {
            GameObject chest = Rs2GameObject.getGameObject(CHEST_ID);
            if (hintNpcModel() != null) {
                resetChestWalker();
                checkForAndFightBrother(config, BarrowsBrother.getFinalBarrowsBrother());
            } else if (shouldGainMorePotential()) {
                resetChestWalker();
                gainPotential();
            } else if (getPuzzleWidget().isPresent()) {
                resetChestWalker();
                solvePuzzle();
            } else if (Rs2GameObject.hasLineOfSight(chest) && Rs2Player.distanceTo(chest.getWorldLocation()) < 6) {
                log.info("Close to the chest.");
                resetChestWalker();
                setState(State.CHEST);
            } else if (walkToChestFuture == null || walkToChestFuture.isCancelled() && !walkToChestFuture.isDone()) {
                //running on separate thread, to ensure that we are continue checking things while walking towards chest
                walkToChestFuture = scheduledExecutorService.scheduleWithFixedDelay(
                        this::walkToChest,
                        0,
                        Rs2Random.between(600, 3600),
                        TimeUnit.MILLISECONDS
                );
            }
        }
    }

    private void resetChestWalker() {
        log.info("Interrupted walking to chest.");
        if (walkToChestFuture != null && !walkToChestFuture.isCancelled()) {
            walkToChestFuture.cancel(true);
            walkToChestFuture = null;
            Rs2Walker.setTarget(null);
        }
    }

    private void walkToChest() {
        if (walkToChestFuture != null && !walkToChestFuture.isCancelled() && super.isRunning()) {
            log.info("Walking towards chest.");
            Rs2Walker.walkTo(CHEST_LOCATION);
        }
    }

    public void drinkForgottenBrew() {
        if (!Rs2Inventory.contains(it -> it != null && it.getName().contains("Forgotten brew"))) {
            return;
        }

        int threshold = Rs2Player.getRealSkillLevel(Skill.MAGIC) + Rs2Random.between(1, 4);
        if (Rs2Player.getBoostedSkillLevel(Skill.MAGIC) > threshold) {
            return;
        }

        String[] priority = {"Forgotten brew(1)", "Forgotten brew(2)", "Forgotten brew(3)", "Forgotten brew(4)"};
        for (String brew : priority) {
            if (Rs2Inventory.contains(brew) && Rs2Inventory.interact(brew, "Drink")) {
                sleep(300, 1000);
                return;
            }
        }
    }

    public void eatFood() {
        if (Rs2Player.getHealthPercentage() > 70) {
            return;
        }

        log.info("Eating food.");
        Rs2ItemModel food = Rs2Inventory.get(it -> it != null && it.isFood());
        if (food != null) {
            Rs2Inventory.interact(food, "Eat");
            sleep(600, 800);
        }
    }

    /*
     public void solvePuzzle(){
        //correct model ids are  6725, 6731, 6713, 6719
        //widget ids are 1638413, 1638415,1638417
        boolean stoppedTheWalker = false;

        int widgets[] = {1638413, 1638415, 1638417};
        int modelIDs[] = {6725, 6731, 6713, 6719};
        int random = Rs2Random.between(0,1000);
        int secondRandom = Rs2Random.between(1,10);

        sleepUntil(()-> Rs2Widget.getWidget(widgets[0]) != null ||
                Rs2Widget.getWidget(widgets[1]) != null ||
                Rs2Widget.getWidget(widgets[2]) != null, Rs2Random.between(300,800));

        for (int widget : widgets) {
            if(!super.isRunning()) break;

            if(Rs2Widget.getWidget(widget)!=null){
                if(!stoppedTheWalker){
                    stopFutureWalker();
                    stoppedTheWalker = true;
                }
                for (int modelID : modelIDs) {
                    if(!super.isRunning()) break;

                    if(Rs2Widget.getWidget(widget).getModelId() == modelID || random <= secondRandom){
                        Microbot.log("Solution found");
                        Rs2Widget.clickWidget(widget);
                        break;
                    }
                }
            } else {
                break;
            }
        }

    }
     */
    public void solvePuzzle() {
        log.info("Solving puzzle");
        Optional<Widget> puzzleWidgetOptional = getPuzzleWidget();

        puzzleWidgetOptional.ifPresent(widget -> {
            sleep(100, 600);
            Rs2Widget.clickWidget(widget.getId());
        });
    }

    private Optional<Widget> getPuzzleWidget() {
        return PUZZLE_WIDGET_IDS
                .stream()
                .map(Rs2Widget::getWidget)
                .filter(Objects::nonNull)
                .filter(rs2Widget -> PUZZLE_MODELS_IDS.contains(rs2Widget.getModelId()))
                .findFirst();
    }

    private boolean enoughBrothersKilledToTunnel() {
        return Arrays.stream(BarrowsBrother.values()).filter(BarrowsBrother::hasBeenKilled).count() >= 5;
    }

    private Optional<BarrowsBrother> findBrotherByName(String name) {
        return Arrays.stream(BarrowsBrother.values()).filter(brother -> brother.getName().equals(name)).findFirst();
    }

    private boolean brotherInsideOfTunnel(BarrowsBrother brother) {
        //Rs2TileObjectQueryable sarcophagus = rs2TileObjectCache.query().where(rs2TileObjectModel -> rs2TileObjectModel.getName().equals("Sarcophagus"));

       // if (sarcophagus.interact("Search")) {
        if (Rs2GameObject.interact("Sarcophagus", "Search")) {
            sleepUntil(() -> hintNpcModel() != null || Rs2Dialogue.isInDialogue());
        }

        if (Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasDialogueText("You've found a hidden")) {
            log.info("Found the crypt for brother: {}", brother.getName());
            brotherInTunnel = brother.getName();
            return true;
        }

        return false;
    }

    private void bankRefill(BarrowsConfig config) {
        Rs2Food ourFood = config.food();
        int foodId = ourFood.getId();

        Set<String> itemsToKeep = new HashSet<>();
        itemsToKeep.addAll(config.inventorySetupMelee().getInventory().stream().map(InventorySetupsItem::getName).collect(Collectors.toSet()));
        itemsToKeep.addAll(config.inventorySetupMelee().getInventory().stream().map(InventorySetupsItem::getName).collect(Collectors.toSet()));
        itemsToKeep.addAll(config.inventorySetupTunnels().getInventory().stream().map(InventorySetupsItem::getName).collect(Collectors.toSet()));
        itemsToKeep.addAll(Set.of(
                neededRune,
                "Moonlight moth",
                "Moonlight moth mix (2)",
                "Teleport to house",
                "Spade",
                "Prayer potion(4)",
                "Prayer potion(3)",
                "Forgotten brew(4)",
                "Forgotten brew(3)",
                "Barrows teleport",
                "Rune pouch")
        );

        Rs2Bank.depositAllExcept(itemsToKeep);

        if (!usingPoweredStaffs) {
            //checkRunes(config); TODO: rune pouch check
        } else if (outOfPoweredStaffCharges) {
            throw new BarrowsScriptException("Out of charges on staff");
        }

        checkPrayerRestorationPotions(config);
        checkFood(config, foodId);
        checkSpade();
        checkRingOfDueling();
    }

    private void checkRunes(BarrowsConfig config) {
        if (Rs2Inventory.get(neededRune) == null || Rs2Inventory.get(neededRune).getQuantity() <= config.minRuneAmount()) {
            if (Rs2Bank.getBankItem(neededRune) != null && Rs2Bank.getBankItem(neededRune).getQuantity() > config.minRuneAmount()) {
                int max = Rs2Bank.getBankItem(neededRune).getQuantity();
                int withdraw = Rs2Random.between(config.minRuneAmount(), Math.max(config.minRuneAmount(), max));
                if (Rs2Bank.withdrawX(neededRune, withdraw)) {
                    sleepUntil(() ->
                    {
                        Rs2ItemModel r = Rs2Inventory.get(neededRune);
                        return r != null && r.getQuantity() > config.minRuneAmount();
                    });
                }
            } else {
                throw new BarrowsScriptException("Out of runes for the spell.");
            }
        }
    }

    private void checkPrayerRestorationPotions(BarrowsConfig config) {
        int prayerId = config.prayerRestoreType().getPrayerRestoreTypeID();
        if (Rs2Inventory.count(prayerId) < Rs2Random.between(config.minPrayerPots(), config.targetPrayerPots())) {
            if (Rs2Bank.getBankItem(prayerId) != null && Rs2Bank.getBankItem(prayerId).getQuantity() >= config.targetPrayerPots()) {
                int want = Rs2Random.between(config.minPrayerPots(), config.targetPrayerPots());
                int amt = want - Rs2Inventory.count(prayerId);
                amt = Math.max(1, amt);

                if (Rs2Bank.withdrawX(prayerId, amt)) {
                    sleepUntil(() -> Rs2Inventory.count(prayerId) > Rs2Random.between(4, 8));
                }
            } else {
                throw new BarrowsScriptException("Out of prayer restoration potions");
            }
        }
    }

    private void checkFood(BarrowsConfig config, int foodId) {
        if (Rs2Inventory.count(foodId) < config.targetFoodAmount()) {
            if (Rs2Bank.getBankItem(foodId) != null && Rs2Bank.getBankItem(foodId).getQuantity() >= config.targetFoodAmount()) {
                int want = Rs2Random.between(config.minFood(), config.targetFoodAmount());
                int amt = want - Rs2Inventory.count(foodId);
                amt = Math.max(1, amt);

                Rs2Bank.withdrawX(foodId, amt);
            } else {
                throw new BarrowsScriptException("Out of food");
            }
        }
    }

    private void checkSpade() {
        if (!Rs2Inventory.contains("Spade")) {
            if (Rs2Bank.getBankItem("Spade") != null && Rs2Bank.getBankItem("Spade").getQuantity() >= 1) {
                Rs2Bank.withdrawOne("Spade");
                sleepUntil(() -> Rs2Inventory.contains("Spade"));
                sleep(300, 1000);
            } else {
                throw new BarrowsScriptException("No spade found.");
            }
        }
    }

    private void checkRingOfDueling() {
        if (Rs2Equipment.get(EquipmentInventorySlot.RING) == null) {
            if (Rs2Bank.count(ItemID.RING_OF_DUELING_8) <= 0) {
                throw new BarrowsScriptException("No ring of dueling found in bank.");
            }

            if (!Rs2Inventory.contains(ItemID.RING_OF_DUELING_8)) {
                if (Rs2Bank.withdrawX(ItemID.RING_OF_DUELING_8, 1)) {
                    sleepUntil(() -> Rs2Inventory.contains(ItemID.RING_OF_DUELING_8));
                }
            }

            if (Rs2Inventory.contains(ItemID.RING_OF_DUELING_8) && Rs2Inventory.interact(ItemID.RING_OF_DUELING_8, "Wear")) {
                sleepUntil(() ->
                {
                    Rs2ItemModel ring = Rs2Equipment.get(EquipmentInventorySlot.RING);
                    return ring != null && ring.getName().contains("dueling");
                });
            }
        }
    }

    private void recordBarrowsPiece() {
        Rs2ItemModel piece = Rs2Inventory.get(it -> it != null && it.getName().contains("'s"));
        if (piece != null) {
            barrowsPieces.add(piece.getName());
            barrowsPieces.remove("Nothing yet.");
        }
    }

    private void setState(State state) {
        log.info("Setting state to {}", state.name());
        this.state = state;
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
        private final Rs2WorldArea humpWP;
        private final Rs2PrayerEnum whatToPray;
        private final int varbit;
        private final boolean needsMeleeGear;

        BarrowsBrother(String name, Rs2WorldArea humpWP, Rs2PrayerEnum whatToPray, int varbit, boolean needsMeleeGear) {
            this.name = name;
            this.humpWP = humpWP;
            this.whatToPray = whatToPray;
            this.varbit = varbit;
            this.needsMeleeGear = needsMeleeGear;
        }

        public boolean hasBeenKilled() {
            return Microbot.getVarbitValue(this.varbit) == 1;
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

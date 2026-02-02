package net.runelite.client.plugins.custom.royaltitans;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.player.Rs2PlayerCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetupsItem;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.shared.FeroxService;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.custom.royaltitans.RoyalTitansShared.FIRE_TITAN_DEAD_ID;
import static net.runelite.client.plugins.custom.royaltitans.RoyalTitansShared.FIRE_TITAN_ID;
import static net.runelite.client.plugins.custom.royaltitans.RoyalTitansShared.ICE_TITAN_DEAD_ID;
import static net.runelite.client.plugins.custom.royaltitans.RoyalTitansShared.ICE_TITAN_ID;
import static net.runelite.client.plugins.custom.royaltitans.RoyalTitansShared.evaluateAndConsumePotions;
import static net.runelite.client.plugins.custom.royaltitans.RoyalTitansShared.lootedTitanLastIteration;
import static net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity.EXTREME;
import static net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer.disableAllPrayers;

@Slf4j
public class RoyalTitansScript extends Script {

    private static final Integer MELEE_TITAN_ICE_REGION_X = 34;
    private static final Integer MELEE_TITAN_FIRE_REGION_X = 26;
    private static final Integer FIRE_MINION_ID = 14150;
    private static final Integer ICE_MINION_ID = 14151;
    private static final Integer FIRE_WALL = 14152;
    private static final Integer ICE_WALL = 14153;
    private static final Integer TUNNEL_ID = 55986;
    private static final Integer TUNNEL_ID_ESCAPE = 55987;
    private static final Integer WIDGET_START_A_FIGHT = 14352385;
    private static final WorldPoint BOSS_LOCATION = new WorldPoint(2951, 9574, 0);
    private static final WorldArea FIGHT_AREA = new WorldArea(new WorldPoint(2909, 9561, 0), 12, 4);

    @Getter
    private RoyalTitansBotStatus state = RoyalTitansBotStatus.TRAVELLING;
    @Getter
    @Setter
    private String subState = "";
    @Getter
    private int kills = 0;

    @Inject
    private FeroxService feroxService;

    @Inject
    private Rs2NpcCache rs2NpcCache;

    @Inject
    private Rs2TileObjectCache rs2TileObjectCache;

    @Inject
    private Rs2PlayerCache rs2PlayerCache;

    private Rs2InventorySetup inventorySetup = null;
    private Rs2InventorySetup magicInventorySetup = null;
    private Rs2InventorySetup meleeInventorySetup = null;
    private Rs2InventorySetup specialAttackInventorySetup = null;
    private Rs2InventorySetup rangedInventorySetup = null;
    private RoyalTitansTravelStatus travelStatus = RoyalTitansTravelStatus.TO_BANK;
    private Instant waitingTimeStart = null;
    private boolean waitedLastIteration = false;
    private boolean isRunning;
    private boolean isAtSecondPhase = false;
    private double fireTitanHealthPercentage = 100;
    private double iceTitanHealthPercentage = 100;

    private final AtomicReference<Tile> enrageTile = new AtomicReference<>(null);
    private final AtomicReference<Rs2NpcModel> titanToFocusOn = new AtomicReference<>(null);

    static {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.simulateFatigue = false;
        Rs2AntibanSettings.simulateAttentionSpan = false;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.nonLinearIntervals = true;
        Rs2AntibanSettings.dynamicActivity = false;
        Rs2AntibanSettings.profileSwitching = false;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.moveMouseOffScreen = false;
        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.04;
        Rs2Antiban.setActivityIntensity(EXTREME);
    }

    public boolean run(RoyalTitansConfig config) {
        isRunning = true;
        enrageTile.set(null);
        waitingTimeStart = null;
        travelStatus = RoyalTitansTravelStatus.TO_BANK;
        state = RoyalTitansBotStatus.TRAVELLING;
        Microbot.enableAutoRunOn = false;

        if (config.overrideState()) {
            state = config.startState();
        }

        log.info("Running as solo? {}", config.soloMode());
        log.info("Titan to focus on? {}", config.royalTitanToFocus());
        log.info("Minions to focus on? {}", config.minionResponsibility());

        meleeInventorySetup = new Rs2InventorySetup(config.meleeEquipment(), mainScheduledFuture);
        magicInventorySetup = new Rs2InventorySetup(config.magicEquipment(), mainScheduledFuture);
        rangedInventorySetup = new Rs2InventorySetup(config.rangedEquipment(), mainScheduledFuture);
        specialAttackInventorySetup = new Rs2InventorySetup(config.specialAttackWeapon(), mainScheduledFuture);
        inventorySetup = new Rs2InventorySetup(config.inventorySetup(), mainScheduledFuture);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run() || !isRunning || !this.isRunning()) {
                    return;
                }

                switch (state) {
                    case BANKING:
                        handleBanking(config);
                        break;
                    case TRAVELLING:
                        handleTravelling(config);
                        break;
                    case WAITING:
                        handleWaiting(config);
                        break;
                    case FIGHTING:
                        handleFighting(config);
                        break;
                }
            } catch (Exception e) {
                log.error("Exception", e);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run() || !isRunning || !this.isRunning()) {
                    return;
                }

                detectState(config);
            } catch (Exception e) {
                log.error("Error detecting state", e);
            }
        }, 10000, 10000, TimeUnit.MILLISECONDS);

        return true;
    }

    public void resetTitanToFocusOn() {
        this.titanToFocusOn.set(null);
    }

    public void resetEnragedTile() {
        this.enrageTile.set(null);
    }

    public void setEnragedTile(Tile tile) {
        this.enrageTile.set(tile);
    }

    public void increaseKillCount() {
        this.kills++;
    }

    public Tile getEnrageTile() {
        return enrageTile.get();
    }

    /**
     * Handles edgecases where the bot get stuck due to the other players actions
     *
     * @param config
     */
    private void detectState(RoyalTitansConfig config) {
        if (RoyalTitansShared.isInBossRegion() && state != RoyalTitansBotStatus.FIGHTING) {
            state = RoyalTitansBotStatus.FIGHTING;
        }
        if (state == RoyalTitansBotStatus.FIGHTING && !RoyalTitansShared.isInBossRegion()) {
            state = RoyalTitansBotStatus.TRAVELLING;
            travelStatus = RoyalTitansTravelStatus.TO_INSTANCE;
        }
    }

    private void equipArmor(Rs2InventorySetup inventorySetup) {
        inventorySetup.wearEquipment();
    }

    private void handleWaiting(RoyalTitansConfig config) {
        isAtSecondPhase = false;
        resetEnragedTile();
        resetTitanToFocusOn();
        if (config.soloMode()) {
            state = RoyalTitansBotStatus.TRAVELLING;
            travelStatus = RoyalTitansTravelStatus.TO_INSTANCE;
            evaluateAndConsumePotions(config);
            sleep(1200, 2400);
            return;
        }
        var teammate = rs2PlayerCache.query().withName(config.teammateName()).nearestOnClientThread();
        if (waitingTimeStart == null && teammate == null && !waitedLastIteration) {
            waitingTimeStart = Instant.now();
            waitedLastIteration = true;
            return;
        }
        if (teammate != null) {
            if (teammate.getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) < 5) {
                waitedLastIteration = false;
                waitingTimeStart = null;
                state = RoyalTitansBotStatus.TRAVELLING;
                travelStatus = RoyalTitansTravelStatus.TO_INSTANCE;
                evaluateAndConsumePotions(config);
                sleep(1200, 2400);
                return;
            }

        }

        if (waitingTimeStart != null && teammate == null && Instant.now().isAfter(waitingTimeStart.plusSeconds(config.waitingTimeForTeammate()))) {
            log.info("Teammate did not show after {} seconds, shutting down", config.waitingTimeForTeammate());
            shutdown();
        }
    }

    private void handleFighting(RoyalTitansConfig config) {
        findSafeTile();
        handleEscaping(config);
        handleEating(config);
        handlePrayers(config);
        handleMinions(config);
        handleWalls(config);
        attackBoss(config);
    }

    private void handleEating(RoyalTitansConfig config) {
        subState = "Handling eating";
        Rs2Player.eatAt(config.minEatPercent());
        Rs2Player.drinkPrayerPotionAt(config.minPrayerPercent());
    }

    private void handleEscaping(RoyalTitansConfig config) {
        var shouldLeave = false;
        int currentHealth = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        boolean noFood = Rs2Inventory.getInventoryFood().isEmpty();
        boolean noPrayerPotions = Rs2Inventory.items()
                .noneMatch(item -> item != null && item.getName() != null && !Rs2Potion.getPrayerPotionsVariants().contains(item.getName()));
        var teammate = rs2PlayerCache.query().withName(config.teammateName()).nearestOnClientThread();
        if (teammate != null) {
            waitingTimeStart = null;
        }
        if (teammate == null && waitingTimeStart == null && config.resupplyWithTeammate()) {
            waitingTimeStart = Instant.now();
        } else if (config.resupplyWithTeammate() && teammate == null && Instant.now().isAfter(waitingTimeStart.plusSeconds(60))) {
            shouldLeave = true;
        }
        if ((noFood && currentHealth <= config.healthThreshold()) || (noPrayerPotions && currentPrayer < 10)) {
            shouldLeave = true;
        }

        var iceTitanDead = rs2NpcCache.query().withId(ICE_TITAN_DEAD_ID).nearestOnClientThread(20);
        var fireTitanDead = rs2NpcCache.query().withId(FIRE_TITAN_DEAD_ID).nearestOnClientThread(20);
        if (shouldLeave && iceTitanDead != null && fireTitanDead != null && !lootedTitanLastIteration) {
            log.info("We want to escape, but Titans are dead, lets loot first");
        }
        if (shouldLeave) {
            if (config.emergencyTeleport() != 0) {
                enrageTile.set(null);
                feroxService.restoreAtFerox();
            } else {
                enrageTile.set(null);
                var tunnel = rs2TileObjectCache.query().withId(TUNNEL_ID_ESCAPE).nearestOnClientThread(20);
                if (tunnel != null) {
                    tunnel.click("Quick-escape");
                    //Microbot.getClientThread().invoke(() -> tunnel.click("Quick-escape"));
                }
                Rs2Bank.walkToBank();
            }
            state = RoyalTitansBotStatus.TRAVELLING;
            travelStatus = RoyalTitansTravelStatus.TO_BANK;
            Rs2Prayer.disableAllPrayers();
        }
    }

    private void handlePrayers(RoyalTitansConfig config) {
        subState = "Handling prayers";
        handleOffensivePrayers(config);
        if (Rs2Combat.inCombat()) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
            return;
        }
        if (rs2NpcCache.query().where(x -> x.getId() == ICE_TITAN_ID || x.getId() == FIRE_TITAN_ID).nearestOnClientThread() != null) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
            return;
        }
        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, false);
    }

    private void handleOffensivePrayers(RoyalTitansConfig config) {
        var bestMeleePrayer = Rs2Prayer.getBestMeleePrayer();
        var bestRangedPrayer = Rs2Prayer.getBestRangePrayer();

        if (getEnrageTile() != null) {
            Rs2Prayer.toggle(bestMeleePrayer, false);
            Rs2Prayer.toggle(bestRangedPrayer, false);
        } else if (config.enableOffensivePrayer() && Rs2Player.isInCombat()) {
            if (titanToFocusOn.get() != null) {
                if (titanIsWithinMeleeDistance()) {
                    if (!titanToFocusOn.get().isReachable()) {
                        destroyWalls(config);
                    } else if (bestMeleePrayer != null) {
                        if (!Rs2Prayer.isPrayerActive(bestMeleePrayer)) {
                            Rs2Prayer.toggle(bestMeleePrayer, true);
                        }
                    }
                } else if (bestRangedPrayer != null) {
                    if (!Rs2Prayer.isPrayerActive(bestRangedPrayer)) {
                        Rs2Prayer.toggle(bestRangedPrayer, true);
                    }
                }
            }
        }
    }

    private void attackBoss(RoyalTitansConfig config) {
        var iceTitan = rs2NpcCache.query().withId(ICE_TITAN_ID).nearestOnClientThread(20);
        var fireTitan = rs2NpcCache.query().withId(FIRE_TITAN_ID).nearestOnClientThread(20);
        if (iceTitan == null && fireTitan == null) {
            log.info("No titans found");
            iceTitanHealthPercentage = 100;
            fireTitanHealthPercentage = 100;
            return;
        }
        lootedTitanLastIteration = false;
        handleBossFocus(config, iceTitan, fireTitan);
    }

    private boolean isDangerousTile(WorldPoint location) {
        return Rs2Tile.getDangerousGraphicsObjectTiles().containsKey(location);
    }

    private void findSafeTile() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();

        if (isDangerousTile(playerLocation)) {
            log.info("Finding safe tile");
            List<WorldPoint> nearbyTiles = new ArrayList<>();

            int x = playerLocation.getX();
            int y = playerLocation.getY();
            int plane = playerLocation.getPlane();

            nearbyTiles.add(playerLocation);

            // Offset X
            for (int dx : List.of(-2, -1, 1, 2)) {
                nearbyTiles.add(new WorldPoint(x + dx, y, plane));
            }

            // Offset Y
            for (int dy : List.of(-2, -1, 1, 2)) {
                nearbyTiles.add(new WorldPoint(x, y + dy, plane));
            }

            // Offset X and Y (diagonal)
            for (int dx : List.of(-2, -1, 1, 2)) {
                for (int dy : List.of(-2, -1, 1, 2)) {
                    nearbyTiles.add(new WorldPoint(x + dx, y + dy, plane));
                }
            }

            var safeTile = nearbyTiles.stream().filter(tile -> FIGHT_AREA.contains(tile) && !isDangerousTile(tile)).findFirst();
            if (safeTile.isPresent()) {
                Rs2Walker.walkFastCanvas(safeTile.get());
                sleepUntil(() -> Rs2Player.getWorldLocation().equals(safeTile.get()));
            }
        }

    }


    private void handleSpecialAttacks(RoyalTitansConfig config, Rs2NpcModel titan) {
        if (!config.useSpecialAttacks()) {
            return;
        }
        var specEnergy = Rs2Combat.getSpecEnergy() / 10;
        if (specEnergy < config.specEnergyConsumed()) {
            return;
        }
        if (!isTitanAlive(titan)) {
            return;
        }
        // Failsafe to handle special attack weapons that require to unequip 2 items
        if (Rs2Inventory.isFull()) {
            return;
        }
        if (getEnrageTile() != null) {
            return;
        }

        // We assume that if we are currently wearing melee armor, it's okay to use a melee special attack weapon. This avoids all of the other targeting logic being duplicated
        if (meleeInventorySetup.doesEquipmentMatch() && config.specialAttackWeaponStyle() == RoyalTitansConfig.SpecialAttackWeaponStyle.MELEE) {
            specialAttackInventorySetup.wearEquipment();
            Rs2Combat.setSpecState(true, config.specEnergyConsumed() * 10);
            sleepUntil(Rs2Combat::getSpecState);
            titan.click("attack");
            Rs2Player.waitForAnimation(600);
            return;
        }
        if ((magicInventorySetup.doesEquipmentMatch() || rangedInventorySetup.doesEquipmentMatch()) && config.specialAttackWeaponStyle() == RoyalTitansConfig.SpecialAttackWeaponStyle.RANGED) {
            specialAttackInventorySetup.wearEquipment();
            Rs2Combat.setSpecState(true, config.specEnergyConsumed() * 10);
            sleepUntil(Rs2Combat::getSpecState);
            titan.click("attack");
            Rs2Player.waitForAnimation(600);
        }
    }

    private boolean isTitanAlive(Rs2NpcModel titan) {
        return titan != null && !titan.isDead();
    }

    private void handleBossFocus(RoyalTitansConfig config, Rs2NpcModel iceTitan, Rs2NpcModel fireTitan) {
        // Handle enrage tile first
        if (getEnrageTile() != null) {
            log.info("Enraged tile is present.");
            subState = "Handling enrage tile";

            if (Rs2Player.getLocalLocation().equals(getEnrageTile().getLocalLocation()) && isTitanAlive(fireTitan) && isTitanAlive(iceTitan)) {
                equipArmor(rangedInventorySetup);
                titanToFocusOn.get().click("attack");
            }
        } else {
            // Solo mode - balance titan health
            if (config.soloMode()) {
                subState = "Solo mode - balancing titan health";
                updateHealthPercentages(iceTitan, fireTitan);
                if (!isTitanAlive(titanToFocusOn.get())) {
                    log.info("No titan selected. Selecting titan to focus on.");
                    var titan = selectTitanForSoloMode(iceTitan, fireTitan);
                    if (titan != null) {
                        titanToFocusOn.set(titan); //This is to prevent the player from swapping between titans in a single phase, so you don't lose too much DPS
                    }
                }
                if (isTitanAlive(titanToFocusOn.get())) {
                    // Select appropriate gear based on titan position
                    if (titanIsWithinMeleeDistance()) {
                        log.info("Titan is in melee distance. Equipping melee gear.");
                        equipArmor(meleeInventorySetup);
                    } else {
                        log.info("Titan is not in melee distance. Equipping ranging gear.");
                        equipArmor(rangedInventorySetup);
                    }

                    NPC attackingNpc = (NPC) Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getInteracting());
                    if (attackingNpc == null || titanToFocusOn.get().getIndex() != attackingNpc.getIndex()) {
                        if (attackingNpc != null) {
                            log.info("Attacking NPC? {}, {}", attackingNpc.getId(), attackingNpc.getIndex());
                        } else {
                            log.info("Attacking NPC is null.");
                        }
                        log.info("Titan: {}", titanToFocusOn.get());
                        titanToFocusOn.get().click("attack");
                    }
                }
            } else {
                if (fireTitanShouldBeAttacked(config, fireTitan, iceTitan)) {
                    subState = "Attacking fire titan";
                    if (fireTitanCanBeAttackedWithMelee(fireTitan)) {
                        equipArmor(meleeInventorySetup);
                    } else {
                        equipArmor(rangedInventorySetup);
                    }
                    fireTitan.click("attack");
                } else if (iceTitanShouldBeAttacked(config, iceTitan, fireTitan)) {
                    subState = "Attacking ice titan";
                    if (iceTitanCanBeAttackedWithMelee(iceTitan)) {
                        equipArmor(meleeInventorySetup);
                    } else {
                        equipArmor(rangedInventorySetup);
                    }
                    iceTitan.click("attack");
                }
            }

        }
    }

    private boolean iceTitanShouldBeAttacked(RoyalTitansConfig config, Rs2NpcModel iceTitan, Rs2NpcModel fireTitan) {
        return (config.royalTitanToFocus() == RoyalTitansConfig.RoyalTitan.ICE_TITAN && isTitanAlive(iceTitan)) || !isTitanAlive(fireTitan);
    }

    private boolean fireTitanShouldBeAttacked(RoyalTitansConfig config, Rs2NpcModel fireTitan, Rs2NpcModel iceTitan) {
        return (config.royalTitanToFocus() == RoyalTitansConfig.RoyalTitan.FIRE_TITAN && isTitanAlive(fireTitan)) || !isTitanAlive(iceTitan);
    }

    private boolean fireTitanCanBeAttackedWithMelee(Rs2NpcModel fireTitan) {
        int fireX = fireTitan.getWorldLocation().getRegionX();
        return fireX == MELEE_TITAN_FIRE_REGION_X ||
                (fireX > MELEE_TITAN_FIRE_REGION_X && fireX < MELEE_TITAN_ICE_REGION_X);
    }

    private boolean iceTitanCanBeAttackedWithMelee(Rs2NpcModel iceTitan) {
        int iceX = iceTitan.getWorldLocation().getRegionX();
        return iceX == MELEE_TITAN_ICE_REGION_X ||
                (iceX > MELEE_TITAN_FIRE_REGION_X && iceX < MELEE_TITAN_ICE_REGION_X);
    }

    private boolean titanIsWithinMeleeDistance() {
        int titanX = titanToFocusOn.get().getWorldLocation().getRegionX();
        return (titanToFocusOn.get().getId() == FIRE_TITAN_ID && titanX >= 26) ||
                (titanToFocusOn.get().getId() == ICE_TITAN_ID && titanX <= 36);
    }

    private void handleWalls(RoyalTitansConfig config) {
        if (config.minionResponsibility() == RoyalTitansConfig.Minions.NONE) {
            return;
        }
        subState = "Handling walls";

        destroyWalls(config);
    }

    private void destroyWalls(RoyalTitansConfig config) {
        // For solo mode, handle both types of walls
        List<Rs2NpcModel> walls;
        if (config.soloMode() || config.minionResponsibility() == RoyalTitansConfig.Minions.ALL) {
            List<Rs2NpcModel> fireWalls = rs2NpcCache.query().withId(FIRE_WALL).where(npcInCenterOfArena()).toListOnClientThread();
            List<Rs2NpcModel> iceWalls = rs2NpcCache.query().withId(ICE_WALL).where(npcInCenterOfArena()).toListOnClientThread();
            walls = new ArrayList<>();
            walls.addAll(fireWalls);
            walls.addAll(iceWalls);
        } else {
            walls = rs2NpcCache.query().withId(config.minionResponsibility() == RoyalTitansConfig.Minions.FIRE_MINIONS ? FIRE_WALL : ICE_WALL).where(npcInCenterOfArena()).toListOnClientThread();
        }

        if (walls.isEmpty() || walls.size() < 8) {
            log.info("No walls to get rid of.");
            return;
        }

        log.info("Equipping magic armour to get rid of walls.");
        equipArmor(magicInventorySetup);

        for (var wall : walls) {
            if (wall != null && wall.getId() != -1 && !wall.isDead()) {
                String action = wall.getId() == FIRE_WALL ? "Douse" : "Melt";
                log.info("Getting rid of wall.");
                wall.click(action);
            }
        }

        sleep(600);
    }

    @Nonnull
    private static Predicate<Rs2NpcModel> npcInCenterOfArena() {
        return npc -> npc.getWorldLocation().getRegionX() == 31;
    }

    private void handleMinions(RoyalTitansConfig config) {
        if (config.minionResponsibility() == RoyalTitansConfig.Minions.NONE) {
            return;
        }
        subState = "Handling minions";

        // For solo mode, handle both types of minions
        List<Rs2NpcModel> minions = new ArrayList<>();
        if (config.soloMode()) {
            var fireMinion = rs2NpcCache.query().withId(FIRE_MINION_ID).nearestOnClientThread(2);
            var iceMinion = rs2NpcCache.query().withId(ICE_MINION_ID).nearestOnClientThread(2);
            if (fireMinion != null) {
                minions.add(fireMinion);
            }

            if (iceMinion != null) {
                minions.add(iceMinion);
            }
        } else {
            var minion = rs2NpcCache.query().withId(config.minionResponsibility() == RoyalTitansConfig.Minions.FIRE_MINIONS ? FIRE_MINION_ID : ICE_MINION_ID).nearestOnClientThread(12);
            if (minion != null) {
                minions.add(minion);
            }
        }

        if (minions.isEmpty()) {
            log.info("No minions to get rid of.");
            return;
        }

        if (!magicInventorySetup.doesEquipmentMatch()) {
            equipArmor(magicInventorySetup);
        }

        for (var minion : minions) {
            if (minion != null && !minion.isDead()) {
                log.info("Attacking minion");
                minion.click("attack");
                //Microbot.getClientThread().invoke(() -> minion.click("attack"));
                sleep(600);
            }
        }
    }

    private void updateHealthPercentages(Rs2NpcModel iceTitan, Rs2NpcModel fireTitan) {
        if (lootedTitanLastIteration || (!isTitanAlive(iceTitan) && !isTitanAlive(fireTitan))) {
            iceTitanHealthPercentage = 100;
            fireTitanHealthPercentage = 100;
            return;
        }

        if (iceTitanHealthPercentage != 100 && !isTitanAlive(iceTitan)) {
            log.info("Ice titan is dead. Set health percentage to 0.");
            iceTitanHealthPercentage = 0;
        }

        if (fireTitanHealthPercentage != 100 && !isTitanAlive(fireTitan)) {
            log.info("Fire titan is dead. Set health percentage to 0.");
            fireTitanHealthPercentage = 0;
        }

        if (isTitanAlive(fireTitan)) {
            double currentFireTitanHealth = fireTitan.getHealthPercentage();
            log.info("Detected health of fire titan: {}", currentFireTitanHealth);

            if (currentFireTitanHealth < 100) { //If they are out of combat for too long, it shows as 100%
                fireTitanHealthPercentage = currentFireTitanHealth;
            }
        }

        if (isTitanAlive(iceTitan)) {
            double currentIceTitanHealth = iceTitan.getHealthPercentage();
            log.info("Detected health of ice titan: {}", currentIceTitanHealth);

            if (currentIceTitanHealth < 100) { //If they are out of combat for too long, it shows as 100%
                iceTitanHealthPercentage = currentIceTitanHealth;
            }
        }

        log.info("Last registered health of ice titan: {}", iceTitanHealthPercentage);
        log.info("Last registered health of fire titan: {}", fireTitanHealthPercentage);
    }

    private Rs2NpcModel selectTitanForSoloMode(Rs2NpcModel iceTitan, Rs2NpcModel fireTitan) {
        if (!isTitanAlive(iceTitan)) {
            log.info("Ice titan is dead, take fire titan");
            return fireTitan;
        }
        if (!isTitanAlive(fireTitan)) {
            log.info("Fire titan is dead, take ice titan");
            return iceTitan;
        }

        if (!isAtSecondPhase && fireTitanHealthPercentage > 33) {
            log.info("Still bringing down the fire giant to lower HP to trigger second phase.");
            return fireTitan;
        }

        isAtSecondPhase = true;

        if (iceTitanHealthPercentage > fireTitanHealthPercentage + 15) {
            log.info("Ice titan is too healthy, take ice titan");
            return iceTitan;
        } else {
            log.info("Fire titan is too healthy, take fire titan");
            return fireTitan;
        }
    }

    private void handleTravelling(RoyalTitansConfig config) {
        isAtSecondPhase = false;
        Rs2Prayer.disableAllPrayers();
        switch (travelStatus) {
            case TO_BANK:
                if (inventorySetup.doesInventoryMatch() && inventorySetup.doesEquipmentMatch()) {
                    state = RoyalTitansBotStatus.TRAVELLING;
                    travelStatus = RoyalTitansTravelStatus.TO_TITANS;
                    return;
                }
                subState = "Walking to bank";
                Rs2Bank.walkToBank();
                var isAtBank = Rs2Bank.isNearBank(5);
                if (isAtBank) {
                    state = RoyalTitansBotStatus.BANKING;
                }
                break;
            case TO_TITANS:
                subState = "Walking to titans";
                Rs2Walker.walkTo(BOSS_LOCATION, 3);
                var gotToTitans = Rs2Player.distanceTo(BOSS_LOCATION) < 5;
                if (gotToTitans) {
                    state = RoyalTitansBotStatus.WAITING;
                    travelStatus = RoyalTitansTravelStatus.TO_BANK;
                } else {
                    Rs2Walker.walkTo(BOSS_LOCATION, 3);
                }
                break;
            case TO_INSTANCE:
                subState = "Walking to instance";
                var isVisible = Rs2Widget.isWidgetVisible(WIDGET_START_A_FIGHT);
                if (isVisible) {
                    iceTitanHealthPercentage = 100;
                    fireTitanHealthPercentage = 100;
                    if (config.currentBotInstanceOwner()) {
                        sleep(600, 1200);
                        Rs2Widget.clickWidget("Start a fight (Your friends will be able to join you).");
                        sleep(600, 1200);
                        state = RoyalTitansBotStatus.FIGHTING;
                    } else {
                        var teammate = rs2PlayerCache.query().withName(config.teammateName()).nearestOnClientThread();
                            if (teammate != null) {
                            log.info("Waiting for teammate to enter the instance");
                            return;
                        }
                        Rs2Widget.clickWidget("Join a fight.");
                        sleep(1200, 1600);
                        Rs2Keyboard.typeString(config.teammateName());
                        sleep(600, 1200);
                        Rs2Keyboard.keyPress(KeyEvent.VK_ENTER);
                        sleep(600, 1200);
                        state = RoyalTitansBotStatus.FIGHTING;
                        sleep(1200, 1600);
                    }
                } else {
                    var tunnel = rs2TileObjectCache.query().withId(TUNNEL_ID).nearestOnClientThread(20);
                    if (tunnel != null) {
                        tunnel.click("Enter");
                        //Microbot.getClientThread().invoke(() -> tunnel.click("Enter"));
                    }
                }
                break;
        }
    }

    private void handleBanking(RoyalTitansConfig config) {
        isAtSecondPhase = false;
        subState = "Equipping gear";
        equipArmor(inventorySetup);
        if (!Rs2Bank.isOpen()) {
            log.info("Opening bank.");
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen);
        }
        Rs2Bank.depositAll();
        var items = inventorySetup.getEquipmentItems();
        var inventory = inventorySetup.getInventoryItems();
        for (var item : items) {
            if (item != null && item.getId() != -1) {
                if (!item.isFuzzy() || !Rs2Equipment.isWearing(item.getName(), false)) {
                    Rs2Bank.wearItem(item.getName(), true);
                }

            }
        }

        Map<InventorySetupsItem, Integer> result =
                inventory.stream()
                        .collect(Collectors.groupingBy(
                                InventorySetupsItem::getId,
                                LinkedHashMap::new,
                                Collectors.collectingAndThen(
                                        Collectors.toList(),
                                        objects -> Map.entry(objects.get(0), objects.size())
                                )
                        ))
                        .values()
                        .stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (a, b) -> a,
                                LinkedHashMap::new
                        ));

        result.forEach((item, quantity) -> {
            if (item != null && item.getId() != -1) {
                if (item.getStackCompare().getType() != 0) {
                    Rs2Bank.withdrawAll(item.getId());
                    sleep(600);
                } else {
                    if (quantity == 1) {
                        Rs2Bank.withdrawOne(item.getId());
                    } else {
                        Rs2Bank.withdrawX(item.getId(), quantity);
                    }
                    sleep(600);
                }
            }
        });

        Rs2Bank.closeBank();
        travelStatus = RoyalTitansTravelStatus.TO_TITANS;
        state = RoyalTitansBotStatus.TRAVELLING;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        isRunning = false;
        state = RoyalTitansBotStatus.BANKING;
        travelStatus = RoyalTitansTravelStatus.TO_BANK;
        enrageTile.set(null);
        kills = 0;
        disableAllPrayers();
        if (mainScheduledFuture != null && !mainScheduledFuture.isCancelled()) {
            mainScheduledFuture.cancel(true);
        }
        log.info("Shutting down Royal Titans script");
    }
}

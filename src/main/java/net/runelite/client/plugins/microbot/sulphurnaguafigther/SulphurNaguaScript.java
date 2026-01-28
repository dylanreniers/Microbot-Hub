package net.runelite.client.plugins.microbot.sulphurnaguafigther;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetupsItem;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.InteractOrder;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
public class SulphurNaguaScript extends Script {

    @Inject
    private Rs2NpcCache rs2NpcCache;

    @Inject
    private Rs2TileItemCache rs2TileItemCache;

    @Inject
    private Rs2TileObjectCache rs2TileObjectCache;

    @Getter
    @RequiredArgsConstructor
    public enum NaguaLocation {
        CIVITAS_ILLA_FORTIS_WEST("West",
                new WorldArea(1344, 9553, 25, 25, 0),
                new WorldPoint(1376, 9712, 0)),

        CIVITAS_ILLA_FORTIS_EAST("East",
                new WorldArea(1371, 9557, 16, 16, 0),
                new WorldPoint(1376, 9712, 0));

        private final String name;
        private final WorldArea combatArea;
        private final WorldPoint prepArea;

        @Override
        public String toString() {
            return name;
        }

        public WorldPoint getFightAreaCenter() {
            return new WorldPoint(
                    this.combatArea.getX() + this.combatArea.getWidth() / 2,
                    this.combatArea.getY() + this.combatArea.getHeight() / 2,
                    this.combatArea.getPlane()
            );
        }
    }

    public enum SulphurNaguaState {
        IDLE,
        BANKING,
        WALKING_TO_BANK,
        WALKING_TO_PREP,
        PREPARATION,
        PICKUP,
        WALKING_TO_FIGHT,
        FIGHTING,
        LOOTING,
        GETTING_RUNE_CRAFTING_XP
    }

    public SulphurNaguaState currentState = SulphurNaguaState.IDLE;

    public int totalNaguaKills = 0;
    @Getter
    private long startTotalExp = 0;
    private boolean hasInitialized = false;

    @Inject
    private Client client;

    private static final int SUPPLY_CRATE_ID = 51371;
    private static final int PESTLE_AND_MORTAR_ID = 233;
    private static final int VIAL_OF_WATER_ID = 227;
    private static final int SULPHUR_BLADE_ID = 29084;

    private static final int SULPHUROUS_ESSENCE_ID = 29087;
    private static final int EYTALLALI_ID = 12870;
    private static final WorldPoint EYTALLALI_LOCATION = new WorldPoint(1521, 9577, 0);

    private Set<Integer> dynamicLootIds = new HashSet<>();

    private static final int MOONLIGHT_GRUB_ID = 29078;
    private static final int MOONLIGHT_GRUB_PASTE_ID = 29079;
    private static final Set<Integer> MOONLIGHT_POTION_IDS = Set.of(29080, 29081, 29082, 29083);
    private static final int GRUB_SAPLING_ID = 51365;

    private NaguaLocation selectedLocation;

    public WorldArea getNaguaCombatArea() {
        return (selectedLocation != null) ? selectedLocation.getCombatArea() : null;
    }

    private void updateDynamicLootIds(SulphurNaguaConfig config) {
        dynamicLootIds.clear();

        if (config.lootFireRunes()) dynamicLootIds.add(554);
        if (config.lootChaosRunes()) dynamicLootIds.add(562);
        if (config.lootNatureRunes()) dynamicLootIds.add(561);
        if (config.lootDeathRunes()) dynamicLootIds.add(560);
        if (config.lootIronOre()) dynamicLootIds.add(441);
        if (config.lootCoal()) dynamicLootIds.add(454);
        if (config.lootCopperOre()) dynamicLootIds.add(437);
        if (config.lootTinOre()) dynamicLootIds.add(439);
        if (config.lootMithrilOre()) dynamicLootIds.add(448);
        if (config.lootSilverOre()) dynamicLootIds.add(443);
        if (config.lootSulphurousEssence()) dynamicLootIds.add(29087);
    }

    public boolean run(SulphurNaguaConfig config) {
        Microbot.enableAutoRunOn = true;
        currentState = SulphurNaguaState.IDLE;
        selectedLocation = config.naguaLocation();

        applyAntiBanSettings();
        updateDynamicLootIds(config);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                if (!hasInitialized) {
                    startTotalExp = Microbot.getClient().getOverallExperience();
                    if (startTotalExp > 0) hasInitialized = true;
                    return;
                }
                determineState(config);

                switch (currentState) {
                    case BANKING:
                        handleBanking(config);
                        break;
                    case WALKING_TO_BANK:
                        Rs2Bank.walkToBank();
                        break;
                    case WALKING_TO_PREP:
                        Rs2Walker.walkTo(selectedLocation.getPrepArea());
                        break;
                    case PREPARATION:
                        handlePreparation(config);
                        break;
                    case WALKING_TO_FIGHT:
                        Rs2Walker.walkTo(selectedLocation.getFightAreaCenter());
                        break;
                    case FIGHTING:
                        handleFighting(config);
                        break;
                    case LOOTING:
                        handleLooting();
                        break;
                    case GETTING_RUNE_CRAFTING_XP:
                        handleGettingRunecraftingXp(config);
                        break;
                    case IDLE:
                        break;
                }
            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        currentState = SulphurNaguaState.IDLE;
        Rs2Antiban.resetAntibanSettings();
    }

    private void determineState(SulphurNaguaConfig config) {

        boolean hasPotionsInInventory = countMoonlightPotions() > 0;
        int totalOwnedPotions = countMoonlightPotions();

        if (!Rs2Inventory.hasItem(PESTLE_AND_MORTAR_ID)) {
            currentState = Rs2Bank.isNearBank(10) ? SulphurNaguaState.BANKING : SulphurNaguaState.WALKING_TO_BANK;
            return;
        }

        boolean inCombatZone = isAtLocation(selectedLocation.getFightAreaCenter());

        var interacting = Rs2Player.getInteracting();
        boolean isAvailableForAction = !Rs2Player.isInCombat() || interacting == null || interacting.isDead();

        if (!dynamicLootIds.isEmpty() && isAvailableForAction && (isStackableLootNearby() || (isSulphurBladeNearby() && !Rs2Inventory.isFull()))) {
            currentState = SulphurNaguaState.LOOTING;
            return;
        }

        if (((currentState == SulphurNaguaState.IDLE && inCombatZone) || currentState == SulphurNaguaState.LOOTING) && hasPotionsInInventory) {
            currentState = SulphurNaguaState.FIGHTING;
            return;
        }

        if (Rs2Inventory.emptySlotCount() > 3 && isAtLocation(selectedLocation.getPrepArea())) {
            currentState = SulphurNaguaState.PREPARATION;
            return;
        }

        if (!hasPotionsInInventory) {
            if (currentState == SulphurNaguaState.FIGHTING) {
                Rs2Prayer.disableAllPrayers();
                Microbot.log("All potions used. Starting preparation for a new batch.");
            }

            if (config.changeInSulphurousEssence() && Rs2Inventory.hasItem(SULPHUROUS_ESSENCE_ID) && totalOwnedPotions == 0) {
                Microbot.log("No potions left. Exchanging Sulphurous Essence for XP.");
                currentState = SulphurNaguaState.GETTING_RUNE_CRAFTING_XP;
                return;
            }
            currentState = isAtLocation(selectedLocation.getPrepArea()) ? SulphurNaguaState.PREPARATION : SulphurNaguaState.WALKING_TO_PREP;
            return;
        }

        currentState = inCombatZone ? SulphurNaguaState.FIGHTING : SulphurNaguaState.WALKING_TO_FIGHT;
    }

    private void handlePreparation(SulphurNaguaConfig config) {
        int freeSlots = Rs2Inventory.emptySlotCount();
        int potionsToMake = freeSlots / 2;
        log.info("Number of potions to make: {}", potionsToMake);
        takeVials(potionsToMake);
        sleepUntil(() -> !Rs2Player.isAnimating());
        takeGrubs(potionsToMake);
        sleepUntil(() -> !Rs2Player.isAnimating());
        processAllIngredients();
        currentState = SulphurNaguaState.WALKING_TO_FIGHT;
    }

    private void processAllIngredients() {
        if (Rs2Inventory.hasItem(MOONLIGHT_GRUB_ID)) {
            Microbot.log("Grinding all available grubs...");
            Rs2Inventory.use(PESTLE_AND_MORTAR_ID);
            sleep(100, 150);
            Rs2Inventory.use(MOONLIGHT_GRUB_ID);
            sleepUntil(() -> !Rs2Inventory.hasItem(MOONLIGHT_GRUB_ID) || Rs2Dialogue.isInDialogue(), 18000);
            sleep(600, 1000);
        }
        if (Rs2Inventory.hasItem(MOONLIGHT_GRUB_PASTE_ID) && Rs2Inventory.hasItem(VIAL_OF_WATER_ID)) {
            Microbot.log("Mixing all available paste...");
            Rs2Inventory.use(MOONLIGHT_GRUB_PASTE_ID);
            sleep(100, 150);
            Rs2Inventory.use(VIAL_OF_WATER_ID);
            sleepUntil(() -> !Rs2Inventory.hasItem(MOONLIGHT_GRUB_PASTE_ID) || Rs2Dialogue.isInDialogue(), 18000);
            sleep(600, 1000);
        }
    }

    private boolean interactWithObject(int objectId, String action) {
        var object = rs2TileObjectCache.query()
                .withId(objectId)
                .nearestOnClientThread(12);

        if (Objects.nonNull(object)) {
            return Microbot.getClientThread().invoke((Supplier<Boolean>) () -> object.click(action));
        }

        return false;
    }

    private void takeVials(int amount) {
        while (Rs2Inventory.count(VIAL_OF_WATER_ID) < amount) {
            log.info("Currently in inventory: {}. Needed: {}", Rs2Inventory.count(VIAL_OF_WATER_ID), amount);
            if (Rs2Dialogue.hasDialogueOption("Take herblore supplies.")) {
                Rs2Dialogue.clickOption("Take herblore supplies.");
            } else if (!Rs2Player.isAnimating()) {
                interactWithObject(SUPPLY_CRATE_ID, "Take-from herblore supplies");
            }
            sleep(300, 500);
        }

        if (Rs2Inventory.count(VIAL_OF_WATER_ID) > amount) {
            log.info("Got too many. Got {} and need {}, dropping {}", Rs2Inventory.count(VIAL_OF_WATER_ID), amount, Rs2Inventory.count(VIAL_OF_WATER_ID) - amount);
            Rs2Inventory.dropAmount(VIAL_OF_WATER_ID, Rs2Inventory.count(VIAL_OF_WATER_ID) - amount, InteractOrder.STANDARD);
        }
    }

    private void takeGrubs(int requiredAmount) {
        if (interactWithObject(GRUB_SAPLING_ID, "Collect-from")) {
            sleepUntil(() -> Rs2Inventory.count(MOONLIGHT_GRUB_ID) > requiredAmount || Rs2Inventory.isFull(), 15000);
            if (Rs2Inventory.count(MOONLIGHT_GRUB_ID) > requiredAmount) {
                log.info("Got too many. Got {} and need {}, dropping {}", Rs2Inventory.count(MOONLIGHT_GRUB_ID), requiredAmount, Rs2Inventory.count(MOONLIGHT_GRUB_ID) - requiredAmount);
                Rs2Inventory.dropAmount(MOONLIGHT_GRUB_ID, Rs2Inventory.count(MOONLIGHT_GRUB_ID) - requiredAmount, InteractOrder.STANDARD);
            }
        }
    }

    private int countMoonlightPotions() {
        return MOONLIGHT_POTION_IDS.stream().mapToInt(Rs2Inventory::count).sum();
    }

    private void handleGettingRunecraftingXp(SulphurNaguaConfig config) {
        if (Rs2Dialogue.isInDialogue()) {
            Rs2Dialogue.clickContinue();
            sleepUntil(() -> !Rs2Dialogue.isInDialogue() || !Rs2Inventory.hasItem(SULPHUROUS_ESSENCE_ID), 3000);
            return;
        }

        if (!Rs2Inventory.hasItem(SULPHUROUS_ESSENCE_ID)) {
            currentState = isAtLocation(selectedLocation.getPrepArea()) ? SulphurNaguaState.PREPARATION : SulphurNaguaState.WALKING_TO_PREP;
            return;
        }

        if (Rs2Player.getWorldLocation().distanceTo(EYTALLALI_LOCATION) > 5) {
            Rs2Walker.walkTo(EYTALLALI_LOCATION);
            sleep(400, 800);
            return;
        }

        var eytallali = rs2NpcCache.query().withId(EYTALLALI_ID).firstOnClientThread();
        if (eytallali == null) {
            sleep(600, 1000);
            return;
        }

        if (Rs2Player.isAnimating() || Microbot.isGainingExp) {
            Microbot.log("Waiting for action to complete...");
            return;
        }

        Rs2Inventory.use(SULPHUROUS_ESSENCE_ID);
        Microbot.getClientThread().invoke(() -> eytallali.click());
        sleepUntil(Rs2Dialogue::isInDialogue, 5000);
    }

    private void handleFighting(SulphurNaguaConfig config) {
        int basePrayerLevel = client.getRealSkillLevel(Skill.PRAYER);
        int currentHerbloreLevel = client.getBoostedSkillLevel(Skill.HERBLORE);

        int prayerBasedRestore = (int) Math.floor(basePrayerLevel * 0.25) + 7;
        int herbloreBasedRestore = (int) Math.floor(currentHerbloreLevel * 0.3) + 7;
        int dynamicThreshold = Math.max(prayerBasedRestore, herbloreBasedRestore);

        if (Rs2Player.drinkPrayerPotionAt(dynamicThreshold)) {
            sleep(300, 600);
        }

        if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MELEE)) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
        }

        if (config.useOffensivePrayers()) {
            var bestMeleePrayer = Rs2Prayer.getBestMeleePrayer();
            if (bestMeleePrayer != null && !Rs2Prayer.isPrayerActive(bestMeleePrayer)) {
                Rs2Prayer.toggle(bestMeleePrayer, true);
            }
        }

        var npcAttackingPlayer = rs2NpcCache.query()
                .where(Rs2NpcModel::isInteractingWithPlayer)
                .nearestOnClientThread(12);

        boolean needsNewTarget = !Rs2Player.isInCombat() && npcAttackingPlayer == null;

        if (Rs2Player.getInteracting() == null && npcAttackingPlayer != null) {
            log.info("Attacking nagua that is attacking us");
            Microbot.getClientThread().invoke(() -> npcAttackingPlayer.click("Attack"));
        } else if (needsNewTarget) {
            if (getNaguaCombatArea() != null && getNaguaCombatArea().contains(Rs2Player.getWorldLocation())) {
                var nagua = rs2NpcCache.query()
                        .withName("Sulphur Nagua")
                        .where((npc) -> !npc.isDead())
                        .nearestOnClientThread(12);
                if (nagua != null) {
                    log.info("Attacking new nagua");
                    Microbot.getClientThread().invoke(() -> nagua.click("Attack"));
                    sleepUntil(Rs2Player::isInCombat);
                    totalNaguaKills++;
                }
            } else {
                Microbot.log("Outside combat zone, walking back to center...");
                Rs2Walker.walkTo(selectedLocation.getFightAreaCenter());
                sleep(400, 800);
            }
        }
    }

    private void applyAntiBanSettings() {
        Rs2Antiban.antibanSetupTemplates.applyCombatSetup();
        Rs2Antiban.setActivity(Activity.GENERAL_COMBAT);
    }

    private void handleBanking(SulphurNaguaConfig config) {
        try {
            Rs2Bank.walkToBank(BankLocation.CAM_TORUM);
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 5000);

            InventorySetup setupData = config.useInventorySetup() ? config.inventorySetup() : null;

            if (setupData != null) {
                Rs2Bank.depositAll();
                sleepUntil(Rs2Inventory::isEmpty, 2000);
                Rs2Bank.depositEquipment();
                Rs2Random.wait(300, 600);

                if (setupData.getEquipment() != null) {
                    for (InventorySetupsItem item : setupData.getEquipment()) {
                        Rs2Bank.withdrawItem(item.getId());
                    }
                }

                if (setupData.getInventory() != null) {
                    for (InventorySetupsItem item : setupData.getInventory()) {
                        final int currentAmountInInv = Rs2Inventory.count(item.getId());
                        final int requiredAmount = item.getQuantity();
                        if (currentAmountInInv < requiredAmount) {
                            Rs2Bank.withdrawX(item.getId(), requiredAmount - currentAmountInInv);
                        }
                    }
                }

                if (!Rs2Inventory.hasItem(PESTLE_AND_MORTAR_ID)) {
                    Rs2Bank.withdrawItem(PESTLE_AND_MORTAR_ID);
                    if (!sleepUntil(() -> Rs2Inventory.hasItem(PESTLE_AND_MORTAR_ID), 2000)) {
                        shutdown();
                        return;
                    }
                }
            } else {
                Rs2Bank.depositAll();
                sleep(300, 600);
                Rs2Bank.withdrawItem(PESTLE_AND_MORTAR_ID);
                if (!sleepUntil(() -> Rs2Inventory.hasItem(PESTLE_AND_MORTAR_ID), 2000)) {
                    shutdown();
                    return;
                }
            }

            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), 2000);
            }

            if (setupData != null) {
                new Rs2InventorySetup(setupData, mainScheduledFuture).wearEquipment();
            }
        } finally {
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
            }
        }
    }

    private boolean isAtLocation(WorldPoint worldPoint) {
        return Rs2Player.getWorldLocation().distanceTo(worldPoint) < 10;
    }


    private boolean isSulphurBladeNearby() {
        return itemExists(SULPHUR_BLADE_ID);
    }

    private boolean itemExists(int itemId) {
        return rs2TileItemCache.query()
                .withId(itemId)
                .nearestOnClientThread(8) != null;
    }

    private void takeItem(int itemId) {
        var item = rs2TileItemCache.query()
                .withId(itemId)
                .nearestOnClientThread(8);

        if (Objects.nonNull(item)) {
            int itemsBefore = Rs2Inventory.itemQuantity(itemId);
            var time = System.currentTimeMillis();
            log.info("Item count before: {}", itemsBefore);
            Microbot.getClientThread().invoke(() -> item.click("Take"));
            sleepUntil(() -> Rs2Inventory.itemQuantity(itemId) > itemsBefore);
            log.info("Time taken: {}", System.currentTimeMillis() - time);
        }
    }

    private boolean isStackableLootNearby() {
        for (int itemId : dynamicLootIds) {
            if (itemExists(itemId)) {
                return Rs2Inventory.contains(itemId) || !Rs2Inventory.isFull();
            }
        }
        return false;
    }


    private void handleLooting() {
        Microbot.log("Looting items...");

        if (isSulphurBladeNearby()) {
            takeItem(SULPHUR_BLADE_ID);
            return;
        }


        for (int itemId : dynamicLootIds) {
            if (itemExists(itemId)) {
                takeItem(itemId);
                return;
            }
        }
    }
}

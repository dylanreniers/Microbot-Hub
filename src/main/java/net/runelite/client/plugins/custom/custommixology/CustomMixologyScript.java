package net.runelite.client.plugins.custom.custommixology;

import net.runelite.api.DynamicObject;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.custom.custommixology.enums.AlchemyObject;
import net.runelite.client.plugins.custom.custommixology.enums.MixologyState;
import net.runelite.client.plugins.custom.custommixology.enums.OrderStrategy;
import net.runelite.client.plugins.custom.custommixology.enums.PotionComponent;
import net.runelite.client.plugins.custom.custommixology.enums.PotionModifier;
import net.runelite.client.plugins.custom.custommixology.enums.PotionType;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.custom.custommixology.enums.AlchemyObject.MIXING_VESSEL;

public class CustomMixologyScript extends Script {
    private static final Integer DIGWEED = ItemID.MM_LAB_SPECIAL_HERB;
    private static final WorldPoint BANK_LOCATION = new WorldPoint(1398, 9313, 0);
    private static final int HOPPER_CAPACITY = 3000;
    private static final int RETORT_PROGRESS_VARBIT = 11327;
    private static final int MAL_HERBLORE_LEVEL = 81;

    // Written by the plugin on the client thread, read by the script thread.
    public volatile List<CustomPotionOrder> potionOrders = Collections.emptyList();
    public volatile int ordersVersion = 0;
    public volatile boolean digweedUsedThisCycle = false;
    public final Set<PotionType> seenPotionTypes = ConcurrentHashMap.newKeySet();
    public volatile int agitatorQuickActionTicks = 0;
    public volatile int agitatorClicks = 0;
    public volatile int alembicQuickActionTicks = 0;
    public volatile boolean retortActive = false;
    public volatile AlchemyObject digweed;

    public static MixologyState mixologyState = MixologyState.IDLE;
    public static int lyePasteAmount, agaPasteAmount, moxPasteAmount;
    public static int startLyePoints, startAgaPoints, startMoxPoints;
    public static int currentLyePoints, currentAgaPoints, currentMoxPoints;
    public int leverRetries = 0;
    public List<PotionModifier> customOrder = Arrays.asList(
            PotionModifier.CRYSTALISED,
            PotionModifier.CONCENTRATED,
            PotionModifier.HOMOGENOUS
    );

    private int hopperMox, hopperAga, hopperLye;
    private int lastAgitatorClickTick = -1;
    private int lastRetortClickTick = -1;
    private int lastRefinerClickTick = -1;
    // Station used last, so stock potions are processed without walking to another station.
    private PotionModifier lastStation = PotionModifier.CONCENTRATED;

    /**
     * Get the total runtime of the script
     *
     * @return the total runtime of the script
     */
    public Instant startTime;
    public Duration getRunTime() {
        if (startTime == null) return Duration.ofSeconds(0);
        return Duration.between(startTime, Instant.now());
    }

    public boolean run(CustomMixologyConfig config) {
        Microbot.enableAutoRunOn = false;
        mixologyState = MixologyState.IDLE;
        startMoxPoints = startAgaPoints = startLyePoints = 0;
        currentMoxPoints = currentAgaPoints = currentLyePoints = 0;
        leverRetries = 0;
        if (!Rs2AntibanSettings.naturalMouse) {
            Microbot.log("Hey! Did you know this script works really well with natural mouse? Feel free to enable it in the antiban settings.");
        }
        startTime = Instant.now();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                if (leverRetries >= 20) {
                    Microbot.log("Failed to create a potion. Please do this step manually and restart the script.");
                    return;
                }

                Widget hud = Rs2Widget.getWidget(882, 2);
                boolean isInMinigame = hud != null;

                if (!isInMinigame && mixologyState != MixologyState.REFINER) {
                    Rs2Walker.walkTo(1395, 9322, 0, 2);
                    return;
                }

                if (isInMinigame) {
                    readHopper(hud);

                    if (startLyePoints == 0 && startAgaPoints == 0 && startMoxPoints == 0) {
                        startMoxPoints = getMoxPoints();
                        startAgaPoints = getAgaPoints();
                        startLyePoints = getLyePoints();
                    }

                    if (handleDigweed(config)) return;

                    updateSupplyState(config);
                }

                if (mixologyState == MixologyState.IDLE) {
                    mixologyState = MixologyState.MIX_POTION_STAGE_1;
                }

                if (isMixingState() && readyForConveyor(config)) {
                    mixologyState = MixologyState.CONVEYER_BELT;
                }

                switch (mixologyState) {
                    case BANK:
                        if (hasPaste()) {
                            if (Rs2Bank.openBank()) {
                                depositPaste();
                            }
                            return;
                        }
                        Rs2Bank.closeBank();
                        mixologyState = MixologyState.MIX_POTION_STAGE_1;
                        break;
                    case REFINER:
                        handleRefiner(config);
                        break;
                    case DEPOSIT_HOPPER:
                        if (!hasPaste()) {
                            mixologyState = MixologyState.MIX_POTION_STAGE_1;
                            break;
                        }
                        if (Microbot.getRs2TileObjectCache().query().withId(ObjectID.MM_LAB_HOPPER).interact()) {
                            Rs2Player.waitForWalking();
                            Rs2Inventory.waitForInventoryChanges(10000);
                            // Give the hopper widget a tick to update so the refill check doesn't read stale amounts.
                            sleep(600);
                            mixologyState = MixologyState.MIX_POTION_STAGE_1;
                        }
                        break;
                    case MIX_POTION_STAGE_1:
                        mixPotions(config);
                        break;
                    case TAKE_FROM_MIXIN_VESSEL:
                        Microbot.getRs2TileObjectCache().query()
                                .withId(MIXING_VESSEL.objectId())
                                .interact();
                        Rs2Inventory.waitForInventoryChanges(2000);
                        mixologyState = MixologyState.MIX_POTION_STAGE_1;
                        break;
                    case MIX_POTION_STAGE_2:
                        processPotions(config);
                        break;
                    case CONVEYER_BELT:
                        if (!readyForConveyor(config)) {
                            mixologyState = MixologyState.MIX_POTION_STAGE_1;
                            return;
                        }
                        int version = ordersVersion;
                        if (Microbot.getRs2TileObjectCache().query().withId(AlchemyObject.CONVEYOR_BELT.objectId()).interact()) {
                            Rs2Inventory.waitForInventoryChanges(5000);
                            // Wait for the refreshed orders, otherwise stale orders can trigger a second hand-in.
                            sleepUntil(() -> ordersVersion != version, 3000);
                            currentAgaPoints = getAgaPoints();
                            currentLyePoints = getLyePoints();
                            currentMoxPoints = getMoxPoints();
                        }
                        break;
                }
            } catch (Exception ex) {
                Microbot.log("Mixology error: " + ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    // ---------------------------------------------------------------- supplies

    private void readHopper(Widget hud) {
        Widget[] children = hud.getDynamicChildren();
        hopperMox = Integer.parseInt(children[8].getText());
        hopperAga = Integer.parseInt(children[11].getText());
        hopperLye = Integer.parseInt(children[14].getText());
        moxPasteAmount = hopperMox + Rs2Inventory.itemQuantity(ItemID.MM_MOX_PASTE);
        agaPasteAmount = hopperAga + Rs2Inventory.itemQuantity(ItemID.MM_AGA_PASTE);
        lyePasteAmount = hopperLye + Rs2Inventory.itemQuantity(ItemID.MM_LYE_PASTE);
    }

    /**
     * Carried paste goes into the hopper (or the bank when the hopper is full); a low hopper starts a refill.
     * The refiner and bank states finish on their own, so they are never interrupted here.
     */
    private void updateSupplyState(CustomMixologyConfig config) {
        if (mixologyState == MixologyState.REFINER || mixologyState == MixologyState.BANK) return;

        if (hasPaste()) {
            boolean hopperFull = (hopperMox >= HOPPER_CAPACITY && Rs2Inventory.hasItem(ItemID.MM_MOX_PASTE))
                    || (hopperAga >= HOPPER_CAPACITY && Rs2Inventory.hasItem(ItemID.MM_AGA_PASTE))
                    || (hopperLye >= HOPPER_CAPACITY && Rs2Inventory.hasItem(ItemID.MM_LYE_PASTE));
            mixologyState = hopperFull ? MixologyState.BANK : MixologyState.DEPOSIT_HOPPER;
            return;
        }

        int threshold = config.refillThreshold();
        if (hopperMox < threshold || hopperAga < threshold || hopperLye < threshold) {
            // Digweed can't leave the minigame area, so spend it before walking to the refiner.
            if (Rs2Inventory.hasItem(DIGWEED)) {
                Integer target = bestPotionInInventory();
                if (target != null) useDigweedOn(target);
            }
            mixologyState = MixologyState.REFINER;
        }
    }

    private void handleRefiner(CustomMixologyConfig config) {
        if (Rs2Player.getWorldLocation().distanceTo(BANK_LOCATION) > 10) {
            Rs2Walker.walkTo(BANK_LOCATION);
            return;
        }

        if (hasRefinerHerbs(config)) {
            refineHerbs(config);
            return;
        }

        if (!Rs2Bank.openBank()) return;
        sleepUntil(Rs2Bank::isOpen);
        if (hasPaste()) {
            depositPaste();
            sleepUntil(() -> !hasPaste(), 3000);
        }

        // Targets are paste amounts: refine until hopper + bank reach them. Never below the refill threshold,
        // or a refill could end with the hopper still under it and loop.
        int minimum = config.refillThreshold() + 100;
        int[][] pastes = {
                {ItemID.MM_MOX_PASTE, hopperMox, Math.max(config.amtMoxHerb(), minimum)},
                {ItemID.MM_LYE_PASTE, hopperLye, Math.max(config.amtLyeHerb(), minimum)},
                {ItemID.MM_AGA_PASTE, hopperAga, Math.max(config.amtAgaHerb(), minimum)},
        };
        String[] herbs = {config.moxHerb().toString(), config.lyeHerb().toString(), config.agaHerb().toString()};

        for (int i = 0; i < pastes.length; i++) {
            int total = pastes[i][1] + Rs2Bank.count(pastes[i][0]);
            if (total >= pastes[i][2]) continue;
            if (!Rs2Bank.hasItem(herbs[i], true)) {
                if (total >= config.refillThreshold()) continue;
                Microbot.showMessage("Failed to find " + herbs[i] + " in your bank. Shutting down script...");
                shutdown();
                return;
            }
            Rs2Bank.withdrawAll(herbs[i], true);
            sleepUntil(() -> hasRefinerHerbs(config), 3000);
            Rs2Bank.closeBank();
            return;
        }

        // Every paste is at its target: carry the bank stock to the hopper.
        for (int[] paste : pastes) {
            if (Rs2Bank.count(paste[0]) > 0) {
                Rs2Bank.withdrawAll(paste[0]);
            }
        }
        sleepUntil(this::hasPaste, 3000);
        Rs2Bank.closeBank();
        mixologyState = MixologyState.DEPOSIT_HOPPER;
    }

    private void refineHerbs(CustomMixologyConfig config) {
        if (config.useQuickActionRefiner()) {
            // One click per tick doubles the refiner speed; extra clicks inside a tick are wasted.
            int tick = currentTick();
            if (tick == lastRefinerClickTick) return;
            if (Microbot.getRs2TileObjectCache().query().withId(ObjectID.MM_LAB_MILL).interact()) {
                lastRefinerClickTick = tick;
                if (!Rs2Player.isAnimating()) Rs2Player.waitForAnimation();
            }
            return;
        }
        if (Rs2Player.isAnimating()) return;
        Microbot.getRs2TileObjectCache().query().withId(ObjectID.MM_LAB_MILL).interact();
        Rs2Player.waitForAnimation();
        sleepGaussian(450, 150);
        sleepUntil(() -> !Microbot.isGainingExp, 30000);
    }

    private boolean hasRefinerHerbs(CustomMixologyConfig config) {
        return Rs2Inventory.hasItem(config.agaHerb().toString())
                || Rs2Inventory.hasItem(config.lyeHerb().toString())
                || Rs2Inventory.hasItem(config.moxHerb().toString());
    }

    private boolean hasPaste() {
        return Rs2Inventory.hasItem(ItemID.MM_MOX_PASTE)
                || Rs2Inventory.hasItem(ItemID.MM_LYE_PASTE)
                || Rs2Inventory.hasItem(ItemID.MM_AGA_PASTE);
    }

    // Only paste goes to the bank: potions in progress stay in the inventory.
    private void depositPaste() {
        for (int paste : new int[]{ItemID.MM_MOX_PASTE, ItemID.MM_LYE_PASTE, ItemID.MM_AGA_PASTE}) {
            if (Rs2Inventory.hasItem(paste)) {
                Rs2Bank.depositAll(paste);
            }
        }
    }

    // ---------------------------------------------------------------- order bookkeeping

    private List<CustomPotionOrder> selectedOrders() {
        return potionOrders.stream().filter(CustomPotionOrder::selected).collect(Collectors.toList());
    }

    private Map<PotionType, Integer> selectedCountByType() {
        Map<PotionType, Integer> counts = new EnumMap<>(PotionType.class);
        for (CustomPotionOrder order : selectedOrders()) {
            counts.merge(order.potionType(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Selected orders that still need processing, in route order. Finished potions in the inventory cover
     * orders of their type: a hand-in only checks the potion type, so stock and restarts count too.
     */
    private List<CustomPotionOrder> pendingOrders() {
        List<CustomPotionOrder> selected = selectedOrders();
        Map<PotionType, Integer> credit = new EnumMap<>(PotionType.class);
        for (CustomPotionOrder order : selected) {
            credit.putIfAbsent(order.potionType(), Rs2Inventory.count(order.potionType().getFulfilledItemId()));
            if (order.fulfilled()) credit.merge(order.potionType(), -1, Integer::sum);
        }
        List<CustomPotionOrder> pending = new ArrayList<>();
        for (CustomPotionOrder order : selected) {
            if (order.fulfilled()) continue;
            int available = credit.getOrDefault(order.potionType(), 0);
            if (available > 0) {
                credit.put(order.potionType(), available - 1);
                continue;
            }
            pending.add(order);
        }
        return pending;
    }

    private boolean readyForConveyor(CustomMixologyConfig config) {
        Map<PotionType, Integer> needed = selectedCountByType();
        if (needed.isEmpty()) return false;
        for (Map.Entry<PotionType, Integer> entry : needed.entrySet()) {
            if (Rs2Inventory.count(entry.getKey().getFulfilledItemId()) < entry.getValue()) return false;
        }
        // In stock mode, finish the batch first so the next orders can be handed in straight away.
        return !config.stockPotions() || unprocessedStockType(config) == null;
    }

    private boolean isMixingState() {
        return mixologyState == MixologyState.MIX_POTION_STAGE_1
                || mixologyState == MixologyState.MIX_POTION_STAGE_2
                || mixologyState == MixologyState.TAKE_FROM_MIXIN_VESSEL;
    }

    /**
     * Types worth keeping in stock: non-single potions that have been ordered this session (so they are
     * within the player's Herblore level), limited to the preferred resin for the single resin strategy.
     */
    private Set<PotionType> stockTypes(CustomMixologyConfig config) {
        if (!config.stockPotions()) return Collections.emptySet();
        return seenPotionTypes.stream()
                .filter(t -> !CustomMixologyOrderSelector.isSingle(t))
                .filter(t -> config.orderStrategy() != OrderStrategy.SINGLE_RESIN
                        || CustomMixologyOrderSelector.count(t, config.preferredResin()) > 0)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PotionType.class)));
    }

    private int ownedCount(PotionType type) {
        return Rs2Inventory.count(type.itemId()) + Rs2Inventory.count(type.getFulfilledItemId());
    }

    private int desiredCount(PotionType type, Map<PotionType, Integer> selectedCounts, Set<PotionType> stockTypes, CustomMixologyConfig config) {
        return selectedCounts.getOrDefault(type, 0) + (stockTypes.contains(type) ? config.stockPerType() : 0);
    }

    // A stock type with unfinished potions beyond what the pending orders use.
    private PotionType unprocessedStockType(CustomMixologyConfig config) {
        Map<PotionType, Integer> pendingNeed = new EnumMap<>(PotionType.class);
        for (CustomPotionOrder order : pendingOrders()) {
            pendingNeed.merge(order.potionType(), 1, Integer::sum);
        }
        for (PotionType type : stockTypes(config)) {
            if (Rs2Inventory.count(type.itemId()) > pendingNeed.getOrDefault(type, 0)) return type;
        }
        return null;
    }

    // ---------------------------------------------------------------- mixing

    private void mixPotions(CustomMixologyConfig config) {
        List<CustomPotionOrder> pending = pendingOrders();
        Map<PotionType, Integer> pendingNeed = new EnumMap<>(PotionType.class);
        for (CustomPotionOrder order : pending) {
            pendingNeed.merge(order.potionType(), 1, Integer::sum);
        }

        // Orders first, in route order, so the vessel output follows the processing path.
        PotionType potionToMake = null;
        for (CustomPotionOrder order : pending) {
            if (Rs2Inventory.count(order.potionType().itemId()) < pendingNeed.get(order.potionType())) {
                potionToMake = order.potionType();
                break;
            }
        }

        // Then top up the stock, keeping a free slot for digweed.
        if (potionToMake == null && config.stockPotions() && Rs2Inventory.emptySlotCount() > 1) {
            Map<PotionType, Integer> selectedCounts = selectedCountByType();
            Set<PotionType> stockTypes = stockTypes(config);
            potionToMake = stockTypes.stream()
                    .filter(t -> ownedCount(t) < desiredCount(t, selectedCounts, stockTypes, config))
                    .max(Comparator.comparingInt(PotionType::experience))
                    .orElse(null);
        }

        if (potionToMake == null) {
            mixologyState = MixologyState.MIX_POTION_STAGE_2;
            return;
        }

        if (Rs2Inventory.emptySlotCount() == 0) {
            if (!dropSurplusPotion(config)) {
                Microbot.log("Inventory is full and nothing can be dropped, processing what is there.");
                mixologyState = MixologyState.MIX_POTION_STAGE_2;
            }
            return;
        }

        if (canCreatePotion(potionToMake)) {
            mixologyState = MixologyState.TAKE_FROM_MIXIN_VESSEL;
            leverRetries = 0;
        } else {
            createPotion(potionToMake, config);
        }
    }

    // Drops one potion that no selected order or stock slot wants, to free space for a new base.
    private boolean dropSurplusPotion(CustomMixologyConfig config) {
        Map<PotionType, Integer> selectedCounts = selectedCountByType();
        Set<PotionType> stockTypes = stockTypes(config);
        for (PotionType type : PotionType.values()) {
            if (ownedCount(type) <= desiredCount(type, selectedCounts, stockTypes, config)) continue;
            int itemId = Rs2Inventory.hasItem(type.itemId()) ? type.itemId() : type.getFulfilledItemId();
            return Rs2Inventory.drop(itemId);
        }
        return false;
    }

    private void createPotion(PotionType potionType, CustomMixologyConfig config) {
        for (PotionComponent component : potionType.components()) {
            if (canCreatePotion(potionType)) break;
            if (component.character() == 'A') {
                Microbot.getRs2TileObjectCache().query().withId(AlchemyObject.AGA_LEVER.objectId()).interact();
            } else if (component.character() == 'L') {
                Microbot.getRs2TileObjectCache().query().withId(AlchemyObject.LYE_LEVER.objectId()).interact();
            } else if (component.character() == 'M') {
                Microbot.getRs2TileObjectCache().query().withId(AlchemyObject.MOX_LEVER.objectId()).interact();
            }
            if (config.useQuickActionLever()) {
                Rs2Player.waitForAnimation();
            } else {
                sleepUntil(Rs2Player::isAnimating);
                final int sleep = Rs2Random.between(300, 600);
                sleepGaussian(sleep, sleep / 4);
            }
            leverRetries++;
        }
    }

    private boolean canCreatePotion(PotionType potionType) {
        Rs2TileObjectModel[] mixerModels = {
                Microbot.getRs2TileObjectCache().query().withId(ObjectID.MM_LAB_MIXER_03).nearest(),
                Microbot.getRs2TileObjectCache().query().withId(ObjectID.MM_LAB_MIXER_02).nearest(),
                Microbot.getRs2TileObjectCache().query().withId(ObjectID.MM_LAB_MIXER_01).nearest()
        };

        if (Arrays.stream(mixerModels).anyMatch(Objects::isNull)) {
            return false;
        }

        int[] currentAnimations = new int[3];
        for (int i = 0; i < mixerModels.length; i++) {
            Rs2TileObjectModel m = mixerModels[i];
            int animId = -1;
            try {
                net.runelite.api.Scene scene = Microbot.getClient().getTopLevelWorldView().getScene();
                net.runelite.api.Tile tile = scene.getTiles()[m.getWorldLocation().getPlane()][m.getLocalLocation().getSceneX()][m.getLocalLocation().getSceneY()];
                if (tile != null && tile.getGameObjects() != null) {
                    for (net.runelite.api.GameObject go : tile.getGameObjects()) {
                        if (go != null && go.getId() == mixerModels[i].getId() && go.getRenderable() instanceof DynamicObject) {
                            animId = ((DynamicObject) go.getRenderable()).getAnimation().getId();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            currentAnimations[i] = animId;
        }

        // Map components to their valid animations
        Map<Character, int[]> componentAnimations = Map.of(
                'A', new int[]{11615, 11609, 11612}, // AGA animations
                'M', new int[]{11617, 11614, 11607}, // MOX animations
                'L', new int[]{11608, 11611, 11618}  // LYE animations
        );

        // Check each position
        for (int i = 0; i < potionType.components().length; i++) {
            char expectedComponent = potionType.components()[i].character();
            int currentAnimation = currentAnimations[i];

            boolean isValid = Arrays.stream(componentAnimations.get(expectedComponent))
                    .anyMatch(validAnim -> validAnim == currentAnimation);

            if (!isValid) {
                return false;
            }
        }

        return true;
    }

    // ---------------------------------------------------------------- processing

    private void processPotions(CustomMixologyConfig config) {
        if (Rs2Player.isAnimating()) {
            handleQuickActions(config);
            return;
        }

        for (CustomPotionOrder order : pendingOrders()) {
            if (Rs2Inventory.hasItem(order.potionType().itemId())) {
                processPotion(order.potionModifier(), order.potionType());
                sleepUntil(Rs2Player::isAnimating);
                return;
            }
        }

        if (config.stockPotions()) {
            PotionType stockType = unprocessedStockType(config);
            if (stockType != null) {
                // The finished item doesn't record the station, so stay at the current one.
                processPotion(lastStation, stockType);
                sleepUntil(Rs2Player::isAnimating);
                return;
            }
        }

        mixologyState = MixologyState.MIX_POTION_STAGE_1;
    }

    private void handleQuickActions(CustomMixologyConfig config) {
        int tick = currentTick();

        if (agitatorQuickActionTicks > 0 && config.useQuickActionOnAgitator()) {
            // One click per tick inside the 2-tick window; a click outside it costs progress.
            int maxClicks = config.agitatorDoubleClick() ? 2 : 1;
            if (agitatorClicks < maxClicks && tick != lastAgitatorClickTick) {
                clickStation(AlchemyObject.AGITATOR);
                lastAgitatorClickTick = tick;
                agitatorClicks++;
            }
            return;
        }

        if (alembicQuickActionTicks > 0 && config.useQuickActionOnAlembic()) {
            clickStation(AlchemyObject.ALEMBIC);
            alembicQuickActionTicks = 0;
            return;
        }

        if (retortActive && config.useQuickActionOnRetort() && tick != lastRetortClickTick) {
            int progress = Microbot.getVarbitValue(RETORT_PROGRESS_VARBIT);
            if (progress > 0 && progress < 15) {
                clickStation(AlchemyObject.RETORT);
                lastRetortClickTick = tick;
            }
        }
    }

    private void processPotion(PotionModifier modifier, PotionType potionType) {
        lastStation = modifier;
        switch (modifier) {
            case HOMOGENOUS:
                useOrClickStation(AlchemyObject.AGITATOR, potionType, 11633, 11632);
                break;
            case CONCENTRATED:
                useOrClickStation(AlchemyObject.RETORT, potionType, 11643, 11642);
                break;
            case CRYSTALISED:
                useOrClickStation(AlchemyObject.ALEMBIC, potionType, 11638, 11637);
                break;
        }
    }

    private static void useOrClickStation(AlchemyObject station, PotionType potionType, int... loadedAnimations) {
        if (isAlchemyObjectAnimating(station, loadedAnimations)) {
            clickStation(station);
        } else {
            Rs2Inventory.useItemOnObject(potionType.itemId(), station.objectId());
        }
    }

    private static void clickStation(AlchemyObject station) {
        Microbot.getRs2TileObjectCache().query().withId(station.objectId()).interact();
    }

    private static boolean isAlchemyObjectAnimating(AlchemyObject alchemyObject, int... animationIds) {
        Rs2TileObjectModel model = Microbot.getRs2TileObjectCache().query().withId(alchemyObject.objectId()).nearest();
        if (model == null) return false;
        try {
            net.runelite.api.Scene scene = Microbot.getClient().getTopLevelWorldView().getScene();
            int plane = model.getWorldLocation().getPlane();
            int sceneX = model.getLocalLocation().getSceneX();
            int sceneY = model.getLocalLocation().getSceneY();
            net.runelite.api.Tile tile = scene.getTiles()[plane][sceneX][sceneY];
            if (tile == null || tile.getGameObjects() == null) return false;
            for (net.runelite.api.GameObject go : tile.getGameObjects()) {
                if (go != null && go.getId() == alchemyObject.objectId() && go.getRenderable() instanceof DynamicObject) {
                    int animId = ((DynamicObject) go.getRenderable()).getAnimation().getId();
                    for (int id : animationIds) {
                        if (animId == id) return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ---------------------------------------------------------------- digweed

    /**
     * Picks digweed between actions and spends it once per order cycle: on a MAL at 81+ Herblore (the best
     * points per paste), otherwise on the highest experience potion of a selected order.
     */
    private boolean handleDigweed(CustomMixologyConfig config) {
        if (!config.pickDigWeed() || Rs2Player.isAnimating() || !isMixingState()) return false;

        if (!Rs2Inventory.hasItem(DIGWEED)) {
            if (digweed == null || Rs2Inventory.emptySlotCount() == 0) return false;
            Microbot.getRs2TileObjectCache().query().withId(digweed.objectId()).interact();
            Rs2Player.waitForWalking();
            Rs2Player.waitForAnimation();
            return true;
        }

        if (digweedUsedThisCycle) return false;
        Integer target = Rs2Player.getRealSkillLevel(Skill.HERBLORE) >= MAL_HERBLORE_LEVEL
                ? malInInventory()
                : bestPotionInInventory();
        if (target == null) return false;
        useDigweedOn(target);
        return true;
    }

    private Integer malInInventory() {
        boolean malOrdered = selectedOrders().stream().anyMatch(o -> o.potionType() == PotionType.MIXALOT);
        if (!malOrdered) return null;
        if (Rs2Inventory.hasItem(PotionType.MIXALOT.itemId())) return PotionType.MIXALOT.itemId();
        if (Rs2Inventory.hasItem(PotionType.MIXALOT.getFulfilledItemId())) return PotionType.MIXALOT.getFulfilledItemId();
        return null;
    }

    // Highest experience selected-order potion in the inventory, unfinished before finished.
    private Integer bestPotionInInventory() {
        return selectedOrders().stream()
                .map(CustomPotionOrder::potionType)
                .sorted(Comparator.comparingInt(PotionType::experience).reversed())
                .flatMap(t -> Arrays.asList(t.itemId(), t.getFulfilledItemId()).stream())
                .filter(Rs2Inventory::hasItem)
                .findFirst()
                .orElse(null);
    }

    private void useDigweedOn(int itemId) {
        Rs2Inventory.interact(DIGWEED, "use");
        Rs2Inventory.interact(itemId, "use");
        // Marked used even on failure (e.g. the potion already has digweed) so it isn't retried every loop.
        digweedUsedThisCycle = true;
        Rs2Inventory.waitForInventoryChanges(1800);
    }

    // ---------------------------------------------------------------- misc

    private static int currentTick() {
        return Microbot.getClient().getTickCount();
    }

    private int getMoxPoints() {
        return Integer.parseInt(Rs2Widget.getWidget(882, 2).getDynamicChildren()[16].getText());
    }

    private int getAgaPoints() {
        return Integer.parseInt(Rs2Widget.getWidget(882, 2).getDynamicChildren()[17].getText());
    }

    private int getLyePoints() {
        return Integer.parseInt(Rs2Widget.getWidget(882, 2).getDynamicChildren()[18].getText());
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}

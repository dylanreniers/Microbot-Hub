package net.runelite.client.plugins.custom.basiliskknights;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.TileItem;
import net.runelite.client.game.ItemStats;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.JewelleryLocationEnum;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.poh.PohTeleports;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BasiliskKnightsScript extends Script {
    // ---- Target / route constants ----
    /**
     * Basilisk Knight NPC id (supplied by the task).
     */
    private static final int KNIGHT_ID = 9293;
    private static final String LUNAR_PORTAL_NAME = "Lunar Isle Portal";
    private static final String BOUQUET_NAME = "Bouquet Mac Hyacinth";
    /**
     * Walk target on Lunar Isle before talking to Bouquet Mac Hyacinth.
     */
    private static final WorldPoint BOUQUET_WALK = new WorldPoint(2099, 3914, 0);
    /**
     * Where the cave drops us underground; we wait here before walking in to the knights.
     */
    private static final WorldPoint ARRIVAL = new WorldPoint(2461, 10417, 0);
    /**
     * The single tile we always fight from.
     */
    private static final WorldPoint STAND_TILE = new WorldPoint(2454, 10395, 0);

    private static final int FEROX_POOL_ID = 39651;
    private static final int LOOT_RANGE = 16;
    /** Basilisk Knight death animation — the reliable kill signal (health ratio isn't broadcast here). */
    private static final int DEATH_ANIMATION = 8501;
    /**
     * Substrings of items we never pick up: big bones is a hard requirement; empty jars would loop.
     */
    private static final String[] LOOT_IGNORE = {"big bones", "butterfly jar"};

    public static int killCount = 0;

    private BasiliskKnightsConfig config;
    private String lastChatMessage = "";

    // Inventory setup resolved LIVE by name (never the stale config snapshot).
    private boolean setupsResolved = false;
    private Rs2InventorySetup bankingSetup = null;
    private boolean decidedStart = false;

    private Rs2NpcModel currentTarget;
    /** Big bones on the floor before the current kill — used to detect THIS kill's guaranteed drop. */
    private int bigBonesBaseline = 0;

    private enum State {TRAVEL, FEROX, FIGHTING}

    private State state = State.TRAVEL;

    /**
     * POH -> Lunar Isle portal -> Bouquet -> boat -> cave -> down to the knights.
     */
    private enum TravelStep {TELE_HOUSE, ENTER_PORTAL, WALK_TO_BOUQUET, TALK_TO_BOUQUET, BOARD_BOAT, ENTER_CAVE, DESCEND}

    private TravelStep travelStep = TravelStep.TELE_HOUSE;

    /**
     * Restock trip: POH -> jewellery box to Ferox -> pool -> bank resupply.
     */
    private enum FeroxStep {TELE_HOUSE, JEWELLERY_TO_FEROX, RESTORE_AT_POOL, RESUPPLY}

    private FeroxStep feroxStep = FeroxStep.TELE_HOUSE;

    public boolean run(BasiliskKnightsConfig config) {
        this.config = config;
        setupsResolved = false;
        killCount = 0;
        currentTarget = null;
        travelStep = TravelStep.TELE_HOUSE;
        feroxStep = FeroxStep.TELE_HOUSE;

        // Debug: a forced start state skips the automatic start decision and begins there directly.
        switch (config.startState()) {
            case TRAVEL:
                state = State.TRAVEL;
                decidedStart = true;
                break;
            case FEROX:
                state = State.FEROX;
                decidedStart = true;
                break;
            case FIGHTING:
                state = State.FIGHTING;
                decidedStart = true;
                break;
            case AUTO:
            default:
                state = State.TRAVEL;
                decidedStart = false; // first tick decides TRAVEL vs FEROX based on whether we're geared
                break;
        }

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }

                resolveSetupsOnce();
                if (bankingSetup == null) {
                    logOnceToChat("No 'Gear & Inventory setup' selected — select one in the config.");
                    return;
                }

                // On the first tick, if we're not already geared & stocked, do the Ferox restock trip first.
                if (!decidedStart) {
                    decidedStart = true;
                    if (!bankingSetup.doesEquipmentMatch() || !bankingSetup.doesInventoryMatch()) {
                        state = State.FEROX;
                        feroxStep = FeroxStep.TELE_HOUSE;
                    }
                }

                switch (state) {
                    case TRAVEL:
                        handleTravel();
                        break;
                    case FEROX:
                        handleFerox();
                        break;
                    case FIGHTING:
                        handleFighting();
                        break;
                }
            } catch (Exception ex) {
                logOnceToChat("Error in main loop: " + ex.getMessage());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    private void resolveSetupsOnce() {
        if (setupsResolved) {
            return;
        }
        setupsResolved = true;
        InventorySetup setup = config.gearSetup();
        bankingSetup = setup == null ? null : new Rs2InventorySetup(setup.getName(), mainScheduledFuture);
    }

    // ------------------------------------------------------------------
    // Travel: POH -> Lunar Isle -> Jormungand's Prison
    // ------------------------------------------------------------------
    private void handleTravel() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
        }

        switch (travelStep) {
            case TELE_HOUSE:
                BasiliskKnightsPlugin.status = "Teleporting to POH";
                if (teleportToHouse()) {
                    travelStep = TravelStep.ENTER_PORTAL;
                }
                break;

            case ENTER_PORTAL:
                BasiliskKnightsPlugin.status = "Entering the Lunar Isle Portal";
                var portal = Microbot.getRs2TileObjectCache().query().withName(LUNAR_PORTAL_NAME).firstOnClientThread();
                if (portal == null) {
                    logOnceToChat("Could not find portal.");
                    return;
                }
                if (portal.click("Enter")) {
                    if (sleepUntil(() -> !PohTeleports.isInHouse(), 10000)) {
                        sleepUntil(() -> !Rs2Player.isMoving(), 3000);
                        travelStep = TravelStep.WALK_TO_BOUQUET;
                    }
                }
                break;

            case WALK_TO_BOUQUET:
                BasiliskKnightsPlugin.status = "Walking to Bouquet Mac Hyacinth";
                if (BOUQUET_WALK.distanceTo(Rs2Player.getWorldLocation()) > 2) {
                    Rs2Walker.walkFastCanvas(BOUQUET_WALK);
                    sleepUntil(() -> BOUQUET_WALK.distanceTo(Rs2Player.getWorldLocation()) <= 2
                            || !Rs2Player.isMoving(), 4000);
                    return;
                }
                travelStep = TravelStep.TALK_TO_BOUQUET;
                break;

            case TALK_TO_BOUQUET:
                BasiliskKnightsPlugin.status = "Talking to Bouquet Mac Hyacinth";
                Rs2NpcModel bouquet = Microbot.getRs2NpcCache().query().withName(BOUQUET_NAME).nearest();
                if (bouquet == null) {
                    return;
                }
                if (bouquet.click("Talk-to") && Rs2Dialogue.sleepUntilInDialogue()) {
                    // Advance the dialogue four times, then move on to board the boat.
                    for (int i = 0; i < 4; i++) {
                        Rs2Dialogue.sleepUntilHasContinue();
                        Rs2Dialogue.clickContinue();
                        sleep(600, 900);
                    }
                    Rs2Dialogue.sleepUntilNotInDialogue();
                    travelStep = TravelStep.BOARD_BOAT;
                }
                break;

            case BOARD_BOAT:
                BasiliskKnightsPlugin.status = "Travelling on the Fremennik Boat";

                Rs2NpcModel haskell = Microbot.getRs2NpcCache().query().withName("Haskell").nearest();
                if (haskell == null) {
                    return;
                }

                haskell.click("Island of Stone");
                sleepUntil(() -> !Rs2Player.isMoving(), 8000);
                sleep(3600);
                travelStep = TravelStep.ENTER_CAVE;
                break;

            case ENTER_CAVE:
                BasiliskKnightsPlugin.status = "Entering the cave";
                sleepUntil(() -> Microbot.getRs2TileObjectCache().query().withName("Cave").firstOnClientThread() != null);
                var cave = Microbot.getRs2TileObjectCache().query().withName("Cave").firstOnClientThread();
                if (cave == null) {
                    logOnceToChat("Cave is null.");
                    return;
                }
                if (cave.click("Enter")) {
                    // "Enter" drops us underground at ARRIVAL; wait until we've actually arrived.
                    if (sleepUntil(() -> ARRIVAL.distanceTo(Rs2Player.getWorldLocation()) <= 5, 15000)) {
                        travelStep = TravelStep.DESCEND;
                    }
                }
                break;

            case DESCEND:
                BasiliskKnightsPlugin.status = "Walking to the attack position";
                if (!STAND_TILE.equals(Rs2Player.getWorldLocation())) {
                    Rs2Walker.walkTo(STAND_TILE, 0);
                    sleepUntil(() -> STAND_TILE.equals(Rs2Player.getWorldLocation())
                            || !Rs2Player.isMoving(), 6000);
                    return;
                }
                travelStep = TravelStep.TELE_HOUSE; // reset for the next trip
                state = State.FIGHTING;
                break;
        }
    }

    /**
     * Casts the standard-spellbook Teleport to House and waits until we're inside the POH.
     */
    private boolean teleportToHouse() {
        if (PohTeleports.isInHouse()) {
            return true;
        }
        if (!Rs2Magic.cast(MagicAction.TELEPORT_TO_HOUSE)) {
            logOnceToChat("Could not cast Teleport to House (missing runes / not on the standard spellbook?)");
            return false;
        }
        return sleepUntil(PohTeleports::isInHouse, 8000);
    }

    // ------------------------------------------------------------------
    // Fighting
    // ------------------------------------------------------------------
    private void handleFighting() {
        logOnceToChat("Being attacked? " + isBeingAttacked());

        // Finished a kill -> wait for the drop to hit the floor, loot it, THEN stop this tick so we don't
        // immediately engage another knight before looting. A kill is detected by the death animation
        // (8501) or by the target despawning (health ratio isn't broadcast here, so isDead() is unreliable).
        if (currentTarget != null && hasTargetDied()) {
            currentTarget = null;
            BasiliskKnightsPlugin.status = "Waiting for loot to drop";
            // Big bones are a guaranteed drop, so the pile has landed once a NEW one appears. Waiting on
            // any lootable item would return instantly on stray bolts already on the floor. Throughout
            // this wait, keep Protect from Magic off to save prayer but flick it on if a knight attacks us.
            long dropDeadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < dropDeadline && countBigBones(LOOT_RANGE) <= bigBonesBaseline) {
                ensureProtectFromMagic(isBeingAttacked());
                sleep(300);
            }
            BasiliskKnightsPlugin.status = "Looting";
            attemptLooting();
            killCount++;
            return;
        }

        // Between kills: if we're out of prayer restoration items, head back to restock (the current
        // kill is already finished above — this only triggers once the target is cleared).
        if (currentTarget == null && isOutOfPrayerItems()) {
            BasiliskKnightsPlugin.status = "Out of prayer supplies — returning home";
            ensureProtectFromMagic(false);
            startFeroxTrip();
            return;
        }

        // Acquire a target.
        if (currentTarget == null || currentTarget.isDead()) {
            currentTarget = findKnight();
            if (currentTarget == null) {
                // No knight to fight — drop the prayer so we don't waste points while idle.
                ensureProtectFromMagic(false);
                BasiliskKnightsPlugin.status = "Waiting for a Basilisk Knight";
                return;
            }
        }

        // If a different knight is attacking us, switch to it — fight whatever is actually hitting us.
        switchToAttackerIfNeeded();

        // We have a target: pray up BEFORE attacking so the knight's first retaliation is blocked.
        ensureProtectFromMagic(true);

        // Sample the big bones already on the floor while the knight is still alive, so when it dies we
        // can wait for its guaranteed Big bones drop (a NEW one) rather than mistaking leftover bones or
        // stray bolts for this kill's loot.
        bigBonesBaseline = countBigBones(LOOT_RANGE);

        // Keep supplies topped up. Eating/drinking/releasing interrupts the attack, so force a re-attack
        // afterwards rather than waiting for the interaction to lapse.
        boolean consumed = restorePrayerIfLow();
        consumed |= Rs2Player.eatAt(config.minEatPercent());

        // Always fight from the fixed stand tile — walk back if we've been pushed off it, and re-attack
        // once we're back on it (walking there breaks the attack).
        boolean returnedToPosition = false;
        if (!STAND_TILE.equals(Rs2Player.getWorldLocation())) {
            BasiliskKnightsPlugin.status = "Returning to attack position";
            Rs2Walker.walkFastCanvas(STAND_TILE);
            sleepUntil(() -> STAND_TILE.equals(Rs2Player.getWorldLocation())
                    || !Rs2Player.isMoving(), 3000);
            if (!STAND_TILE.equals(Rs2Player.getWorldLocation())) {
                return; // not back on the tile yet — retry the walk next tick
            }
            returnedToPosition = true; // back in position -> click attack again below
        }

        // Attack if we just returned to position, consumed a supply (both break the attack), or aren't
        // engaged with the target.
        if (returnedToPosition || consumed || !isAttacking(currentTarget)) {
            BasiliskKnightsPlugin.status = "Ranging Basilisk Knight";
            if (currentTarget.click("Attack")) {
                sleepUntil(() -> isAttacking(currentTarget), 1500);
            } else {
                // Stale / despawned model (e.g. "NPCComposition is null") — drop it and re-acquire.
                currentTarget = null;
            }
        }
    }

    private Rs2NpcModel findKnight() {
        // Prefer a knight that is already attacking us — it's guaranteed in range and line-of-sight.
        // Do NOT filter on isInteracting(): these knights are permanently aggressive, so that excludes
        // exactly the ones you should be ranging and leaves only distant/unreachable targets.
        Rs2NpcModel engaging = Microbot.getRs2NpcCache().query()
                .withName("Basilisk Knight")
                .where(npc -> !npc.isDead() && npc.isInteractingWithPlayer())
                .nearestOnClientThread();
        if (engaging != null) {
            return engaging;
        }
        return Microbot.getRs2NpcCache().query()
                .withName("Basilisk Knight")
                .where(npc -> !npc.isDead())
                .nearestOnClientThread();
    }

    /**
     * If a Basilisk Knight other than our current target is attacking us, switch to it. Keeps the current
     * target while it is itself attacking us, so we don't thrash between multiple attackers.
     */
    private void switchToAttackerIfNeeded() {
        if (currentTarget == null) {
            return;
        }
        boolean currentAttackingUs = Microbot.getRs2NpcCache().query()
                .withName("Basilisk Knight")
                .where(npc -> npc.getIndex() == currentTarget.getIndex() && npc.isInteractingWithPlayer())
                .firstOnClientThread() != null;
        if (currentAttackingUs) {
            return;
        }
        Rs2NpcModel attacker = Microbot.getRs2NpcCache().query()
                .withName("Basilisk Knight")
                .where(npc -> !npc.isDead() && npc.isInteractingWithPlayer())
                .nearestOnClientThread();
        if (attacker != null && attacker.getIndex() != currentTarget.getIndex()) {
            logOnceToChat("Switching to the knight attacking us");
            currentTarget = attacker;
        }
    }

    /** True while {@code npc} is playing its death animation (8501) — our kill-complete signal. */
    private boolean isDying(Rs2NpcModel npc) {
        if (npc == null) {
            return false;
        }
        return Microbot.getClientThread().invoke(() -> npc.getAnimation()) == DEATH_ANIMATION;
    }

    /**
     * True once the current target has died. Detected either by its death animation (8501) or by it
     * despawning from the scene — the despawn is the reliable fallback for when the 1s loop skips over
     * the brief animation frame.
     */
    private boolean hasTargetDied() {
        if (currentTarget == null) {
            return false;
        }
        if (isDying(currentTarget)) {
            return true;
        }
        int index = currentTarget.getIndex();
        Rs2NpcModel stillAlive = Microbot.getRs2NpcCache().query()
                .withName("Basilisk Knight")
                .where(npc -> npc.getIndex() == index && !npc.isDead())
                .firstOnClientThread();
        return stillAlive == null;
    }

    /**
     * True if a Basilisk Knight is currently attacking (interacting with) the local player.
     */
    private boolean isBeingAttacked() {
        return Microbot.getRs2NpcCache().query()
                .withName("Basilisk Knight")
                .where(Rs2NpcModel::isInteractingWithPlayer)
                .firstOnClientThread() != null;
    }

    /**
     * True if <b>the player</b> is attacking {@code target}.
     *
     * <p>This checks only the player's own interaction ({@link Rs2Player#getInteracting()}). Do NOT use
     * {@code target.isInteractingWithPlayer()} here: the knights are aggressive, so that's true whenever
     * the knight is attacking us — which says nothing about whether we're attacking back. Keying off it
     * made the script think it was engaged and sit idle while the knight hit us.</p>
     */
    private boolean isAttacking(Rs2NpcModel target) {
        if (target == null) {
            return false;
        }
        Actor interacting = Rs2Player.getInteracting();
        return interacting instanceof NPC && ((NPC) interacting).getIndex() == target.getIndex();
    }

    private void ensureProtectFromMagic(boolean on) {
        boolean active = Rs2Prayer.getActiveProtectionPrayer() == Rs2PrayerEnum.PROTECT_MAGIC;
        if (on && !active) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
        } else if (!on && active) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, false);
        }
    }

    // ------------------------------------------------------------------
    // Prayer restoration
    // ------------------------------------------------------------------
    /** @return true if a prayer item was consumed/released this call (so the caller should re-attack). */
    private boolean restorePrayerIfLow() {
        if (Rs2Player.getPrayerPercentage() > config.minPrayerPercent()) {
            return false;
        }

        // Releasing a caught moonlight moth restores prayer for free — burn those first.
        if (Rs2Inventory.hasItem(ItemID.BUTTERFLY_JAR_MOONMOTH)) {
            Rs2Inventory.interact(ItemID.BUTTERFLY_JAR_MOONMOTH, "Release");
            sleep(300, 500);
            dropEmptyButterflyJars();
            return true;
        }

        // Then a moonlight moth mix, then finally a prayer potion.
        Rs2ItemModel mix = Rs2Inventory.items()
                .filter(i -> i != null && i.getName() != null
                        && i.getName().toLowerCase().contains("moonlight moth mix"))
                .findFirst()
                .orElse(null);
        if (mix != null) {
            Rs2Inventory.interact(mix, "Drink");
            dropEmptyButterflyJars();
            return true;
        }

        return Rs2Player.drinkPrayerPotionAt(config.minPrayerPercent());
    }

    /**
     * True once no prayer potions, moonlight moths or moonlight moth mixes remain in the inventory.
     */
    private boolean isOutOfPrayerItems() {
        return Rs2Inventory.items().noneMatch(i -> {
            if (i == null || i.getName() == null) {
                return false;
            }
            String name = i.getName().toLowerCase();
            // "moonlight moth" covers both the caught moth (jar) and the moth mix.
            return name.contains("prayer potion") || name.contains("moonlight moth");
        });
    }

    private void dropEmptyButterflyJars() {
        if (Rs2Inventory.hasItem("Butterfly jar")) {
            Rs2Inventory.dropAll("Butterfly jar");
        }
    }

    // ------------------------------------------------------------------
    // Looting
    // ------------------------------------------------------------------
    private void attemptLooting() {
        long start = System.currentTimeMillis();
        long deadline = start + 8000;

        while (System.currentTimeMillis() < deadline) {
            // Preserve prayer while looting, but flick Protect from Magic on the moment a knight attacks us.
            ensureProtectFromMagic(isBeingAttacked());
            if (lootNextDrop()) {
                deadline = Math.min(start + 15000, System.currentTimeMillis() + 2500);
            } else if (!hasLootableDrops()) {
                break;
            } else {
                sleep(400);
            }
        }
    }

    private boolean lootNextDrop() {
        RS2Item[] groundItems = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Rs2GroundItem.getAll(LOOT_RANGE))
                .orElse(new RS2Item[]{});

        for (RS2Item item : groundItems) {
            if (!isLootable(item)) {
                continue;
            }
            if (needsInventorySpace(item) && !makeSpaceForLoot()) {
                continue;
            }
            if (Rs2GroundItem.interact(item)) {
                Rs2Inventory.waitForInventoryChanges(2000);
                return true;
            }
        }
        return false;
    }

    /** Number of Big bones ground-item stacks within {@code range} of the player. */
    private int countBigBones(int range) {
        RS2Item[] groundItems = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Rs2GroundItem.getAll(range))
                .orElse(new RS2Item[]{});
        int count = 0;
        for (RS2Item item : groundItems) {
            if (item != null && item.getItem() != null
                    && "Big bones".equalsIgnoreCase(item.getItem().getName())) {
                count++;
            }
        }
        return count;
    }

    private boolean hasLootableDrops() {
        RS2Item[] groundItems = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Rs2GroundItem.getAll(LOOT_RANGE))
                .orElse(new RS2Item[]{});

        for (RS2Item item : groundItems) {
            if (isLootable(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLootable(RS2Item item) {
        if (item == null || item.getItem() == null) {
            return false;
        }
        String name = item.getItem().getName();
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String ignore : LOOT_IGNORE) {
            if (lower.contains(ignore)) {
                return false;
            }
        }
        if (config.ignoreAmmunition() && isAmmunition(item)) {
            return false;
        }
        if (config.lootMyLootOnly() && item.getTileItem() != null
                && item.getTileItem().getOwnership() != TileItem.OWNERSHIP_SELF
                && item.getTileItem().getOwnership() != TileItem.OWNERSHIP_NONE) {
            return false;
        }
        return true;
    }

    /** True if the item equips in the ammunition slot (bolts, arrows, darts, etc.). */
    private boolean isAmmunition(RS2Item item) {
        if (item == null || item.getItem() == null) {
            return false;
        }
        int id = item.getItem().getId();
        // getItemStats() must run on the client thread — calling it from the script thread throws
        // "must be called on client thread", which would abort the whole loot pass.
        Boolean ammo = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            ItemStats stats = Microbot.getItemManager().getItemStats(id);
            return stats != null && stats.getEquipment() != null
                    && stats.getEquipment().getSlot() == EquipmentInventorySlot.AMMO.getSlotIdx();
        }).orElse(false);
        return Boolean.TRUE.equals(ammo);
    }

    private boolean needsInventorySpace(RS2Item item) {
        if (!Rs2Inventory.isFull()) {
            return false;
        }
        if (item != null && item.getItem() != null
                && item.getItem().isStackable() && Rs2Inventory.hasItem(item.getItem().getId())) {
            return false;
        }
        return true;
    }

    /**
     * Eat to free an inventory slot so loot fits.
     */
    private boolean makeSpaceForLoot() {
        if (!Rs2Inventory.isFull()) {
            return true;
        }
        List<Rs2ItemModel> foods = Rs2Inventory.getInventoryFood();
        if (foods.isEmpty()) {
            return false;
        }
        Rs2ItemModel food = foods.get(0);
        logOnceToChat("Inventory full — eating " + food.getName() + " to make space for loot");
        Rs2Inventory.interact(food, "Eat");
        return sleepUntil(() -> !Rs2Inventory.isFull(), 1800);
    }

    // ------------------------------------------------------------------
    // Ferox restock trip
    // ------------------------------------------------------------------
    private void startFeroxTrip() {
        currentTarget = null;
        feroxStep = FeroxStep.TELE_HOUSE;
        state = State.FEROX;
    }

    private void handleFerox() {
        switch (feroxStep) {
            case TELE_HOUSE:
                BasiliskKnightsPlugin.status = "Teleporting home to restock";
                if (teleportToHouse()) {
                    feroxStep = FeroxStep.JEWELLERY_TO_FEROX;
                }
                break;

            case JEWELLERY_TO_FEROX:
                BasiliskKnightsPlugin.status = "Jewellery box -> Ferox Enclave";
                if (PohTeleports.useJewelleryBox(JewelleryLocationEnum.FEROX_ENCLAVE)) {
                    sleepUntil(() -> !PohTeleports.isInHouse(), 8000);
                    feroxStep = FeroxStep.RESTORE_AT_POOL;
                }
                break;

            case RESTORE_AT_POOL:
                BasiliskKnightsPlugin.status = "Restoring at the pool";
                if (restoreAtPool()) {
                    feroxStep = FeroxStep.RESUPPLY;
                }
                break;

            case RESUPPLY:
                BasiliskKnightsPlugin.status = "Resupplying at the bank";
                if (resupplyAtBank()) {
                    // Resupplied — start the cycle again: POH -> Lunar Isle -> knights.
                    travelStep = TravelStep.TELE_HOUSE;
                    state = State.TRAVEL;
                }
                break;
        }
    }

    /**
     * Drinks at the Ferox Pool of Refreshment until HP and Prayer are back to full.
     */
    private boolean restoreAtPool() {
        int maxHealth = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
        int maxPrayer = Microbot.getClient().getRealSkillLevel(Skill.PRAYER);
        if (Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) >= maxHealth
                && Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) >= maxPrayer) {
            return true;
        }
        if (Microbot.getRs2TileObjectCache().query().interact(FEROX_POOL_ID, "Drink")) {
            Rs2Player.waitForAnimation();
            sleepUntil(() -> Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) >= maxHealth
                    && Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) >= maxPrayer, 8000);
            return Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) >= maxHealth;
        }
        return false;
    }

    /**
     * Opens the Ferox bank, re-wears the setup's equipment, deposits inventory and reloads it.
     */
    private boolean resupplyAtBank() {
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            if (!sleepUntil(Rs2Bank::isOpen, 8000)) {
                return false;
            }
        }

        // Wear the setup equipment before depositing, so swapped-out gear goes into the inventory first.
        if (!bankingSetup.doesEquipmentMatch()) {
            bankingSetup.wearEquipment();
            sleepUntil(bankingSetup::doesEquipmentMatch, 3000);
            if (!bankingSetup.doesEquipmentMatch()) {
                bankingSetup.loadEquipment();
                sleepUntil(bankingSetup::doesEquipmentMatch, 5000);
            }
        }
        if (!bankingSetup.doesEquipmentMatch()) {
            logOnceToChat("Could not equip gear setup from the bank.");
            return false;
        }

        Rs2Bank.depositAll();
        sleepUntil(Rs2Inventory::isEmpty, 2000);

        if (bankingSetup.loadInventory()) {
            Rs2Bank.closeBank();
            return true;
        }
        logOnceToChat("Resupply incomplete (missing setup items in bank?) — retrying.");
        return false;
    }

    private void logOnceToChat(String message) {
        if (!message.equals(lastChatMessage)) {
            Microbot.log(message);
            lastChatMessage = message;
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, false);
        currentTarget = null;
        setupsResolved = false;
        decidedStart = false;
        bankingSetup = null;
        state = State.TRAVEL;
        travelStep = TravelStep.TELE_HOUSE;
        feroxStep = FeroxStep.TELE_HOUSE;
        if (mainScheduledFuture != null && !mainScheduledFuture.isCancelled()) {
            mainScheduledFuture.cancel(true);
        }
    }
}

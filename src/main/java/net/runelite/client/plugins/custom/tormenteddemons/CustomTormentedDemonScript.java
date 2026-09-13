package net.runelite.client.plugins.custom.tormenteddemons;

import net.runelite.api.HeadIcon;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.JewelleryLocationEnum;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.poh.PohTeleports;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CustomTormentedDemonScript extends Script {

    private boolean isRunning = false;
    public static int killCount = 0;
    private Rs2PrayerEnum currentDefensivePrayer = null;
    private Rs2PrayerEnum currentOffensivePrayer = null;
    private HeadIcon currentOverheadIcon = null;
    public Rs2NpcModel currentTarget;
    private boolean lootAttempted = false;
    private String lastChatMessage = "";

    // Gear/inventory setups resolved LIVE by name from the config (never the stale InventorySetup
    // snapshot the config returns). Resolved once on the first tick, when mainScheduledFuture is set.
    private boolean setupsResolved = false;
    private Rs2InventorySetup bankingSetup = null;
    private Rs2InventorySetup rangeSetup = null;
    private Rs2InventorySetup magicSetup = null;
    private Rs2InventorySetup meleeSetup = null;

    /** The setup for the combat style we've decided to attack with (counter to the demon's overhead).
     *  Kept sticky while it stays a valid counter for the current overhead + enabled styles, so a random
     *  dual-style pick isn't re-rolled every tick; re-picked as soon as it's no longer valid. */
    private Rs2InventorySetup intendedGearSetup = null;

    private static final int LOOT_RANGE = 10;
    /**
     * Demon drops consumed on the ground for their reward instead of being picked up, each with its own menu
     * action: the pile of flesh is "Eat-from", the gland is "Crush". (The smouldering heart is a normal
     * "Take", so it's left to the regular loot pass.) {@code [name, action]}.
     */
    private static final String[][] SMOULDERING_DROPS = {
            {"Smouldering pile of flesh", "Eat-from"},
            {"Smouldering gland", "Crush"}
    };

    // ---- FULL_AUTO restock + travel constants ----
    private static final int FEROX_POOL_ID = 39651;
    private static final int LIGHT_CREATURE_ID = 5435;
    private static final int TOG_ROCKS_ID = 6673;       // "Climb"
    private static final int WALL_ONE_ID = 53623;        // "Climb-up"
    private static final int WALL_TWO_ID = 53624;        // "Climb-up"
    private static final int OPENING_ID = 53622;         // "Climb-through"
    private static final WorldPoint DEMON_LOCATION = new WorldPoint(4041, 4452, 0);
    /** Time the light creature takes to carry us into the chasm before the walls are reachable. */
    private static final int CHASM_TRAVEL_WAIT_MS = 15000;

    private enum State {BANKING, TRAVEL_TO_TORMENTED, FIGHTING}

    public static State BOT_STATUS = State.BANKING;

    /** Travel from Tears of Guthix (Juna's cave) down through the chasm to the Tormented Demons. */
    private enum TravelStep {CLIMB_ROCKS, USE_LANTERN, SELECT_TRAVEL, WAIT_IN_CHASM, CLIMB_WALL_ONE, CLIMB_WALL_TWO, CLIMB_OPENING, RUN_TO_DEMONS}

    private TravelStep travelStep = TravelStep.CLIMB_ROCKS;

    /** Restock trip: POH -> jewellery box to Ferox -> pool + bank -> POH -> jewellery box to Tears of Guthix. */
    private enum BankingStep {TELE_HOUSE_TO_FEROX, JEWELLERY_TO_FEROX, RESTORE_AT_POOL, RESUPPLY, TELE_HOUSE_TO_TOG, JEWELLERY_TO_TOG}

    private BankingStep bankingStep = BankingStep.TELE_HOUSE_TO_FEROX;

    public boolean run(CustomTormentedDemonConfig config) {
        if (config.mode() == CustomTormentedDemonConfig.MODE.FULL_AUTO) {
            BOT_STATUS = State.BANKING;
        } else if (config.mode() == CustomTormentedDemonConfig.MODE.COMBAT_ONLY) {
            BOT_STATUS = State.FIGHTING;
        }

        bankingStep = BankingStep.TELE_HOUSE_TO_FEROX;
        travelStep = TravelStep.CLIMB_ROCKS;
        setupsResolved = false;
        Microbot.enableAutoRunOn = false;
        isRunning = true;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                resolveSetupsOnce(config);

                switch (BOT_STATUS) {
                    case BANKING:
                        handleBanking(config);
                        break;
                    case TRAVEL_TO_TORMENTED:
                        handleTravel(config);
                        break;
                    case FIGHTING:
                        handleFighting(config);
                        break;
                }
            } catch (Exception ex) {
                logOnceToChat("Error in main loop: " + ex.getMessage());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * Resolve the configured inventory setups once, by NAME. The InventorySetup object the config returns
     * is a snapshot serialized when it was picked in the dropdown, so editing the setup afterwards leaves
     * it stale; the String constructor looks the setup up live from the Inventory Setups plugin. (Editing
     * a setup while the plugin runs still needs a restart to re-resolve.)
     */
    private void resolveSetupsOnce(CustomTormentedDemonConfig config) {
        if (setupsResolved) {
            return;
        }
        setupsResolved = true;
        bankingSetup = resolveSetup(config.gearSetup());
        rangeSetup = resolveSetup(config.rangeGear());
        magicSetup = resolveSetup(config.magicGear());
        meleeSetup = resolveSetup(config.meleeGear());
    }

    /** Build an {@link Rs2InventorySetup} resolved live by the setup's name (never the stale config snapshot). */
    private Rs2InventorySetup resolveSetup(InventorySetup setup) {
        return setup == null ? null : new Rs2InventorySetup(setup.getName(), mainScheduledFuture);
    }


    private void handleBanking(CustomTormentedDemonConfig config) {
        if (bankingSetup == null) {
            logOnceToChat("No 'Gear & Inventory setup' selected — cannot restock. Select one in the config.");
            shutdown();
            return;
        }

        switch (bankingStep) {
            case TELE_HOUSE_TO_FEROX:
                Microbot.status = "Teleporting to house...";
                if (teleportToHouse()) {
                    bankingStep = BankingStep.JEWELLERY_TO_FEROX;
                }
                break;

            case JEWELLERY_TO_FEROX:
                Microbot.status = "Jewellery box -> Ferox Enclave...";
                if (PohTeleports.useJewelleryBox(JewelleryLocationEnum.FEROX_ENCLAVE)) {
                    sleepUntil(() -> !PohTeleports.isInHouse(), 8000);
                    bankingStep = BankingStep.RESTORE_AT_POOL;
                }
                break;

            case RESTORE_AT_POOL:
                Microbot.status = "Restoring at the pool...";
                if (restoreAtPool()) {
                    bankingStep = BankingStep.RESUPPLY;
                }
                break;

            case RESUPPLY:
                Microbot.status = "Resupplying at bank...";
                if (resupplyAtBank()) {
                    bankingStep = BankingStep.TELE_HOUSE_TO_TOG;
                }
                break;

            case TELE_HOUSE_TO_TOG:
                Microbot.status = "Teleporting to house...";
                if (teleportToHouse()) {
                    bankingStep = BankingStep.JEWELLERY_TO_TOG;
                }
                break;

            case JEWELLERY_TO_TOG:
                Microbot.status = "Jewellery box -> Tears of Guthix...";
                if (PohTeleports.useJewelleryBox(JewelleryLocationEnum.TEARS_OF_GUTHIX)) {
                    sleepUntil(() -> !PohTeleports.isInHouse(), 8000);
                    bankingStep = BankingStep.TELE_HOUSE_TO_FEROX; // reset for the next restock trip
                    travelStep = TravelStep.CLIMB_ROCKS;
                    BOT_STATUS = State.TRAVEL_TO_TORMENTED;
                }
                break;
        }
    }

    /** Casts the standard-spellbook Teleport to House and waits until we're inside the POH. */
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

    /** Drinks at the pool (Ferox Pool of Refreshment) until HP and Prayer are back to full. */
    private boolean restoreAtPool() {
        int maxHealth = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
        int maxPrayer = Microbot.getClient().getRealSkillLevel(Skill.PRAYER);
        boolean full = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) >= maxHealth
                && Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) >= maxPrayer;
        if (full) {
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

    /** Opens the Ferox bank, deposits everything and reloads the banking inventory setup. */
    private boolean resupplyAtBank() {
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            if (!sleepUntil(Rs2Bank::isOpen, 8000)) {
                return false;
            }
        }
        Rs2Bank.depositAll();
        boolean equipmentLoaded = bankingSetup.loadEquipment();
        boolean inventoryLoaded = bankingSetup.loadInventory();
        if (equipmentLoaded && inventoryLoaded) {
            Rs2Bank.closeBank();
            return true;
        }
        logOnceToChat("Resupply incomplete (missing setup items in bank?) — retrying.");
        return false;
    }

    private void handleTravel(CustomTormentedDemonConfig config) {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
        }

        switch (travelStep) {
            case CLIMB_ROCKS:
                Microbot.status = "Climbing the rocks...";
                if (Microbot.getRs2TileObjectCache().query().interact(TOG_ROCKS_ID, "Climb")) {
                    Rs2Player.waitForWalking();
                    sleepUntil(() -> !Rs2Player.isMoving(), 5000);
                    travelStep = TravelStep.USE_LANTERN;
                }
                break;

            case USE_LANTERN:
                Microbot.status = "Using Sapphire lantern on the light creature...";
                if (useLanternOnLightCreature()) {
                    travelStep = TravelStep.SELECT_TRAVEL;
                }
                break;

            case SELECT_TRAVEL:
                Microbot.status = "Selecting 'Travel into chasm'...";
                if (Rs2Dialogue.sleepUntilSelectAnOption() && Rs2Dialogue.clickOption("Travel into chasm")) {
                    travelStep = TravelStep.WAIT_IN_CHASM;
                }
                break;

            case WAIT_IN_CHASM:
                Microbot.status = "Travelling through the chasm...";
                sleep(CHASM_TRAVEL_WAIT_MS);
                travelStep = TravelStep.CLIMB_WALL_ONE;
                break;

            case CLIMB_WALL_ONE:
                Microbot.status = "Climbing the first wall...";
                if (Microbot.getRs2TileObjectCache().query().interact(WALL_ONE_ID, "Climb-up")) {
                    Rs2Player.waitForAnimation();
                    sleepUntil(() -> !Rs2Player.isAnimating());
                    travelStep = TravelStep.CLIMB_WALL_TWO;
                }
                break;

            case CLIMB_WALL_TWO:
                Microbot.status = "Climbing the second wall...";
                if (Microbot.getRs2TileObjectCache().query().interact(WALL_TWO_ID, "Climb-up")) {
                    Rs2Player.waitForAnimation();
                    sleepUntil(() -> !Rs2Player.isAnimating());
                    travelStep = TravelStep.CLIMB_OPENING;
                }
                break;

            case CLIMB_OPENING:
                Microbot.status = "Climbing through the opening...";
                if (Microbot.getRs2TileObjectCache().query().interact(OPENING_ID, "Climb-through")) {
                    Rs2Player.waitForAnimation();
                    sleepUntil(() -> !Rs2Player.isAnimating());
                    travelStep = TravelStep.RUN_TO_DEMONS;
                }
                break;

            case RUN_TO_DEMONS:
                Microbot.status = "Approaching Tormented Demon location...";
                if (Rs2Walker.walkTo(DEMON_LOCATION, 3)) {
                    sleepUntil(() -> DEMON_LOCATION.distanceTo(Rs2Player.getWorldLocation()) <= 5, 8000);
                    travelStep = TravelStep.CLIMB_ROCKS; // reset for the next trip
                    BOT_STATUS = State.FIGHTING;
                }
                break;
        }
    }

    /** Uses the Sapphire lantern on the nearest light creature to begin chasm travel. */
    private boolean useLanternOnLightCreature() {
        Rs2ItemModel lantern = Rs2Inventory.get("Sapphire lantern");
        if (lantern == null) {
            logOnceToChat("No Sapphire lantern in inventory for the light creature.");
            return false;
        }
        var lightCreature = Rs2Npc.getNpc(LIGHT_CREATURE_ID);
        if (lightCreature == null) {
            return false;
        }
        return Rs2Inventory.useItemOnNpc(lantern.getId(), lightCreature);
    }

    private void handleFighting(CustomTormentedDemonConfig config) {
        if (currentTarget == null || currentTarget.isDead()) {
            disableAllPrayers();

            if (!lootAttempted) {
                Microbot.pauseAllScripts.compareAndSet(false, true);
                sleep(5000);
                attemptLooting(config);
                lootAttempted = true;
                Microbot.pauseAllScripts.compareAndSet(true, false);
                killCount++;
            }

            currentTarget = findNewTarget(config);
            if (currentTarget.getInteracting() != Microbot.getClient().getLocalPlayer()) {
                currentTarget = findNewTarget(config);
            }
            if (currentTarget != null) {
                // We don't yet know the demon's attack style, so default to Protect from Melee — a 1/3 chance
                // of being right on the opening attack. The plugin's onAnimationChanged corrects it the moment
                // the demon actually attacks.
                prayDefaultDefensivePrayer(config);
                currentOverheadIcon = Microbot.getClientThread().invoke(() -> currentTarget.getHeadIcon());
                Microbot.log("Acquired target overhead: " + currentOverheadIcon);
                if (currentOverheadIcon == null) {
                    logOnceToChat("Failed to retrieve HeadIcon for target.");
                    return;
                }
                switchGear(config, currentOverheadIcon);
                lootAttempted = false;
            } else {
                logOnceToChat("No target found for attack.");
                return;
            }
        }

        evaluateAndConsumePotions(config);

        if (config.mode() == CustomTormentedDemonConfig.MODE.FULL_AUTO && shouldRetreat(config)) {
            currentTarget = null;
            currentOverheadIcon = null;
            intendedGearSetup = null;
            disableAllPrayers();
            // Start the restock trip from the top: teleport home, jewellery box to Ferox, restore, resupply.
            bankingStep = BankingStep.TELE_HOUSE_TO_FEROX;
            BOT_STATUS = State.BANKING;
            return;
        }

        if (currentTarget != null && !currentTarget.isDead()) {

            Rs2Player.eatAt(config.minEatPercent());
            Rs2Player.drinkPrayerPotionAt(config.minPrayerPercent());

            var interactingActor = Rs2Player.getInteracting();
            int interactingIndex = (interactingActor instanceof NPC) ? ((NPC) interactingActor).getIndex() : -1;

            if (currentTarget == null) return;

            if (interactingActor == null || interactingIndex != currentTarget.getIndex()) {
                boolean attackSuccessful = currentTarget.click("attack");

                if (attackSuccessful) {
                    // Only wait briefly for the interaction to register. Do NOT block on
                    // Rs2Player.waitForAnimation() here: it waits up to ~5s for the attack to start and again
                    // for it to finish, stalling the loop for many seconds — which delays prayer/gear/re-attack
                    // and is why attacks were being dropped. The loop re-checks and re-attacks each tick anyway.
                    sleepUntil(() -> {
                        var a = Rs2Player.getInteracting();
                        return a instanceof NPC && ((NPC) a).getIndex() == currentTarget.getIndex();
                    }, 1200);
                } else {
                    logOnceToChat("Attack failed for target: " + (currentTarget != null ? currentTarget.getName() : "null"));
                    currentTarget = null;
                    return;
                }
            }
        }

        if (currentTarget == null) return;

        // Read the overhead + current animation on the client thread. NOTE: the demon's overhead is its
        // PROTECTION prayer (drives our gear), which is independent of the style it ATTACKS with (drives our
        // defensive prayer). "demon attack" below is the demon's current attack animation → the style we pray.
        HeadIcon newOverheadIcon = Microbot.getClientThread().invoke(() -> currentTarget.getHeadIcon());
        int demonAnim = Microbot.getClientThread().invoke(() -> currentTarget.getAnimation());
        Microbot.log("Demon overhead(protect): " + newOverheadIcon
                + " | tracked: " + currentOverheadIcon
                + " | demon attack: " + demonAttackLabel(demonAnim)
                + " | my prayer: " + Rs2Prayer.getActiveProtectionPrayer()
                + " | attacking with: " + gearLabel(intendedGearSetup));

        // Only react to a real, non-null overhead. getHeadIcon() returns null between prayer flips; treating
        // that as a change would desync tracking.
        if (newOverheadIcon != null) {
            if (newOverheadIcon != currentOverheadIcon) {
                logOnceToChat("Demon overhead changed to " + newOverheadIcon);
                currentOverheadIcon = newOverheadIcon;
                if (!Rs2Inventory.isOpen()) {
                    Rs2Inventory.open();
                    sleepUntil(Rs2Inventory::isOpen, 1000);
                }
            }
            // Enforce a valid counter-style every tick: re-picks when the overhead changed or the current
            // style is no longer allowed (e.g. that style was just toggled off in config), and re-applies
            // the gear if a previous swap couldn't complete.
            switchGear(config, currentOverheadIcon);
        }

        if (config.enableOffensivePrayer()) {
            activateOffensivePrayer(config);
        }
    }


    private void activateOffensivePrayer(CustomTormentedDemonConfig config) {
        // Base the offensive prayer on the style we actually decided to attack with (intendedGearSetup),
        // NOT on inspecting worn equipment: range/magic/melee setups usually share every slot but the
        // weapon, so doesEquipmentMatch() can report the wrong style (e.g. picking a mage prayer while
        // we're attacking with range).
        if (intendedGearSetup == null) {
            return;
        }
        Rs2PrayerEnum newOffensivePrayer = null;
        if (config.useMagicStyle() && intendedGearSetup == magicSetup) {
            newOffensivePrayer = Rs2Prayer.getBestMagePrayer();
        } else if (config.useMeleeStyle() && intendedGearSetup == meleeSetup) {
            newOffensivePrayer = Rs2Prayer.getBestMeleePrayer();
        } else if (config.useRangeStyle() && intendedGearSetup == rangeSetup) {
            newOffensivePrayer = Rs2Prayer.getBestRangePrayer();
        }
        if (newOffensivePrayer != null && newOffensivePrayer != currentOffensivePrayer) {
            logOnceToChat("Changing offensive prayer to " + newOffensivePrayer);
            switchOffensivePrayer(newOffensivePrayer);
            sleep(100);
        }
    }

    private void switchOffensivePrayer(Rs2PrayerEnum newOffensivePrayer) {
        if (currentOffensivePrayer != null) {
            Rs2Prayer.toggle(currentOffensivePrayer, false);
        }
        Rs2Prayer.toggle(newOffensivePrayer, true);
        currentOffensivePrayer = newOffensivePrayer;
    }

    private Rs2NpcModel findNewTarget(CustomTormentedDemonConfig config) {
        return Microbot.getRs2NpcCache().query()
                .withName("Tormented Demon")
                .where(npc -> !npc.isDead())
                .where(npc -> npc.getInteracting() == null || npc.getInteracting() == Microbot.getClient().getLocalPlayer())
                .where(npc -> {
                    HeadIcon demonHeadIcon = npc.getHeadIcon();
                    if (demonHeadIcon != null) {
                        switchGear(config, demonHeadIcon);
                        return true;
                    }
                    logOnceToChat("Null HeadIcon for NPC " + npc.getName());
                    return false;
                })
                .firstOnClientThread();
    }

    /**
     * Ensures we're geared for a style the demon is NOT protecting against. Keeps the current
     * {@link #intendedGearSetup} while it's still a valid counter for this overhead and enabled styles
     * (so a random dual-style pick isn't re-rolled every tick); otherwise re-picks. Then equips it if the
     * worn gear doesn't already match. Safe to call every tick — it only wears on a mismatch.
     */
    private void switchGear(CustomTormentedDemonConfig config, HeadIcon combatNpcHeadIcon) {
        if (!config.autoGearSwitch()) {
            return;
        }

        if (!isAcceptableCounter(config, combatNpcHeadIcon, intendedGearSetup)) {
            Rs2InventorySetup target = selectGearSetup(config, combatNpcHeadIcon);
            if (target == null) {
                // Nothing valid configured/enabled for this overhead; keep whatever we have.
                return;
            }
            intendedGearSetup = target;
        }
        equipGearSetup(intendedGearSetup);
    }

    /**
     * True if {@code setup} is a currently-enabled counter-style for this overhead — i.e. a style the demon
     * is not protecting against. Used to decide whether the sticky {@link #intendedGearSetup} is still valid
     * or must be re-picked (e.g. after the demon flips prayer, or the player toggles a style off).
     */
    private boolean isAcceptableCounter(CustomTormentedDemonConfig config, HeadIcon overhead, Rs2InventorySetup setup) {
        if (setup == null) {
            return false;
        }
        boolean useRange = config.useRangeStyle();
        boolean useMagic = config.useMagicStyle();
        boolean useMelee = config.useMeleeStyle();
        switch (overhead) {
            case RANGED:
                return (useMelee && setup == meleeSetup) || (useMagic && setup == magicSetup);
            case MAGIC:
                return (useRange && setup == rangeSetup) || (useMelee && setup == meleeSetup);
            case MELEE:
                return (useRange && setup == rangeSetup) || (useMagic && setup == magicSetup);
            default:
                return false;
        }
    }

    private void equipGearSetup(Rs2InventorySetup target) {
        if (target.doesEquipmentMatch()) {
            return;
        }
        logOnceToChat("Changing gear to " + gearLabel(target) + " setup");
        if (!target.wearEquipment()) {
            logOnceToChat("Could not fully equip " + gearLabel(target)
                    + " setup (missing items / inventory full) — will retry");
        }
    }

    /** Chooses the setup for a style the demon is NOT protecting against, honoring the enabled-style toggles. */
    private Rs2InventorySetup selectGearSetup(CustomTormentedDemonConfig config, HeadIcon combatNpcHeadIcon) {
        boolean useRange = config.useRangeStyle();
        boolean useMagic = config.useMagicStyle();
        boolean useMelee = config.useMeleeStyle();

        switch (combatNpcHeadIcon) {
            case RANGED:
                if (useMelee && useMagic) {
                    return Math.random() < 0.5 ? meleeSetup : magicSetup;
                } else if (useMelee) {
                    return meleeSetup;
                } else if (useMagic) {
                    return magicSetup;
                }
                break;

            case MAGIC:
                if (useRange && useMelee) {
                    return Math.random() < 0.5 ? rangeSetup : meleeSetup;
                } else if (useRange) {
                    return rangeSetup;
                } else if (useMelee) {
                    return meleeSetup;
                }
                break;

            case MELEE:
                if (useRange && useMagic) {
                    return Math.random() < 0.5 ? rangeSetup : magicSetup;
                } else if (useRange) {
                    return rangeSetup;
                } else if (useMagic) {
                    return magicSetup;
                }
                break;
        }
        return null;
    }

    /** Maps the demon's current attack animation to the style we should be praying against. */
    private String demonAttackLabel(int animationId) {
        switch (animationId) {
            case 11388: return "MAGIC";   // FIREY_BALLS
            case 11389: return "RANGED";  // SPARE_RIBS
            case 11392: return "MELEE";   // MELEE
            case 11387: return "SPECIAL"; // EXPLOSION_FIRE (AoE, dodged)
            default: return "-";          // idle / non-attack animation
        }
    }

    private String gearLabel(Rs2InventorySetup setup) {
        if (setup == null) {
            return "none";
        }
        if (setup == magicSetup) {
            return "magic";
        }
        if (setup == meleeSetup) {
            return "melee";
        }
        if (setup == rangeSetup) {
            return "range";
        }
        return "unknown";
    }

    private boolean shouldRetreat(CustomTormentedDemonConfig config) {
        int currentHealth = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        boolean noFood = Rs2Inventory.getInventoryFood().isEmpty();
        boolean noPrayerPotions = Rs2Inventory.items()
                .noneMatch(item -> item != null && item.getName() != null && item.getName().toLowerCase().contains("prayer potion"));

        return (noFood || currentHealth <= config.healthThreshold()) || (noPrayerPotions && currentPrayer < 10);
    }

    public void disableAllPrayers() {
        Rs2Prayer.disableAllPrayers();
        currentDefensivePrayer = null;
        currentOffensivePrayer = null;
    }

    /**
     * Pre-prays Protect from Melee at the start of a fight (before the demon has attacked, so its style is
     * unknown) — a 1/3 chance to block the opening hit. Only sets it when no protection prayer is active, so
     * it never overrides a correct prayer the plugin already flicked.
     */
    private void prayDefaultDefensivePrayer(CustomTormentedDemonConfig config) {
        if (!config.enableDefensivePrayer()) {
            return;
        }
        if (Rs2Prayer.getActiveProtectionPrayer() == null) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
        }
    }

    private void attemptLooting(CustomTormentedDemonConfig config) {
        Microbot.log("Checking loot..");
        // Consume smouldering drops in place (Crush / Eat-from) instead of taking them. Done first because
        // consuming removes the ground item, which also stops the "loot everything" untradeable pass below
        // from picking them up.
        consumeSmoulderingDrops();

        // antiLureProtection == "only items owned by me" (OWNERSHIP_SELF). Off = loot regardless of owner.
        boolean mine = config.lootMyLootOnly();

        // Always honor the explicit name list (additive to "loot everything").
        List<String> lootItems = parseLootItems(config.lootItems());
        if (!lootItems.isEmpty()) {
            Rs2GroundItem.lootItemsBasedOnNames(
                    new LootingParameters(10, 1, 1, 0, false, mine, lootItems.toArray(new String[0])));
        }

        if (config.lootEverything()) {
            // Everything tradeable worth > 0 gp in one pass...
            Rs2GroundItem.lootItemBasedOnValue(
                    new LootingParameters(0, Integer.MAX_VALUE, 10, 1, 0, false, mine));
            // ...plus untradeables (clues, ashes, etc.) and coins, which carry no GE value.
            Rs2GroundItem.lootUntradables(
                    new LootingParameters(0, Integer.MAX_VALUE, 10, 1, 0, false, mine));
            Rs2GroundItem.lootCoins(
                    new LootingParameters(0, Integer.MAX_VALUE, 10, 1, 0, false, mine));
        }

        if (config.scatterAshes()) {
            lootAndScatterInfernalAshes(config);
        }
    }

    /**
     * Consume the demon's smouldering drops on the ground for their reward rather than taking them, using
     * each item's own action (pile of flesh = "Eat-from", gland/heart = "Crush"). Loops per item until it's
     * gone (the action removes the ground item), bounded so a stubborn/blocked interaction can't hang looting.
     */
    private void consumeSmoulderingDrops() {
        for (String[] drop : SMOULDERING_DROPS) {
            final String name = drop[0];
            final String action = drop[1];
            int attempts = 0;
            while (Rs2GroundItem.exists(name, LOOT_RANGE) && attempts++ < 5) {
                if (!Rs2GroundItem.interact(name, action, LOOT_RANGE)) {
                    break;
                }
                logOnceToChat(action + " " + name);
                sleepUntil(() -> !Rs2GroundItem.exists(name, LOOT_RANGE), 3000);
            }
        }
    }

    private void lootAndScatterInfernalAshes(CustomTormentedDemonConfig config) {
        String ashesName = "Infernal ashes";

        // Ashes may already be in the inventory if "Loot Everything" grabbed them as an untradeable;
        // otherwise pick them up here. Either way, scatter whatever ashes we're now holding.
        if (!Rs2Inventory.isFull()) {
            Rs2GroundItem.lootItemsBasedOnNames(
                    new LootingParameters(10, 1, 1, 0, false, config.lootMyLootOnly(), ashesName));
            sleepUntil(() -> Rs2Inventory.contains(ashesName), 2000);
        }

        if (Rs2Inventory.contains(ashesName)) {
            Rs2Inventory.interact(ashesName, "Scatter");
            sleep(600); // Wait briefly for scattering action
        }
    }

    private List<String> parseLootItems(String lootFilter) {
        return Arrays.asList(lootFilter.toLowerCase().split(","));
    }

    private void evaluateAndConsumePotions(CustomTormentedDemonConfig config) {
        int threshold = config.boostedStatsThreshold();

        if (!isCombatPotionActive(config.combatPotionType(), threshold)) {
            consumeCombatPotion(config.combatPotionType(), threshold);
        }

        if (!isRangingPotionActive(config.rangingPotionType(), threshold)) {
            consumeRangingPotion(config.rangingPotionType());
        }
    }

    private boolean isCombatPotionActive(CustomTormentedDemonConfig.CombatPotionType combatPotionType, int threshold) {
        switch (combatPotionType) {
            case SUPER_COMBAT:
            case SUPER_ATTACK_AND_STRENGTH:
                return Rs2Player.hasAttackActive(threshold) && Rs2Player.hasStrengthActive(threshold);
            case DIVINE_SUPER_COMBAT:
                return Rs2Player.hasDivineCombatActive();
            default:
                return true;
        }
    }

    private boolean isRangingPotionActive(CustomTormentedDemonConfig.RangingPotionType rangingPotionType, int threshold) {
        switch (rangingPotionType) {
            case RANGING:
                return Rs2Player.hasRangingPotionActive(threshold);
            case DIVINE_RANGING:
                return Rs2Player.hasDivineRangedActive();
            case BASTION:
                return Rs2Player.hasDivineBastionActive();
            default:
                return true;
        }
    }

    private void consumeCombatPotion(CustomTormentedDemonConfig.CombatPotionType combatPotionType, int threshold) {
        switch (combatPotionType) {
            case SUPER_COMBAT:
                consumePotion("super combat");
                break;
            case DIVINE_SUPER_COMBAT:
                consumePotion("divine super combat");
                break;
            case SUPER_ATTACK_AND_STRENGTH:
                // Drink only the boost that has decayed so we don't waste doses of the other.
                if (!Rs2Player.hasStrengthActive(threshold)) {
                    consumePotion("super strength");
                }
                if (!Rs2Player.hasAttackActive(threshold)) {
                    consumePotion("super attack");
                }
                break;
            default:
                break;
        }
    }

    private void consumeRangingPotion(CustomTormentedDemonConfig.RangingPotionType rangingPotionType) {
        String potion = null;
        switch (rangingPotionType) {
            case RANGING:
                potion = "ranging potion";
                break;
            case DIVINE_RANGING:
                potion = "divine ranging potion";
                break;
            case BASTION:
                potion = "bastion potion";
                break;
            default:
                return;
        }
        consumePotion(potion);
    }

    private void consumePotion(String keyword) {
        Rs2Inventory.getPotions().stream()
                .filter(potion -> potion.getName().toLowerCase().contains(keyword))
                .findFirst()
                .ifPresent(potion -> {
                    Rs2Inventory.interact(potion, "Drink");
                    logOnceToChat("Drinking potion: " + potion.getName());
                });
    }

    void logOnceToChat(String message) {
        if (!message.equals(lastChatMessage)) {
            Microbot.log(message);
            lastChatMessage = message;
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        isRunning = false;
        disableAllPrayers();
        BOT_STATUS = State.BANKING;
        travelStep = TravelStep.CLIMB_ROCKS;
        bankingStep = BankingStep.TELE_HOUSE_TO_FEROX;
        currentTarget = null;
        killCount = 0;
        lootAttempted = false;  // Reset here
        currentDefensivePrayer = null;
        currentOffensivePrayer = null;
        currentOverheadIcon = null;
        setupsResolved = false;
        bankingSetup = null;
        rangeSetup = null;
        magicSetup = null;
        meleeSetup = null;
        intendedGearSetup = null;
        if (mainScheduledFuture != null && !mainScheduledFuture.isCancelled()) {
            mainScheduledFuture.cancel(true);
        }
        logOnceToChat("Shutting down Tormented Demon script");
    }
}

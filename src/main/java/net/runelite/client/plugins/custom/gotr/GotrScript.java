package net.runelite.client.plugins.custom.gotr;

import com.google.common.collect.ImmutableList;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.custom.gotr.data.CellType;
import net.runelite.client.plugins.custom.gotr.data.GuardianPortalInfo;
import net.runelite.client.plugins.custom.gotr.data.Mode;
import net.runelite.client.plugins.custom.gotr.data.RuneType;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.Microbot.log;


public class GotrScript extends Script {

    public static long totalTime = 0;
    public static boolean shouldMineGuardianRemains = true;
    public static final String rewardPointRegex = "Total elemental energy:[^>]+>([\\d,]+).*Total catalytic energy:[^>]+>([\\d,]+).";
    public static final Pattern rewardPointPattern = Pattern.compile(rewardPointRegex);

    public static boolean isInMiniGame = false;
    public static boolean isFirstPortal = true;
    public static final int portalId = ObjectID.PORTAL_43729;
    public static final int greatGuardianId = 11403;
    public static final Map<Integer, GuardianPortalInfo> guardianPortalInfo = new HashMap<>();
    public static Optional<Instant> nextGameStart = Optional.empty();
    public static Optional<Instant> timeSincePortal = Optional.empty();
    public static final Set<GameObject> guardians = new HashSet<>();
    public static final List<GameObject> activeGuardianPortals = new ArrayList<>();
    public static NPC greatGuardian;
    public static int elementalRewardPoints;
    public static int catalyticRewardPoints;
    public static GotrState state;
    static GotrConfig config;
    String GUARDIAN_FRAGMENTS = "guardian fragments";
    String GUARDIAN_ESSENCE = "guardian essence";

    boolean initCheck = false;
    boolean optimizedEssenceLoop = false;

    static boolean useNpcContact = true;
    // Sprite id of the NPC Contact spell icon in the Lunar spellbook. Used to locate the spell by
    // sprite/action instead of by name, which can be changed via the RuneLite Spellbook plugin.
    private static final int NPC_CONTACT_SPRITE_ID = 618;
    // Tracks the antiban activity we last handed to Rs2Antiban.setActivity so we only switch on a
    // real phase change (setActivity resets the play-style evolution, so calling it every tick
    // would thrash it).
    private static Activity currentActivity;
    // Throttle for the mining-target debug log so it prints at most once every couple of seconds.
    private static long lastTargetLog = 0;
    private final List<Integer> runeIds = ImmutableList.of(
            ItemID.NATURE_RUNE,
            ItemID.LAW_RUNE,
            ItemID.BODY_RUNE,
            ItemID.DUST_RUNE,
            ItemID.LAVA_RUNE,
            ItemID.STEAM_RUNE,
            ItemID.SMOKE_RUNE,
            ItemID.SOUL_RUNE,
            ItemID.WATER_RUNE,
            ItemID.AIR_RUNE,
            ItemID.EARTH_RUNE,
            ItemID.FIRE_RUNE,
            ItemID.MIND_RUNE,
            ItemID.CHAOS_RUNE,
            ItemID.DEATH_RUNE,
            ItemID.BLOOD_RUNE,
            ItemID.COSMIC_RUNE,
            ItemID.ASTRAL_RUNE,
            ItemID.MIST_RUNE,
            ItemID.MUD_RUNE,
            ItemID.WRATH_RUNE);

    private void initializeGuardianPortalInfo() {
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_AIR, new GuardianPortalInfo("AIR", 1, ItemID.AIR_RUNE, 26887, 4353, RuneType.ELEMENTAL, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_MIND, new GuardianPortalInfo("MIND", 2, ItemID.MIND_RUNE, 26891, 4354, RuneType.CATALYTIC, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_WATER, new GuardianPortalInfo("WATER", 5, ItemID.WATER_RUNE, 26888, 4355, RuneType.ELEMENTAL, CellType.MEDIUM, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_EARTH, new GuardianPortalInfo("EARTH", 9, ItemID.EARTH_RUNE, 26889, 4356, RuneType.ELEMENTAL, CellType.STRONG, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_FIRE, new GuardianPortalInfo("FIRE", 14, ItemID.FIRE_RUNE, 26890, 4357, RuneType.ELEMENTAL, CellType.OVERCHARGED, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_BODY, new GuardianPortalInfo("BODY", 20, ItemID.BODY_RUNE, 26895, 4358, RuneType.CATALYTIC, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_COSMIC, new GuardianPortalInfo("COSMIC", 27, ItemID.COSMIC_RUNE, 26896, 4359, RuneType.CATALYTIC, CellType.MEDIUM, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.LOST_CITY.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_CHAOS, new GuardianPortalInfo("CHAOS", 35, ItemID.CHAOS_RUNE, 26892, 4360, RuneType.CATALYTIC, CellType.MEDIUM, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_NATURE, new GuardianPortalInfo("NATURE", 44, ItemID.NATURE_RUNE, 26897, 4361, RuneType.CATALYTIC, CellType.STRONG, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_LAW, new GuardianPortalInfo("LAW", 54, ItemID.LAW_RUNE, 26898, 4362, RuneType.CATALYTIC, CellType.STRONG, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.TROLL_STRONGHOLD.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_DEATH, new GuardianPortalInfo("DEATH", 65, ItemID.DEATH_RUNE, 26893, 4363, RuneType.CATALYTIC, CellType.OVERCHARGED, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.MOURNINGS_END_PART_II.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_BLOOD, new GuardianPortalInfo("BLOOD", 77, ItemID.BLOOD_RUNE, 26894, 4364, RuneType.CATALYTIC, CellType.OVERCHARGED, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.SINS_OF_THE_FATHER.getState(Microbot.getClient())).orElse(null)));
    }

    public boolean run(GotrConfig config) {
        this.config = config;
        // Static (and singleton-instance) state persists for the whole JVM session and leaks
        // across plugin disable/re-enable (see docs/PLUGIN_DEBUGGING_NOTES.md §5). Reset it here
        // so a restart behaves like a first start instead of inheriting a stale state machine.
        shouldMineGuardianRemains = true;
        isInMiniGame = false;
        isFirstPortal = true;
        state = null;
        nextGameStart = Optional.empty();
        timeSincePortal = Optional.empty();
        elementalRewardPoints = 0;
        catalyticRewardPoints = 0;
        useNpcContact = true;
        initCheck = false;
        optimizedEssenceLoop = false;
        guardians.clear();
        activeGuardianPortals.clear();
        greatGuardian = null;

        // Humanization: hand action timing and mouse behaviour to the antiban engine so we no
        // longer fire on a rigid metronome. usePlayStyle + behavioralVariability + nonLinearIntervals
        // spread actions out non-uniformly and pause them during cooldowns; naturalMouse/mistakes/
        // random mouse movement cover the pointer. Task-boundary actionCooldown()/takeMicroBreakByChance()
        // calls (after mining, crafting, depositing) drive it. Activity is switched per phase below.
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.setActivity(Activity.GENERAL_RUNECRAFT);
        currentActivity = Activity.GENERAL_RUNECRAFT;
        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.simulateAttentionSpan = true;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.nonLinearIntervals = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.04;
        Rs2AntibanSettings.moveMouseOffScreen = true;
        Rs2AntibanSettings.takeMicroBreaks = true;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                long startTime = System.currentTimeMillis();

                if (!initCheck) {
                    initializeGuardianPortalInfo();
                    if (!config.useLunarSpellbook() || !Rs2Magic.isSpellbook(Rs2Spellbook.LUNAR)) {
                        Microbot.log("Lunar NPC Contact unavailable (disabled in config or not on Lunar spellbook)...using Cordelia pouch repair");
                        useNpcContact = false;
                    }
                    initCheck = true;
                }

                if (!Rs2Inventory.hasItem("pickaxe") && !Rs2Equipment.isWearing("pickaxe")) {
                    log("You need to have a pickaxe before you can participate in this minigame.");
                    return;
                }

                // A human "pause" is in progress (behavioural-variability timeout from a prior
                // actionCooldown) — hold off on issuing new actions this tick.
                if (Rs2AntibanSettings.actionCooldownActive) return;

                checkPouches(Rs2Inventory.anyPouchUnknown(), 1500, 300);

                //IS INSIDE THE MINIGAME
                int timeToStart = 0;
                if (nextGameStart.isPresent()) {
                    timeToStart = ((int) ChronoUnit.SECONDS.between(Instant.now(), nextGameStart.get()));
                }

                if (Rs2Inventory.hasItem("portal talisman") && !Rs2Inventory.hasItem(GUARDIAN_ESSENCE) && !Rs2Inventory.anyPouchFull()) {
                    Rs2Inventory.drop("portal talisman");
                    log("Dropping portal talisman...");
                }
                //Repair colossal pouch asap to avoid disintegrate completely
                if (Rs2Inventory.hasItem("colossal pouch") && Rs2Inventory.hasDegradedPouch()) {
                    if (!repairPouches()) {
                        return;
                    }
                }

                GotrScript.isInMiniGame = !isOutsideBarrier() && isInMainRegion();


                if (isInMiniGame) {

                    if (waitingForGameToStart(timeToStart)) return;


                    if (!Rs2Inventory.hasItem("Uncharged cell") && !isInLargeMine() && !isInHugeMine()) {
                        takeUnchargedCells();
                        return;
                    }

                    if (usePortal()) return;
                    //mine huge guardian remains
                    if (mineHugeGuardianRemain()) return;

                    if (powerUpGreatGuardian()) return;
                    if (repairCells()) return;


                    if (!shouldMineGuardianRemains) {
                        //Create fragments into whatever
                        if (isOutOfFragments()) return;

                        //deposit runes
                        if (depositRunesIntoPool()) return;

                        if (fillPouches()) {
                            craftGuardianEssences();
                            return;
                        }
                        if (!Rs2Inventory.isFull() && !optimizedEssenceLoop) {
                            if (leaveLargeMine()) return;

                            if (state == GotrState.CRAFT_GUARDIAN_ESSENCE && (Rs2Player.isAnimating() || Rs2Player.isMoving())) return;

                            if (craftGuardianEssences()) return;

                        } else if (Rs2Inventory.hasItem(GUARDIAN_ESSENCE)) {
                            if (leaveLargeMine()) return;
                            if (enterAltar()) return;
                        }
                    } else {
                        updateMiningTarget();
                        mineGuardianRemains();
                    }
                    return;
                }


                //IS NOT IN THE MINIGAME

                if (craftRunes()) return;

                if (enterMinigame()) return;

                if (waitForMinigameToStart()) return;


                long endTime = System.currentTimeMillis();
                totalTime = endTime - startTime;

            } catch (Exception ex) {
                Microbot.log("Something went wrong in the GOTR Script: " + ex.getMessage() + ". If the script is stuck, please contact us on discord with this log.");
                ex.printStackTrace();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private boolean waitingForGameToStart(int timeToStart) {
        if (isInHugeMine()) return false;

        if (getStartTimer() > Rs2Random.randomGaussian(35, Rs2Random.between(1, 5)) || getStartTimer() == -1 || timeToStart > 10) {

            // A round just ended (or hasn't started yet) and this path runs instead of the
            // craft branch — bank any crafted runes into the pool before prepping for the next
            // game, so we never carry runes over.
            if (depositRunesIntoPool()) return true;

            // Only take cells if we don't already have them
            if (!Rs2Inventory.hasItem("Uncharged cell")) {
                // If in large mine and need cells, leave first
                if (isInLargeMine()) {
                    if (leaveLargeMine()) return true;
                }
                takeUnchargedCells();
                // Return to large mine if we were there before
                if (!isInLargeMine() && shouldMineGuardianRemains) {
                    if (Rs2Walker.walkTo(new WorldPoint(3632, 9503, 0), 20)) {
                        interactObject(ObjectID.RUBBLE_43724);
                        return true;
                    }
                }
            }

            repairPouches();

            // Same cap as the in-game mining branch — without this the pre-game wait mines with no
            // limit and stockpiles hundreds of fragments before the rift even opens.
            updateMiningTarget();
            if (!shouldMineGuardianRemains) return true;

            mineGuardianRemains();
            return true;
        }
        return false;
    }

    /**
     * Decide whether we've mined enough guardian fragments and, if so, switch to crafting.
     *
     * <p>Target: below full guardian power we bank up to {@code maxFragmentAmount} (the "Max. amount
     * fragments" config option), but never fewer than a full run — every empty inventory slot plus
     * all remaining pouch capacity — or we'd thrash between the remains and the workbench. Once power
     * is high we only top up a single run to avoid wasting end-game time over-mining.
     *
     * <p>We compare {@link Rs2Inventory#itemQuantity(String)} directly (rather than
     * {@code hasItemAmount}) so the number logged is exactly the number driving the decision, and log
     * it on a throttle so you can see how the target is derived while mining.
     */
    private void updateMiningTarget() {
        int fragments = Rs2Inventory.itemQuantity(GUARDIAN_FRAGMENTS);
        int emptySlots = Rs2Inventory.emptySlotCount();
        int pouchRemaining = Rs2Inventory.getRemainingCapacityInPouches();
        int fullRun = emptySlots + pouchRemaining;
        int power = getGuardiansPower();
        int target = power > 70 ? fullRun : Math.max(config.maxFragmentAmount(), fullRun);

        if (System.currentTimeMillis() - lastTargetLog > 2000) {
            lastTargetLog = System.currentTimeMillis();
            log(String.format("[GOTR mining] fragments=%d / target=%d  (maxCfg=%d, fullRun=%d [empty=%d + pouchRemaining=%d], power=%d%%)",
                    fragments, target, config.maxFragmentAmount(), fullRun, emptySlots, pouchRemaining, power));
        }

        if (fragments >= target) {
            shouldMineGuardianRemains = false;
        }
    }

    private boolean repairCells() {
        Rs2ItemModel cell = Rs2Inventory.get(CellType.PoweredCellList().stream().mapToInt(i -> i).toArray());
        if (cell == null || !isInMainRegion() || !isInMiniGame() || shouldMineGuardianRemains || isInLargeMine() || isInHugeMine()) {
            return false;
        }
        int cellTier = CellType.GetCellTier(cell.getId());
        // Identify the shield pylons by object id (CellType.GetShieldTier knows them all and
        // returns -1 for anything else). The previous filter matched on a name containing
        // "cell_tile", but the real pylon objects aren't named that, so the query always came
        // back empty — yet the method still returned true unconditionally below. That made the
        // main loop short-circuit at `if (repairCells()) return;` on every tick whenever a
        // powered cell was held, leaving the bot standing idle until the next game start. Match
        // by id, and only claim the tick when we actually place/use a cell.
        List<Rs2TileObjectModel> shieldCells = Microbot.getRs2TileObjectCache().query()
            .where(o -> CellType.GetShieldTier(o.getId()) >= 0)
            .toListOnClientThread();

        if (Rs2Inventory.hasItemAmount(GUARDIAN_ESSENCE, 10)) {
            for (Rs2TileObjectModel shieldCell : shieldCells) {
                if (CellType.GetShieldTier(shieldCell.getId()) < cellTier) {
                    Microbot.log("Upgrading power cell at " + shieldCell.getWorldLocation());
                    shieldCell.click("Place-cell");
                    sleepUntil(() -> !Rs2Player.isMoving());
                    return true;
                }
            }
        }
        Rs2TileObjectModel cellToUse = shieldCells.stream()
            .filter(o -> CellType.GetShieldTier(o.getId()) > 0)
            .findFirst().orElse(null);
        if (cellToUse != null) {
            cellToUse.click();
            log("Using cell with id " + cellToUse.getId());
            sleep(Rs2Random.randomGaussian(1000, 300));
            sleepUntil(() -> !Rs2Player.isMoving());
            return true;
        }
        // Nothing to place — don't pretend we handled the tick, or the loop will never craft.
        return false;
    }

    private boolean powerUpGreatGuardian() {
        if (!Rs2Inventory.hasItem("guardian stone") || shouldMineGuardianRemains || isInLargeMine() || isInHugeMine()) {
            return false;
        }

        Rs2NpcModel guardian = Microbot.getRs2NpcCache().query().withName("The great guardian").nearest();
        if (guardian == null) {
            return false;
        }

        if (!Rs2Npc.canWalkTo(guardian.getNpc(), 10)) {
            return true;
        }

        state = GotrState.POWERING_UP;
        if (!guardian.click("power-up")) {
            return false;
        }

        log("Powering up the great guardian...");
        int stonesBefore = Rs2Inventory.count("guardian stone");
        Global.sleepUntil(Rs2Player::isAnimating, 3000);
        Global.sleepUntil(() -> !Rs2Player.isAnimating(), 5000);
        sleep(Rs2Random.randomGaussian(Rs2Random.between(1000, 2000), Rs2Random.between(100, 300)));

        // Only hold the tick if we actually consumed a stone; otherwise let the loop continue.
        return Rs2Inventory.count("guardian stone") < stonesBefore;
    }


    private void takeUnchargedCells() {

        if (!Rs2Inventory.hasItem("Uncharged cell")) {
            // Drop one guardian essence if inventory is full
            if (Rs2Inventory.isFull()) {
                if (Rs2Inventory.drop(ItemID.GUARDIAN_ESSENCE)) {
                    Microbot.log("Dropped one Guardian essence to make space for Uncharged cell");
                }
            }

            interactObject(ObjectID.UNCHARGED_CELLS_43732, "Take-10");
            log("Taking uncharged cells...");
            Rs2Player.waitForAnimation();
        }
    }

    private boolean usePortal() {
        if (!isInHugeMine() && Microbot.getClient().hasHintArrow() && Rs2Inventory.count() < config.maxAmountEssence()) {
            if (leaveLargeMine()) return true;
            // Human reaction latency: don't sprint to a freshly-spawned portal on the exact tick it
            // appears. Occasionally we "don't notice" it for a beat, otherwise we react after a
            // realistic (log-normal) delay before pathing to it.
            if (Rs2Random.dicePercentage(15)) return false;
            sleep(Rs2Random.reactionTime());
            Rs2Walker.walkFastCanvas(Microbot.getClient().getHintArrowPoint());
            sleepUntil(Rs2Player::isMoving);
            Microbot.getRs2TileObjectCache().query().within(Microbot.getClient().getHintArrowPoint(), 0).interact();
            log("Found a portal spawn...interacting with it...");
            Rs2Player.waitForWalking();
            sleepUntil(() -> isInHugeMine());
            sleepUntil(() -> getGuardiansPower() > 0);
            return true;
        }
        return false;
    }

    private boolean depositRunesIntoPool() {
        if (!config.shouldDepositRunes()
                || !Rs2Inventory.hasItem(runeIds.stream().mapToInt(i -> i).toArray())
                || isInLargeMine() || isInHugeMine()) {
            return false;
        }
        if (Rs2Player.isMoving()) return true;
        // Walk-first interaction, but only claim the tick when the pool actually exists — otherwise
        // return false so we never lock the loop standing around holding runes. Dropped the old
        // !isFull / !optimizedEssenceLoop guards: they skipped exactly the end-of-round case, where
        // a full inventory of crafted runes would otherwise never be deposited and carried into the
        // next round.
        Rs2TileObjectModel pool = Microbot.getRs2TileObjectCache().query().withId(ObjectID.DEPOSIT_POOL).nearest();
        if (pool == null) return false;
        if (interactObject(pool, null)) {
            log("Deposit runes into pool...");
            sleep(600, 2400);
            Rs2Antiban.actionCooldown();
            Rs2Antiban.takeMicroBreakByChance();
        }
        return true;
    }

    private boolean enterAltar() {
        List<GameObject> altars = getAvailableAltars();
        GameObject best = altars.stream().findFirst().orElse(null);
        // Occasionally take the 2nd-best available altar instead of always the mathematically
        // optimal one — perfectly optimal selection every game is a bot tell. Only do this when the
        // 2nd altar is the same rune type as the first, so the shuffle never overrides the chosen
        // Mode (e.g. picking an elemental altar while in CATALYTIC mode).
        if (best != null && altars.size() > 1 && Rs2Random.dicePercentage(20)) {
            GameObject second = altars.get(1);
            if (guardianPortalInfo.get(second.getId()).getRuneType()
                    == guardianPortalInfo.get(best.getId()).getRuneType()) {
                best = second;
            }
        }
        final GameObject availableAltar = best;
        if (availableAltar != null && !Rs2Player.isMoving()) {
            log("Entering with altar " + availableAltar.getId());
            Rs2GameObject.interact(availableAltar);
            state = GotrState.ENTER_ALTAR;
            Global.sleepUntil(() -> !isInMainRegion() || !Objects.equals(getAvailableAltars().stream().findFirst().orElse(null), availableAltar), 5000);
            sleep(Rs2Random.randomGaussian(1000, 300));

            return true;
        }
        return false;
    }

    private boolean craftGuardianEssences() {
        useActivity(Activity.GENERAL_RUNECRAFT);
        if (interactObject(ObjectID.WORKBENCH_43754)) {
            state = GotrState.CRAFT_GUARDIAN_ESSENCE;
            sleep(Rs2Random.randomGaussian(Rs2Random.between(600, 900), Rs2Random.between(150, 300)));
            log("Crafting guardian essences...");
            Rs2Antiban.actionCooldown();
            Rs2Antiban.takeMicroBreakByChance();
            return true;
        }
       return false;
    }

    private boolean leaveLargeMine() {
        if (isInLargeMine()) {
            interactObject(ObjectID.RUBBLE_43726);
            Rs2Player.waitForAnimation();
            log("Leaving large mine...");
            state = GotrState.LEAVING_LARGE_MINE;
            return true;
        }
        return false;
    }

    private boolean fillPouches() {
        if (Rs2Inventory.isFull() && Rs2Inventory.anyPouchEmpty() && getGuardiansPower() < 90) {
            Rs2Inventory.fillPouches();
            sleep(Rs2Random.randomGaussian(600, 300));
            return true;
        }
        return false;
    }

    private boolean isOutOfFragments() {
        if ((!Rs2Inventory.hasItem(GUARDIAN_FRAGMENTS) && !Rs2Inventory.isFull()) || (getTimeSincePortal() > 85 && !Rs2Inventory.hasItem(GUARDIAN_ESSENCE))) {
            shouldMineGuardianRemains = true;
            if(!Rs2Inventory.hasItem(GUARDIAN_FRAGMENTS))
                log("Memorize that we no longer have guardian fragments...");

            return true;
        }
        shouldMineGuardianRemains = false;
        return false;
    }

    private boolean craftRunes() {
        if (!isInMainRegion() && isInMiniGame()) {
            Rs2TileObjectModel rcAltar = findRcAltar();
            if (rcAltar != null) {
                if (Rs2Player.isMoving()) return true;
                if (Rs2Inventory.anyPouchFull() && !Rs2Inventory.isFull()) {
                    Rs2Inventory.emptyPouches();
                    Rs2Inventory.waitForInventoryChanges(5000);
                    sleep(Rs2Random.randomGaussian(350, 150));
                }
                if (Rs2Inventory.hasItem(GUARDIAN_ESSENCE)) {
                    useActivity(Activity.GENERAL_RUNECRAFT);
                    state = GotrState.CRAFTING_RUNES;
                    optimizedEssenceLoop = false;
                    interactObject(rcAltar, null);
                    log("Crafting runes on altar " + rcAltar.getId());
                    sleep(Rs2Random.randomGaussian(Rs2Random.between(1000, 1500), 300));
                    Rs2Antiban.actionCooldown();
                    Rs2Antiban.takeMicroBreakByChance();
                } else if (!Rs2Player.isMoving()) {
                    state = GotrState.LEAVING_ALTAR;
                    Rs2TileObjectModel rcPortal = findPortalToLeaveAltar();
                    if (interactObject(rcPortal, null)) {
                        log("Leaving the altar...");
                        sleepUntilTrue(GotrScript::isInMainRegion,100,10000);
                        sleep(Rs2Random.randomGaussian(750, 150));
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static boolean waitForMinigameToStart() {
        if (!isInMainRegion()) {
            Rs2TileObjectModel rcPortal = findPortalToLeaveAltar();
            if (rcPortal != null && interactObject(rcPortal, null)) {
                state = GotrState.LEAVING_ALTAR;
                return true;
            }
        }
        resetPlugin();
        if (state != GotrState.WAITING) {
            state = GotrState.WAITING;
            log("Make sure to start the script near the minigame barrier.");
            interactObject(ObjectID.BARRIER_43849, "Peek");
        }
        return state == GotrState.WAITING;
    }

    private static boolean enterMinigame() {
        if (interactObject(ObjectID.BARRIER_43700, "quick-pass")) {
            Rs2Player.waitForWalking();
            state = GotrState.ENTER_GAME;
            GotrScript.shouldMineGuardianRemains = true;
            log("Entering game...");
            return true;
        }
        return false;
    }

    private void checkPouches(boolean anyPouchUnknown, int mean, int stddev) {
        if (anyPouchUnknown) {
            Rs2Inventory.checkPouches();
            sleep(Rs2Random.randomGaussian(mean, stddev));
        }
    }

    private boolean mineHugeGuardianRemain() {
        if (isInHugeMine()) {
            if (getGuardiansPower() == 0) {
                repairPouches();
                leaveHugeMine();
                optimizedEssenceLoop = false;
                return false;
            }
            if (!Rs2Inventory.isFull()) {
                if (!Rs2Player.isAnimating()) {
                    interactObject(ObjectID.HUGE_GUARDIAN_REMAINS);
                    Rs2Player.waitForAnimation();
                    if (!Rs2Player.isAnimating())
                        interactObject(ObjectID.HUGE_GUARDIAN_REMAINS);
                }
            } else {
                if (Rs2Inventory.allPouchesFull()) {
                    if(Rs2Inventory.hasItem("guardian stone"))
                        optimizedEssenceLoop = true;
                    leaveHugeMine();
                } else {
                    Rs2Inventory.fillPouches();
                    sleep(Rs2Random.randomGaussian(Rs2Random.between(600, 1200), Rs2Random.between(100, 300)));
                    if (!Rs2Inventory.isFull()) {
                        interactObject(ObjectID.HUGE_GUARDIAN_REMAINS);
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void mineGuardianRemains() {
        if (Microbot.getClient().hasHintArrow())
            return;
        if (Rs2Inventory.isFull()) {
            shouldMineGuardianRemains = false;
            return;
        }
        state = GotrState.MINE_LARGE_GUARDIAN_REMAINS;
        useActivity(Activity.GENERAL_MINING);
        maybeIdleCamera();
        if (isInHugeMine()) {
            leaveHugeMine();
            return;
        }
        if (Rs2Player.getSkillRequirement(Skill.AGILITY, 56) && getTimeSincePortal() < 85 && !Rs2Inventory.hasItem(GUARDIAN_ESSENCE)) {
            if (!isInLargeMine() && !isInHugeMine() && (!Rs2Inventory.hasItem(GUARDIAN_FRAGMENTS) || getStartTimer() == -1)) {
                if (Rs2Walker.walkTo(new WorldPoint(3632, 9503, 0), 20)) {
                    log("Traveling to large mine...");
                    interactObject(ObjectID.RUBBLE_43724);
                    if (sleepUntil(Rs2Player::isAnimating)) {
                        sleepUntil(GotrScript::isInLargeMine);
                        if (isInLargeMine()) {
                            sleep(Rs2Random.randomGaussian(Rs2Random.between(2000, 2400), Rs2Random.between(100, 300)));
                            log("Interacting with large guardian remains...");
                            interactObject(ObjectID.LARGE_GUARDIAN_REMAINS);
                            sleepGaussian(1200, 150);
                            Rs2Antiban.actionCooldown();
                            Rs2Antiban.takeMicroBreakByChance();
                        }
                    }
                }
                sleepGaussian(600, 150);
            } else {
                if (!Rs2Player.isAnimating() && getStartTimer() != -1) {
                    // Don't fire the pickaxe special on every available cooldown — perfectly timed
                    // specs are a bot tell. Roll for it and add a short pre-click delay.
                    if (Rs2Equipment.isWearing("dragon pickaxe") && Rs2Random.dicePercentage(70)) {
                        sleep(Rs2Random.between(80, 400));
                        Rs2Combat.setSpecState(true, 1000);
                    }
                    checkPouches(Rs2Random.between(1, 20) == 2, Rs2Random.between(100, 600), Rs2Random.between(100, 300));

                    repairPouches();
                    interactObject(ObjectID.LARGE_GUARDIAN_REMAINS);
                    sleepGaussian(1200, 150);
                    Rs2Antiban.actionCooldown();
                    Rs2Antiban.takeMicroBreakByChance();
                }
            }
        } else {
            //guardian parts
            if (!Rs2Player.isAnimating() && getStartTimer() != -1) {
                if(isInLargeMine()) {
                    leaveLargeMine();
                }
                if (Rs2Equipment.isWearing("dragon pickaxe") && Rs2Random.dicePercentage(70)) {
                    sleep(Rs2Random.between(80, 400));
                    Rs2Combat.setSpecState(true, 1000);
                }
                repairPouches();
                interactObject(ObjectID.GUARDIAN_PARTS_43716);
                sleepGaussian(1200, 150);
                Rs2Antiban.actionCooldown();
                Rs2Antiban.takeMicroBreakByChance();
                // NB: do NOT flip shouldMineGuardianRemains to false here. Mining parts once yields
                // only a handful of fragments; stopping now caused the mine <-> workbench thrash.
                // The full-run threshold in the main loop decides when we've mined enough.
            }
        }
    }

    private void leaveHugeMine() {
        interactObject(38044);
        log("Leave huge mine...");
        Global.sleepUntil(() -> !isInHugeMine(), 5000);

    }

    private static boolean repairPouches() {
        if (!useNpcContact) {
            repairWithCordelia();
            return true;
        }
        if (Rs2Inventory.hasDegradedPouch()) {
            try {
                if (repairViaNpcContact()) return true;
            } catch (Exception ex) {
                Microbot.log("NPC Contact pouch repair failed (" + ex.getClass().getSimpleName()
                        + "); trying Cordelia.");
                Microbot.logStackTrace("GotrScript.repairPouches", ex);
            }
            // NPC Contact didn't complete this tick — fall back to Cordelia (a no-op without pearls)
            // and let the loop retry next tick.
            repairWithCordelia();
            return false;
        }
        return false;
    }

    /**
     * Repairs pouches through the NPC Contact spell WITHOUT going through {@link Rs2Magic#cast},
     * whose {@code MagicAction.getActions()} looks the spell up by the hard-coded name "Npc Contact"
     * on spellbook widget group 218 and NPEs when that lookup returns null — which happens when the
     * spell has been renamed in the RuneLite Spellbook plugin (e.g. to "Astral contact"). We instead
     * find the spell icon by its sprite / "Dark Mage" action and left-click it directly, then drive
     * the Dark Mage dialogue.
     */
    private static boolean repairViaNpcContact() {
        if (!Rs2Magic.isSpellbook(Rs2Spellbook.LUNAR)) {
            Microbot.log("Not on the Lunar spellbook; cannot use NPC Contact. Switching to Cordelia.");
            useNpcContact = false;
            return false;
        }

        Rs2Tab.switchToMagicTab();

        Widget spell = findNpcContactWidget();
        if (spell == null) {
            Microbot.log("Could not find the NPC Contact spell icon in the spellbook (by sprite/action).");
            return false;
        }

        log("Repairing pouches via NPC Contact (Dark Mage)...");
        Rs2Widget.clickWidget(spell);

        // A plain left-click fires the default 'Cast' action, opening the choose-character panel
        // (widget group 75). Some setups bind the spell's left-click straight to 'Dark Mage', in
        // which case the panel never opens and we go straight to dialogue — handle both.
        final int chooseCharacterWidgetId = 75 << 16;
        if (Global.sleepUntil(() -> !Rs2Widget.isHidden(chooseCharacterWidgetId), 3000)) {
            sleep(Rs2Random.randomGaussian(700, 200));
            if (!Rs2Widget.clickWidget("dark mage", Optional.of(75), 0, false)) {
                Microbot.log("Choose-character panel open but Dark Mage entry not found.");
                return false;
            }
        }

        // Dark Mage dialogue: continue past the greeting, then pick the repair option.
        Rs2Player.waitForAnimation();
        sleep(Rs2Random.randomGaussian(1100, 200));
        Rs2Dialogue.clickContinue();
        Rs2Widget.sleepUntilHasWidget("Can you repair my pouches?");
        sleep(Rs2Random.randomGaussian(900, 300));
        Rs2Widget.clickWidget("Can you repair my pouches?", Optional.of(162), 0, true);

        return Global.sleepUntil(() -> !Rs2Inventory.hasDegradedPouch(), 8000);
    }

    /**
     * Finds the NPC Contact spell icon in the Lunar spellbook by its sprite ({@link #NPC_CONTACT_SPRITE_ID})
     * or its "Dark Mage" quick-cast action — deliberately NOT by name, since the spell can be renamed
     * in the RuneLite Spellbook plugin (which is exactly what breaks the client's own name lookup).
     */
    private static Widget findNpcContactWidget() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget spellbook = Microbot.getClient().getWidget(218, 3);
            if (spellbook == null) return null;
            return searchForNpcContact(spellbook);
        }).orElse(null);
    }

    private static Widget searchForNpcContact(Widget w) {
        if (w == null) return null;
        if (isNpcContactIcon(w)) return w;
        for (Widget[] group : new Widget[][]{ w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren() }) {
            if (group == null) continue;
            for (Widget child : group) {
                Widget found = searchForNpcContact(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isNpcContactIcon(Widget w) {
        if (w.getSpriteId() == NPC_CONTACT_SPRITE_ID) return true;
        String[] actions = w.getActions();
        return actions != null && Arrays.stream(actions)
                .anyMatch(a -> a != null && a.equalsIgnoreCase("Dark Mage"));
    }

    /**
     * Repair pouch by talking to cordelia
     * make sure to have the repair unlocked for 25 pearls
     */
    private static void repairWithCordelia() {
        if (!Rs2Inventory.hasDegradedPouch()) return;
        if (!Rs2Inventory.hasItem(ItemID.ABYSSAL_PEARLS)) return;
        Rs2NpcModel pouchRepairNpc = Microbot.getRs2NpcCache().query().withId(NpcID.APPRENTICE_CORDELIA_12180).nearest();
        if (pouchRepairNpc == null) return;
        if (!Rs2Npc.hasAction(pouchRepairNpc.getId(), "Repair")) return;
        if (!Rs2Npc.canWalkTo(pouchRepairNpc.getNpc(), 10)) return;
        if (!pouchRepairNpc.click("Repair")) return;

        Microbot.log("Repairing pouches...");

        Global.sleepUntil(() -> {
            Rs2Dialogue.clickContinue();
            return !Rs2Inventory.hasDegradedPouch();
        }, 10000);

    }

    @Override
    public void shutdown() {
        state = null;
        currentActivity = null;
        Rs2Antiban.resetAntibanSettings();
        super.shutdown();
    }

    public static boolean isOutsideBarrier() {
        int outsideBarrierY = 9482;
        return Rs2Player.getWorldLocation().getY() <= outsideBarrierY
                && Rs2Player.getWorldLocation().getRegionID() == 14484;
    }

    public  static boolean isInLargeMine() {
        int largeMineX = 3637;
        return Rs2Player.getWorldLocation().getRegionID() == 14484
                && Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().getX()) >= largeMineX;
    }

    public  boolean isInHugeMine() {
        int hugeMineX = 3594;
        return Rs2Player.getWorldLocation().getRegionID() == 14484
                && Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().getX()) <= hugeMineX;
    }

    public static boolean isGuardianPortal(GameObject gameObject) {
        return guardianPortalInfo.containsKey(gameObject.getId());
    }

    public ItemManager getItemManager() {
        return Microbot.getItemManager();
    }

    public boolean isInMiniGame() {
        int parentWidgetId = 48889857;
        Widget elementalRuneWidget = Microbot.getClient().getWidget(parentWidgetId);
        return elementalRuneWidget != null;
    }

    public static boolean isInMainRegion() {
        return Rs2Player.getWorldLocation().getRegionID() == 14484;
    }

    public static int getStartTimer() {
        Widget timerWidget = Rs2Widget.getWidget(48889861);
        if (timerWidget != null) {
            String timer = timerWidget.getText();
            if (timer == null) return -1;
            // Split the timer string into minutes and seconds
            String[] timeParts = timer.split(":");

            // Ensure there are two parts (minutes and seconds)
            if (timeParts.length == 2) {
                int minutes = Integer.parseInt(timeParts[0]);
                int seconds = Integer.parseInt(timeParts[1]);

                // Convert the timer to total seconds
                int totalSeconds = (minutes * 60) + seconds;
                return totalSeconds;
            }
        }
        return -1;
    }

    public static int getTimeSincePortal() {
        if(getStartTimer() == -1) {
            return -1;
        }
        int firstPortalTimeAdjustment = isFirstPortal ? 40 : 0;
        return timeSincePortal.map(instant -> (int) ChronoUnit.SECONDS.between(instant, Instant.now())-firstPortalTimeAdjustment).orElse(-1);

    }

    public static List<GameObject> getAvailableAltars() {
        int elementalPoints = elementalRewardPoints;
        int catalyticPoints = catalyticRewardPoints;
        List<GameObject> availableAltars = Rs2GameObject.getGameObjects().stream()
                .filter(x -> {

                    if (!guardianPortalInfo.containsKey(x.getId())) return false;

                    GuardianPortalInfo portalInfo = GotrScript.guardianPortalInfo.get(x.getId());

                    if (portalInfo.getRequiredLevel()
                            > Microbot.getClient().getBoostedSkillLevel(Skill.RUNECRAFT)) {
                        Microbot.log("Filtered altar " + portalInfo.getName() + " – insufficient RC level");
                        return false;
                    }
                    if (portalInfo.getQuestState() != QuestState.FINISHED) {
                        Microbot.log("Filtered altar " + portalInfo.getName() + " – quest not complete");
                        return false;
                    }

                    if (((DynamicObject) x.getRenderable()).getAnimation() == null) {
                        return false;
                    }
                    if (((DynamicObject) x.getRenderable()).getAnimation().getId() != 9363) {
                        return false;
                    }
                    return true;

                })
                .collect(Collectors.toList());

        Microbot.log("Found " + availableAltars.size() + " active altars after filtering.");

        if (config.Mode() == Mode.POINTS) {
            // Sort by strongest → weakest CellType; if equal, fall back to balancing points
            Microbot.log("Sorting by CellType (strongest→weakest) for POINTS mode...");
            return availableAltars.stream()
                    .sorted(
                            Comparator.<GameObject>comparingInt(
                                            o -> GotrScript.guardianPortalInfo.get(o.getId()).getCellType().ordinal()
                                    ).reversed()
                                    .thenComparingInt(o -> {
                                        RuneType rt = GotrScript.guardianPortalInfo.get(o.getId()).getRuneType();
                                        boolean preferElemental = elementalPoints < catalyticPoints;
                                        return ((preferElemental && rt == RuneType.ELEMENTAL) ||
                                                (!preferElemental && rt == RuneType.CATALYTIC)) ? 0 : 1;
                                    })
                    )
                    .collect(Collectors.toList());
        }

        // ELEMENTAL / CATALYTIC / BALANCED: prioritise altars by their actual rune type (read from
        // GuardianPortalInfo) rather than guessing from object id. ELEMENTAL/CATALYTIC pin the
        // preference to that type; BALANCED aims to even the score by crafting whichever type we
        // currently have fewer points of. The preferred type sorts first, but the other type is
        // still kept as a fallback so we never stand idle with a full inventory of essence when no
        // altar of the preferred type happens to be open.
        final RuneType preferred;
        switch (config.Mode()) {
            case ELEMENTAL:
                preferred = RuneType.ELEMENTAL;
                break;
            case CATALYTIC:
                preferred = RuneType.CATALYTIC;
                break;
            case BALANCED:
            default:
                preferred = elementalPoints < catalyticPoints ? RuneType.ELEMENTAL : RuneType.CATALYTIC;
                break;
        }

        Microbot.log("Mode " + config.Mode() + " -> preferring " + preferred + " altars (elemental="
                + elementalPoints + ", catalytic=" + catalyticPoints + ")");

        return availableAltars.stream()
                .sorted(Comparator.<GameObject>comparingInt(o ->
                        guardianPortalInfo.get(o.getId()).getRuneType() == preferred ? 0 : 1))
                .collect(Collectors.toList());
    }

    private int getGuardiansPower() {
        Widget pWidget = Rs2Widget.getWidget(48889874);
        if (pWidget == null) {
            return 0;
        }

        Matcher matcher = Pattern.compile("(\\d+)%").matcher(pWidget.getText());

        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    public static void resetPlugin() {
        guardians.clear();
        activeGuardianPortals.clear();
        greatGuardian = null;
        Microbot.getClient().clearHintArrow();
    }

    /**
     * Switch the antiban activity only when the phase actually changes. GOTR alternates between a
     * mining phase (guardian remains/parts) and a runecrafting phase (essence + rune altars);
     * telling the antiban engine which one we're in lets it pick appropriate intensity/timing.
     * {@link Rs2Antiban#setActivity} resets the play-style evolution, so we must not call it every
     * tick.
     */
    private static void useActivity(Activity activity) {
        if (currentActivity != activity) {
            currentActivity = activity;
            Rs2Antiban.setActivity(activity);
        }
    }

    /**
     * Occasionally glance the camera around during the long mining stretches, like a bored human.
     */
    private static void maybeIdleCamera() {
        if (Rs2Random.dicePercentage(4)) {
            Rs2Camera.setAngle(Rs2Random.between(0, 359), Rs2Random.between(20, 60));
        }
    }

    /**
     * Walk-first object interaction.
     *
     * <p>The migrated Queryable API ({@code cache.query().interact(id, action)}) resolves
     * {@code nearestReachable()} and clicks at the player's current tile — it does NOT walk into
     * range. Legacy {@code Rs2GameObject.interact(id, action)} auto-walked when the target was
     * more than 51 tiles away. After the query-API migration GOTR lost that auto-walk, so any
     * interaction issued while out of range silently no-ops every tick and the bot just stands
     * there (see docs/PLUGIN_DEBUGGING_NOTES.md §3). This restores the legacy behaviour: web-walk
     * when far, hand off to the game's click-to-walk once close.
     */
    private static boolean interactObject(int id) {
        return interactObject(id, null);
    }

    private static boolean interactObject(int id, String action) {
        return interactObject(Microbot.getRs2TileObjectCache().query().withId(id).nearest(), action);
    }

    private static boolean interactObject(Rs2TileObjectModel obj, String action) {
        if (obj == null) return false;
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        WorldPoint objLoc = obj.getWorldLocation();
        if (playerLoc != null && objLoc != null && playerLoc.distanceTo(objLoc) > 51) {
            log("Object " + obj.getId() + " is " + playerLoc.distanceTo(objLoc) + " tiles away, walking into range...");
            Rs2Walker.walkTo(objLoc);
            return false;
        }
        // In click range: drop any lingering web-walk target so the game's click-to-walk drives
        // the final approach, then interact.
        Rs2Walker.setTarget(null);
        return (action == null || action.isEmpty()) ? obj.click() : obj.click(action);
    }

    public static Rs2TileObjectModel findRcAltar() {
        return Microbot.getRs2TileObjectCache().query().withIds(
                ObjectID.ALTAR_34760, ObjectID.ALTAR_34761, ObjectID.ALTAR_34762, ObjectID.ALTAR_34763, ObjectID.ALTAR_34764,
                ObjectID.ALTAR_34765, ObjectID.ALTAR_34766, ObjectID.ALTAR_34767, ObjectID.ALTAR_34768, ObjectID.ALTAR_34769, ObjectID.ALTAR_34770,
                ObjectID.ALTAR_34771, ObjectID.ALTAR_34772, ObjectID.ALTAR_43479).nearest();
    }

    public static Rs2TileObjectModel findPortalToLeaveAltar() {
        return Microbot.getRs2TileObjectCache().query().withIds(
                ObjectID.PORTAL_34748, ObjectID.PORTAL_34749, ObjectID.PORTAL_34750, ObjectID.PORTAL_34751, ObjectID.PORTAL_34752,
                ObjectID.PORTAL_34753, ObjectID.PORTAL_34754, ObjectID.PORTAL_34755, ObjectID.PORTAL_34756, ObjectID.PORTAL_34757, ObjectID.PORTAL_34758,
                ObjectID.PORTAL_34758, ObjectID.PORTAL_34759, ObjectID.PORTAL_43478).nearest();
    }
    public static boolean leaveMinigame() {
        GotrScript.isInMiniGame = !isOutsideBarrier() && isInMainRegion();
        if (!isInMiniGame) {
            return true;    // Already outside the minigame, successfully left
        }
        if(isInLargeMine()) {
            interactObject(ObjectID.RUBBLE_43726);
            Rs2Player.waitForAnimation();
            sleepUntil(()-> !isInLargeMine());
            if (isInLargeMine()){
                log("Failed to leave large mine, retrying...");
                return false;
            }

        }
        interactObject(ObjectID.BARRIER_43700, "quick-pass");
        Rs2Player.waitForWalking();
        sleepUntil( ()-> {return !(!isOutsideBarrier() && isInMainRegion());}, 200);
        GotrScript.isInMiniGame  = !isOutsideBarrier() && isInMainRegion();
        return !GotrScript.isInMiniGame;// Successfully left the minigame
    }
}

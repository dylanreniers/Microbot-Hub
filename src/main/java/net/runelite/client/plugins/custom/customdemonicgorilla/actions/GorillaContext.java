package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.HeadIcon;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.utils.FixedSizeQueue;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;

import java.time.Instant;

/**
 * Durable state of the Demonic Gorilla run, shared across ticks. Actions read/write it via
 * {@link GorillaState#context()}; the plugin's game-tick and projectile events also write to it
 * (player movement history, the incoming boulder position). Replaces the pile of static/instance
 * fields the old single-loop script carried, so state now lives in one place and no longer leaks
 * across plugin restarts. Mirrors the {@code FightContext} pattern used by the Zulrah script.
 */
@Getter
@Setter
public class GorillaContext {

    // --- Environment (built once in the script's onInitialize) ---
    private Rs2InventorySetup rangeGear;
    private Rs2InventorySetup magicGear;
    private Rs2InventorySetup meleeGear;
    private Rs2InventorySetup bankingGear;
    /** Set true once the inventory setups have been built; gates the pipeline until ready. */
    private boolean initialized;

    // --- High-level state machine ---
    private volatile State botStatus = State.BANKING;
    private BankingStep bankingStep = BankingStep.BANK;
    private TravelStep travelStep = TravelStep.GNOME_STRONGHOLD;
    /** Consecutive failed banking-setup load attempts; the load booleans go transiently false on
     *  bank-mirror/sync timing, so we retry a bounded number of times before giving up. */
    private int bankLoadAttempts;

    // --- Combat state ---
    private volatile Rs2NpcModel currentTarget;
    private Rs2PrayerEnum currentDefensivePrayer;
    private Rs2PrayerEnum currentOffensivePrayer;
    private HeadIcon currentOverheadIcon;
    private ArmorEquiped currentGear = ArmorEquiped.MELEE;
    private boolean lootAttempted;

    // --- Between-kills loot wait: after a gorilla dies we hold off engaging the next one until looting
    //     is finished. Armed on death with a grace for the drop to appear; the looter extends the
    //     deadline on every pickup, so we only proceed once nothing has been looted for a short window. ---
    /** How long after a kill to wait for the drop to appear before looting counts as "settled". */
    public static final long INITIAL_LOOT_GRACE_MS = 2000;
    /** Each successful pickup pushes the deadline this far out; when it lapses, looting is done. */
    public static final long LOOT_PICKUP_GRACE_MS = 1500;
    /** True between a kill and looting being finished; blocks acquiring/attacking the next gorilla. */
    private volatile boolean awaitingLoot;
    /** Wall-clock deadline; looting is considered done once now exceeds it with no further pickups. */
    private volatile long lootDeadlineMs;

    // --- Kill accounting ---
    private int killCount;
    private int currentTripKillCount;

    // --- Attack-style prediction (see GorillaAttacksAction) ---
    /** Set by the plugin's OverheadTextChanged handler when the target gorilla shouts "Rhaaaa" — the
     *  reliable style-switch cry (animation 7224 is the ambiguous defensive emote and must NOT be used).
     *  Consumed by GorillaAttacksAction, which then moves away and pre-prays the prediction. */
    private volatile boolean styleSwitchCryPending;
    /** The gorilla's last CONFIRMED attack style (from an actual attack animation). */
    private AttackStyle currentAttackStyle = AttackStyle.UNKNOWN;
    /** The style it was using when the "Rhaaaaa" switch cry fired; drives the next-style prediction. */
    private AttackStyle previousAttackStyle = AttackStyle.UNKNOWN;
    /** True between the switch cry and the first attack of the new style; while set (and the gorilla is
     *  at range) we pre-pray the magic&lt;-&gt;range prediction. Melee is handled reactively by the fail-check. */
    private volatile boolean awaitingStyleSwitch;

    // --- Attack/animation tracking (populated from the target NPC and game ticks) ---
    private int npcAnimationCount;
    private int lastAnimation;
    private int lastRealAnimation;
    private int lastAttackAnimation;
    private int gameTickCount;
    private int lastGameTick;
    private volatile boolean playerMoved;
    private WorldPoint lastGorillaLocation;
    private int failedAttacks;
    private int failedCount;
    private Instant outOfCombatTime = Instant.now();

    // --- Incoming AOE boulder, fed by the plugin's ProjectileMoved handler. The boulder lands on its
    //     target tile; we arm a dodge as soon as one is inbound on our tile (early), rather than waiting
    //     for it to arrive. ---
    /** The tile the inbound boulder will land on (its projectile target), or null if none inbound. */
    private volatile WorldPoint boulderTargetTile;
    /** Set when a boulder targeting our tile (or an adjacent one) is detected; consumed by BoulderDodgeAction. */
    private volatile boolean boulderDodgePending;
    /** Wall-clock when the inbound boulder lands; we hold off the tile until then so the melee re-attack
     *  can't walk us back onto it before impact. */
    private volatile long boulderLandsAtMs;

    // --- Player movement history, updated each game tick by the plugin ---
    private final FixedSizeQueue<WorldPoint> lastLocation = new FixedSizeQueue<>(2);

    // --- logOnceToChat de-duplication ---
    private String lastChatMessage = "";

    /** Clears per-run/per-fight state so a fresh start (or restart) begins clean. */
    public void reset() {
        botStatus = State.BANKING;
        bankingStep = BankingStep.BANK;
        travelStep = TravelStep.GNOME_STRONGHOLD;
        bankLoadAttempts = 0;
        currentTarget = null;
        currentDefensivePrayer = null;
        currentOffensivePrayer = null;
        currentOverheadIcon = null;
        currentGear = ArmorEquiped.MELEE;
        lootAttempted = false;
        awaitingLoot = false;
        lootDeadlineMs = 0;
        currentTripKillCount = 0;
        styleSwitchCryPending = false;
        currentAttackStyle = AttackStyle.UNKNOWN;
        previousAttackStyle = AttackStyle.UNKNOWN;
        awaitingStyleSwitch = false;
        npcAnimationCount = 0;
        lastGorillaLocation = null;
        failedAttacks = 0;
        failedCount = 0;
        outOfCombatTime = Instant.now();
        boulderTargetTile = null;
        boulderDodgePending = false;
        boulderLandsAtMs = 0;
    }

    public enum AttackStyle {MAGIC, RANGED, MELEE, UNKNOWN}

    public enum State {BANKING, TRAVEL_TO_GORILLAS, FIGHTING}

    public enum TravelStep {GNOME_STRONGHOLD, TRAVEL_TO_OPENING, CRASH_SITE, IN_CAVE, AT_GORILLAS}

    public enum BankingStep {BANK, LOAD_INVENTORY}

    public enum ArmorEquiped {MELEE, RANGED, MAGIC}
}

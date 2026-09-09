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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Wall-clock of the last worn-gear verification/re-equip attempt; rate-limits the recovery retry so a
     *  persistently-full inventory can't hot-loop wearEquipment() and saturate the client thread. */
    private volatile long lastGearVerifyMs;
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
    /** True between the switch cry and the first attack of the new style. */
    private volatile boolean awaitingStyleSwitch;
    /** Wall-clock when the switch read was armed (the cry). The melee-read hold is capped relative to this
     *  so a gorilla that can't reach us (stuck behind another gorilla / a wall) doesn't stall the fight. */
    private volatile long styleSwitchArmedMs;
    /** True once we've opened the read gap (reached >= ~4 tiles) since the cry. Only then is the gorilla
     *  being within melee distance a real melee tell — before that we're still mid-step and close. */
    private volatile boolean awaitingGapOpened;
    /** The gorilla's tile captured at the pre-pray moment (when we flip prayer on the cry). After a
     *  magic/ranged switch we step away and watch this baseline: if the gorilla LEAVES this tile it's
     *  walking to us (melee); if it stays put it's the other of magic/range. */
    private volatile WorldPoint gorillaTileAtPrePray;

    // --- Rotation/timing model (Woox-style, see onAnimationChanged) --------------------------------
    /** Ticks between a gorilla's successive attacks. */
    public static final int ATTACK_RATE = 5;
    /** A gorilla switches style after this many attacks the player prayed CORRECTLY against. */
    public static final int ATTACKS_PER_SWITCH = 3;
    /** Countdown to the next style switch; decremented only on a correctly-prayed attack, reset on a
     *  confirmed switch. Lets us know WHEN a switch is plausible independently of the "Rhaaaa" cry. */
    private int attacksUntilSwitch = ATTACKS_PER_SWITCH;
    /** Game tick (client.getTickCount()) the gorilla's next attack is expected on; set to lastAttack + ATTACK_RATE.
     *  Used to only read the melee-approach tell inside the attack window, not on idle repositioning. */
    private int nextAttackTick = -100;
    /** The gorilla's distance to us on the previous game tick, for the closing-in melee tell (Phase 2). */
    private int lastGorillaDistToPlayer = -1;
    /** Set true when the target gorilla launches a (non-boulder) projectile since the last cry — proof the
     *  attack is magic/ranged, so it CANNOT be melee. Cleared on each cry. Ground-truth from onProjectileMoved. */
    private volatile boolean gorillaProjectileFiredSinceCry;

    /** True when a style switch is due per the rotation counter (independent of the cry). */
    public boolean isSwitchDue() {
        return attacksUntilSwitch <= 0;
    }

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
    /** All boulder landing tiles currently in flight near us, tile -> wall-clock land time. During an AOE
     *  barrage several are airborne at once; the dodge must avoid ALL of them, not just the latest, so it
     *  doesn't hop off one boulder's tile straight onto another's. Written from the client-thread projectile
     *  handler, read from the dodge action — hence concurrent. */
    private final Map<WorldPoint, Long> inboundBoulders = new ConcurrentHashMap<>();
    /** Wall-clock until which a boulder is inbound/just-landed: while in this window we don't re-approach
     *  the gorilla (would path onto danger) and the dodge action stays active every tick. Replaces the old
     *  blocking sleep, which froze the whole pipeline and let follow-up boulders connect. */
    private volatile long boulderDangerUntilMs;

    /** Record a boulder landing on {@code tile} at {@code landsAtMs}; extends the danger window to cover it. */
    public void addInboundBoulder(WorldPoint tile, long landsAtMs) {
        inboundBoulders.put(tile, landsAtMs);
        if (landsAtMs + 300 > boulderDangerUntilMs) {
            boulderDangerUntilMs = landsAtMs + 300;
        }
    }

    /** Drop boulders that have already landed (past their land time + a short splat grace). */
    public void pruneInboundBoulders(long now) {
        inboundBoulders.values().removeIf(landsAt -> landsAt + 600 < now);
    }

    // --- Diagnostics (Phase 1-3 validation). Incremented from client-thread handlers (plain int, no
    //     logging on that thread — GameChatAppender would deadlock); logged periodically off-thread by
    //     DiagnosticsAction. Approximate under races, which is fine for a running tally. ---
    private int diagCries;
    private int diagMeleeTells;
    private int diagProjectileVetoes;
    private int diagCorrectPrayerAttacks;
    private int diagWrongPrayerAttacks;
    private int diagHitsTaken;
    private int diagBoulderWindowHits;
    /** How many times a gorilla attack projectile was detected (proves Phase 3 source-matching is alive). */
    private int diagGorillaProjectiles;
    private long diagLastLogMs;

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
        lastGearVerifyMs = 0;
        lootAttempted = false;
        awaitingLoot = false;
        lootDeadlineMs = 0;
        currentTripKillCount = 0;
        styleSwitchCryPending = false;
        currentAttackStyle = AttackStyle.UNKNOWN;
        previousAttackStyle = AttackStyle.UNKNOWN;
        awaitingStyleSwitch = false;
        styleSwitchArmedMs = 0;
        awaitingGapOpened = false;
        gorillaTileAtPrePray = null;
        attacksUntilSwitch = ATTACKS_PER_SWITCH;
        nextAttackTick = -100;
        lastGorillaDistToPlayer = -1;
        gorillaProjectileFiredSinceCry = false;
        npcAnimationCount = 0;
        lastGorillaLocation = null;
        failedAttacks = 0;
        failedCount = 0;
        outOfCombatTime = Instant.now();
        boulderTargetTile = null;
        boulderDodgePending = false;
        boulderLandsAtMs = 0;
        inboundBoulders.clear();
        boulderDangerUntilMs = 0;
        diagCries = 0;
        diagMeleeTells = 0;
        diagProjectileVetoes = 0;
        diagCorrectPrayerAttacks = 0;
        diagWrongPrayerAttacks = 0;
        diagHitsTaken = 0;
        diagBoulderWindowHits = 0;
        diagGorillaProjectiles = 0;
        diagLastLogMs = 0;
    }

    public enum AttackStyle {MAGIC, RANGED, MELEE, UNKNOWN}

    public enum State {BANKING, TRAVEL_TO_GORILLAS, FIGHTING}

    public enum TravelStep {GNOME_STRONGHOLD, TRAVEL_TO_OPENING, CRASH_SITE, IN_CAVE, AT_GORILLAS}

    public enum BankingStep {BANK, LOAD_INVENTORY}

    public enum ArmorEquiped {MELEE, RANGED, MAGIC}
}

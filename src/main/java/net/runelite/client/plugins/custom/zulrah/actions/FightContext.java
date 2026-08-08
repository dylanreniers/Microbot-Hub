package net.runelite.client.plugins.custom.zulrah.actions;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.custom.zulrah.rotationutils.ZulrahPhase;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;

/**
 * Durable state of the current fight, shared across ticks. Actions read/write it via
 * {@link ZulrahState#context()}; the plugin's animation events (phase change, melee swing, jad
 * flick) also write to it. Fields are volatile where they are touched from both the client (event)
 * thread and the script tick thread.
 */
@Getter
@Setter
public class FightContext {

    public static final int DEFAULT_ATTACK_SPEED_TICKS = 4;

    // --- Environment (set once on initialise) ---
    private final ItemManager itemManager;
    private Rs2InventorySetup magicSetup;
    private Rs2InventorySetup rangeSetup;

    // --- Durable fight state ---
    /** Current phase, set by the plugin's phase-transition events. */
    private volatile ZulrahPhase phase;
    /** Set true by an event when a new phase arrives; consumed by SyncPhaseAction. */
    private volatile boolean phaseChanged;
    /** The tile to stand on this phase. */
    private volatile WorldPoint standLocation;
    /** The next phase's stand tile (known once the rotation is identified); used to pre-move out of
     *  end-of-phase venom clouds before the phase actually transitions. Null if unknown. */
    private volatile WorldPoint nextStandLocation;
    /** Set once we've pre-moved toward the next phase because a venom cloud spawned; blocks further
     *  venom pre-moves this phase. Reset on resurface so each phase re-arms exactly one pre-move. */
    private volatile boolean venomPreMoved;
    /** Whether this is the NE melee-dodge phase. */
    private volatile boolean meleeDodgePhase;
    /** Set once the jad phase's starting overhead has been anchored; the flick alternates it after. */
    private volatile boolean jadStartPrayerSet;
    /** Which of the two NE dodge tiles is currently targeted; flipped on each tail swing. */
    private volatile boolean meleeDodgeAtNorth = true;

    /** Wall-clock of the last attack, for the attack-on-cooldown-while-moving cadence. */
    private long lastAttackAtMs;
    /** Bounded gear-swap attempts for the current phase. */
    private int gearSwapAttempts;

    /** Weapon-speed cache, refreshed only when the equipped weapon changes. */
    private int cachedWeaponId = -1;
    private int cachedAttackSpeedTicks = DEFAULT_ATTACK_SPEED_TICKS;

    public FightContext(ItemManager itemManager) {
        this.itemManager = itemManager;
    }

    /** Clears per-fight state so a fresh encounter starts idle at the first phase. */
    public void reset() {
        phase = null;
        phaseChanged = false;
        standLocation = null;
        nextStandLocation = null;
        venomPreMoved = false;
        meleeDodgePhase = false;
        jadStartPrayerSet = false;
        meleeDodgeAtNorth = true;
        lastAttackAtMs = 0;
        gearSwapAttempts = 0;
    }
}

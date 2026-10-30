package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Durable state of a Cerberus fight, shared across ticks. The plugin's {@code @Subscribe} handlers
 * (client thread) write the boss / attack counter / triple / soul / lava state; the action pipeline
 * (script thread) reads it. Fields touched from both threads are {@code volatile} or concurrent.
 */
@Getter
@Setter
public class CerberusContext {

    /**
     * Between-kills state. STARTING: decide on the first tick (in the lair -> FIGHT, else RESTOCK). FIGHT:
     * in the lair, fighting or waiting for her respawn. LOOT: she just died. RESTOCK: Ferox trip and back
     * into a free lair. STOPPED: a trip step failed; nothing runs until the plugin is restarted. RESETTING:
     * no-ghosts strategy — she dropped below 400 HP too early, walking out over the flames to reset her.
     */
    public enum Phase {STARTING, FIGHT, LOOT, RESTOCK, STOPPED, RESETTING}

    /** Which hit of the triple attack we're praying against. MAGIC is also the pre-pray before it. */
    public enum TripleStage {NONE, MAGIC, MISSILES, MELEE}

    /** One summoned soul of the current wave: its x (for west -> east order) and the prayer it needs. */
    public static final class Soul {
        private final int x;
        private final Rs2PrayerEnum prayer;

        public Soul(int x, Rs2PrayerEnum prayer) {
            this.x = x;
            this.prayer = prayer;
        }

        public int getX() {
            return x;
        }

        public Rs2PrayerEnum getPrayer() {
            return prayer;
        }
    }

    private volatile Phase phase = Phase.STARTING;
    /** The resupply inventory setup, resolved live by name at start (the config's snapshot goes stale). */
    private volatile Rs2InventorySetup resupplySetup;
    /** Where to stand relative to her centre: +SIDE_SPOT_OFFSET (east) or -SIDE_SPOT_OFFSET (west); null
     *  until picked for the current room. */
    private volatile Integer sideOffset;
    /** Where the winch dropped us (south of the entrance flames), for a no-ghosts reset; null if unknown. */
    private volatile WorldPoint lairEntrance;
    /** Her HP estimated from the health bar each tick; -1 while no bar is shown. */
    private volatile int bossHp = -1;
    /** No-ghosts: true while we deliberately stop attacking her (near 400 HP, before attack 14). */
    private volatile boolean holdingDamage;
    /** True if we switched auto-retaliate off for the hold (so we switch it back on afterwards). */
    private volatile boolean retaliateDisabledByUs;
    /** Loot timing (ms): when looting began, and when it may end if nothing more turns up. */
    private volatile long lootStartMs;
    private volatile long lootDeadlineMs;

    // --- Boss ---
    /** Cerberus while she has the attackable id (5862); null when no fight is active. */
    private volatile NPC boss;
    /** True while the client thread sees us interacting with the boss (getInteracting() is client-thread only). */
    private volatile boolean engaged;

    // --- Attack counter (only meaningful while synced, i.e. we saw her become 5862) ---
    /** False when the plugin started mid-fight: the count is unknown, so only the camp prayer is used. */
    private volatile boolean counterSynced;
    /** Attacks seen since she became 5862; an attack = her animation going from -1 to something else. */
    private volatile int attackCount;
    private volatile int lastBossAnimation = -1;

    // --- Triple attack ---
    private volatile TripleStage tripleStage = TripleStage.NONE;
    /** True from the triple's animation start until her animation is back to -1. */
    private volatile boolean tripleInProgress;

    // --- Summoned souls ---
    private volatile int soulWaveSpawnTick = -1;
    private final List<Soul> souls = new CopyOnWriteArrayList<>();

    // --- Lava pools: graphics object -> tile, pruned once the graphics object finishes ---
    private final Map<GraphicsObject, WorldPoint> lavaPools = new ConcurrentHashMap<>();

    /** True while a protection flick is due (soul wave out, triple pending/in progress): the pipeline then
     *  holds its non-urgent clicks so they can't delay the flick on the shared input lock. */
    private volatile boolean prayerCritical;

    /** Game tick of the last eat/drink, so the next one waits until this one has registered. */
    private volatile int lastSustainTick = -100;

    /** Set when the fight ends so the pipeline drops prayers off the client thread. */
    private volatile boolean prayersOffPending;

    /** Tick-stamped events from the client-thread handlers, logged off-thread by DiagnosticsAction. */
    private final Queue<String> eventLog = new ConcurrentLinkedQueue<>();

    /** Game tick of the last menu-action click. Only one click lands per tick, so clicks are claimed. */
    private int lastClickTick = -1;

    public boolean isFightActive() {
        return boss != null;
    }

    /** Claims this tick's single click slot; false if something already clicked this tick. */
    public synchronized boolean claimClick(int tick) {
        if (lastClickTick == tick) {
            return false;
        }
        lastClickTick = tick;
        return true;
    }

    /** Records a click that must happen regardless (protection prayer switches). */
    public synchronized void markClick(int tick) {
        lastClickTick = tick;
    }

    /** The current wave's souls in attack order (west -> east). */
    public List<Soul> soulsInAttackOrder() {
        return souls.stream().sorted(Comparator.comparingInt(Soul::getX)).collect(Collectors.toList());
    }

    public Set<WorldPoint> lavaTiles() {
        return new HashSet<>(lavaPools.values());
    }

    /** Clears everything tied to one Cerberus fight. */
    public void resetFight() {
        boss = null;
        engaged = false;
        counterSynced = false;
        attackCount = 0;
        lastBossAnimation = -1;
        tripleStage = TripleStage.NONE;
        tripleInProgress = false;
        prayerCritical = false;
        bossHp = -1;
        holdingDamage = false;
        soulWaveSpawnTick = -1;
        souls.clear();
        lavaPools.clear();
    }

    public void reset() {
        resetFight();
        phase = Phase.STARTING;
        sideOffset = null;
        lairEntrance = null;
        retaliateDisabledByUs = false;
        lootStartMs = 0;
        lootDeadlineMs = 0;
        prayersOffPending = false;
        lastSustainTick = -100;
        synchronized (this) {
            lastClickTick = -1;
        }
    }
}

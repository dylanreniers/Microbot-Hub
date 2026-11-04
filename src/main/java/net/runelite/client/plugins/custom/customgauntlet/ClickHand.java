package net.runelite.client.plugins.custom.customgauntlet;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The fight's one "hand": every click (walk, prayer, eat, drink, equip, attack) goes through here, one at a time, on
 * its own thread, with human timing. Microbot holds one pending menu action ({@code Microbot.targetMenu}); a click armed
 * before the previous one landed overwrote it, so walk + prayer sent from the same client-thread job lost the walk.
 * Here each click waits until the game consumed it, then a human gap (~110-300ms) follows, so 2 (sometimes 3) clicks
 * land per 600ms tick in priority order. Prayers are direct menu actions (no mouse); inventory clicks open the inventory
 * (ESC) first, like a player.
 */
public class ClickHand {

    public enum Kind { FLEE, WALK, PROTECT_PRAYER, EAT, DRINK, OFFENSIVE_PRAYER, EQUIP, ATTACK, HOVER }

    public enum Tab { NONE, INVENTORY, PRAYER }

    // FLEE = a walk while standing on a hazard: always first (a flee waiting behind prayer clicks was dropped and we
    // stood on the hazard for 5 seconds).
    private static final int[] TORNADO_ORDER = rank(Kind.FLEE, Kind.WALK, Kind.PROTECT_PRAYER, Kind.EAT, Kind.DRINK,
            Kind.OFFENSIVE_PRAYER, Kind.EQUIP, Kind.ATTACK, Kind.HOVER);
    // Outside tornadoes a walk is a hazard flee: right after the protection prayer.
    private static final int[] CALM_ORDER = rank(Kind.FLEE, Kind.PROTECT_PRAYER, Kind.WALK, Kind.EAT, Kind.EQUIP, Kind.DRINK,
            Kind.OFFENSIVE_PRAYER, Kind.ATTACK, Kind.HOVER);

    private static final long STALE_WALK_MS = 900;      // a walk target this old was re-decided by now
    private static final long STALE_MS = 2400;
    private static final long PRAYER_SETTLE_MS = 1200;  // the prayer's state shows a tick later: don't click it twice
    private static final long EQUIP_SETTLE_MS = 1200;
    private static final long IDLE_FOR_REACTION_MS = 1200; // first click after a quiet spell gets a reaction delay
    private static final long LAND_TIMEOUT_MS = 400;

    private static final class Request {
        final Kind kind;
        final String key;
        final Tab tab;
        final Runnable action;
        final BooleanSupplier stillWanted;
        final String label;
        final Supplier<java.awt.Point> aim; // where the click goes, to move there first (null: the action moves itself)
        long queuedAt = System.currentTimeMillis();

        Request(Kind kind, String key, Tab tab, Runnable action, BooleanSupplier stillWanted, String label,
                Supplier<java.awt.Point> aim) {
            this.kind = kind;
            this.key = key;
            this.tab = tab;
            this.action = action;
            this.stillWanted = stillWanted;
            this.label = label;
            this.aim = aim;
        }

        /** Clicks an escape walk may cut short (never a walk or a prayer: those are instant). */
        boolean interruptible() {
            return kind == Kind.EAT || kind == Kind.DRINK || kind == Kind.EQUIP;
        }
    }

    private final Map<String, Request> pending = new HashMap<>();
    private final Map<String, Long> settleUntil = new HashMap<>();
    private final IntSupplier tick;
    private final BooleanSupplier tornadoesUp;
    private volatile boolean running;
    private Thread thread;
    private long lastClickEnd = 0;
    private int lastGap = -1;
    private int lastClickTick = -1;
    private boolean tabKeyWarned = false;
    private String lastWalkLabel = null;  // last walk clicked, to skip an identical re-click right after it
    private long lastWalkAt = 0;
    private static final long DUPLICATE_WALK_MS = 700;
    private volatile String aborted = null;        // why the running action gave up before clicking (null: it clicked)
    private volatile String currentKey = null;     // key of the click being made right now
    private final Map<String, Integer> landedTick = new HashMap<>();
    private ActivityIntensity savedIntensity = null; // the antiban intensity before the fight sped the mouse up

    // Per tornado phase, for the summary line
    private int phaseClicks, phaseSameTick, phaseWaitedTick;
    private long phaseGapSum;

    public ClickHand(IntSupplier tick, BooleanSupplier tornadoesUp) {
        this.tick = tick;
        this.tornadoesUp = tornadoesUp;
    }

    private static int[] rank(Kind... order) {
        int[] r = new int[Kind.values().length];
        for (int i = 0; i < order.length; i++) r[order[i].ordinal()] = i;
        return r;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        // Microbot's natural mouse speed follows the antiban activity intensity; the default profile ("average
        // computer user") took ~300ms per move, so tornado phases averaged one click per 550ms. HIGH = fast gamer.
        savedIntensity = Rs2Antiban.getActivityIntensity();
        Rs2Antiban.setActivityIntensity(ActivityIntensity.HIGH);
        thread = new Thread(this::loop, "CustomGauntlet-ClickHand");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        pending.clear();
        if (savedIntensity != null) Rs2Antiban.setActivityIntensity(savedIntensity);
        savedIntensity = null;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    /**
     * Queues a click. A request with the same key replaces the waiting one (the newest decision wins). A walk drops a
     * waiting attack (the attack would cancel the walk) and any hover.
     */
    public void submit(Kind kind, String key, Tab tab, Runnable action, BooleanSupplier stillWanted, String label) {
        submit(kind, key, tab, action, stillWanted, label, null);
    }

    public synchronized void submit(Kind kind, String key, Tab tab, Runnable action, BooleanSupplier stillWanted, String label,
                                    Supplier<java.awt.Point> aim) {
        Long settle = settleUntil.get(key);
        if (settle != null && System.currentTimeMillis() < settle) return;
        // The dodge re-sends a walk until the game shows the new destination; the same tile again is a wasted click.
        if (isWalk(kind) && label.equals(lastWalkLabel) && System.currentTimeMillis() - lastWalkAt < DUPLICATE_WALK_MS) return;
        if (isWalk(kind)) pending.remove("attack");
        if (kind != Kind.HOVER) pending.remove("hover");
        pending.put(key, new Request(kind, key, tab, action, stillWanted, label, aim));
    }

    /** Drops a waiting click with this key. */
    public synchronized void cancel(String key) {
        Request r = pending.remove(key);
        if (r != null) Microbot.log(String.format("[Click] %s cancelled", r.label));
    }

    /** Called by a running action that decided not to click after all (logged as aborted, no landing wait). */
    public void abortCurrent(String why) {
        aborted = why;
    }

    /** True while a click with this key is waiting or being made. */
    public synchronized boolean isBusyWith(String key) {
        return pending.containsKey(key) || key.equals(currentKey);
    }

    /** Game tick the last click with this key landed, or -100. */
    public synchronized int lastLandedTick(String key) {
        return landedTick.getOrDefault(key, -100);
    }

    /** True while a walk off a hazard is waiting. */
    public synchronized boolean isFleePending() {
        Request w = pending.get("walk");
        return w != null && w.kind == Kind.FLEE;
    }

    /** True while a click of this kind is waiting. */
    public synchronized boolean isKindPending(Kind kind) {
        return pending.values().stream().anyMatch(r -> r.kind == kind);
    }

    /** True while a click with this key is waiting. */
    public synchronized boolean isPending(String key) {
        return pending.containsKey(key);
    }

    private synchronized Request pickNext() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(r -> {
            // A flee is never too old: while it waits we're still on the hazard (a newer decision replaces it).
            boolean stale = r.kind != Kind.FLEE && now - r.queuedAt > (r.kind == Kind.WALK ? STALE_WALK_MS : STALE_MS);
            if (stale && r.kind != Kind.HOVER) Microbot.log(String.format("[Click] %s dropped - waited %dms", r.label, now - r.queuedAt));
            return stale;
        });
        if (pending.isEmpty()) return null;
        int[] order = tornadoesUp.getAsBoolean() ? TORNADO_ORDER : CALM_ORDER;
        Tab current = currentTab();
        Request best = null;
        for (Request r : pending.values()) {
            if (best == null) { best = r; continue; }
            int a = order[r.kind.ordinal()], b = order[best.kind.ordinal()];
            // Same rank: stay on the open tab (both prayer clicks before switching back to the inventory).
            if (a < b || (a == b && r.tab == current && best.tab != current)) best = r;
        }
        return best;
    }

    private synchronized void take(Request r) {
        if (pending.get(r.key) == r) pending.remove(r.key);
    }

    private void loop() {
        while (running) {
            try {
                Request r = pickNext();
                if (r == null) {
                    Thread.sleep(10);
                    continue;
                }
                if (System.currentTimeMillis() - lastClickEnd > IDLE_FOR_REACTION_MS && r.kind != Kind.HOVER && !isPrayer(r.kind)) {
                    // Reacting to something new after a quiet spell; pick again after (the decision may have changed).
                    // An escape walk when tornadoes appear gets a focused player's quicker reaction.
                    boolean escape = r.kind == Kind.FLEE || (r.kind == Kind.WALK && tornadoesUp.getAsBoolean());
                    Thread.sleep(escape ? Rs2Random.logNormalBounded(80, 150) : Rs2Random.logNormalBounded(150, 300));
                    lastClickEnd = System.currentTimeMillis() - IDLE_FOR_REACTION_MS + 1;
                    continue;
                }
                take(r);
                if (isPrayer(r.kind) && settling(r.key)) {
                    // Clicked a moment ago and the game just hasn't shown it yet: a second click switches it off.
                    Microbot.log(String.format("[Click] %s dropped - clicked it already", r.label));
                    continue;
                }
                currentKey = r.key;
                if (!r.stillWanted.getAsBoolean()) {
                    currentKey = null;
                    if (r.kind != Kind.HOVER) Microbot.log(String.format("[Click] %s dropped - no longer wanted", r.label));
                    continue;
                }
                try {
                    click(r);
                } finally {
                    currentKey = null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Microbot.logStackTrace("ClickHand", e);
            }
        }
    }

    private void click(Request r) throws InterruptedException {
        long start = System.currentTimeMillis();
        int queuedTick = tick.getAsInt();
        openTab(r.tab);
        if (isWalk(r.kind)) {
            synchronized (this) {
                lastWalkLabel = r.label;
                lastWalkAt = System.currentTimeMillis();
            }
        }
        // An eat/drink/offensive prayer/gear swap moves onto its target first; if an escape walk came in meanwhile, it
        // goes back in the queue and the walk goes first (a walk waited 631ms behind a prayer click and we stood 3 ticks).
        if (r.interruptible() && r.aim != null) {
            java.awt.Point p = r.aim.get();
            if (p != null && Microbot.naturalMouse != null) Microbot.naturalMouse.moveTo(p.x, p.y);
        }
        // A flee (we're on a hazard) cuts in at any time: an eat at 24 HP went first and the flee walk waited 1.3s.
        if (r.interruptible() && ((tornadoesUp.getAsBoolean() && isPending("walk")) || isFleePending())) {
            requeue(r);
            Microbot.log(String.format("[Click] %s put back - escape walk first", r.label));
            lastClickEnd = System.currentTimeMillis();
            return;
        }
        // A prayer counts as clicked from now: a duplicate queued while this one is on its way must not click it off.
        if (isPrayer(r.kind)) settleUntil(r.key, System.currentTimeMillis() + PRAYER_SETTLE_MS);
        aborted = null;
        r.action.run();
        if (aborted != null) {
            Microbot.log(String.format("[Click] %s %s", r.label, aborted));
            lastClickEnd = System.currentTimeMillis();
            return;
        }
        // Wait until the game took the click before arming the next one (one pending menu action at a time).
        long armedAt = System.currentTimeMillis();
        while (Microbot.targetMenu != null && System.currentTimeMillis() - armedAt < LAND_TIMEOUT_MS) Thread.sleep(5);
        boolean landed = Microbot.targetMenu == null;
        int landedTick = tick.getAsInt();
        if (landed) {
            synchronized (this) {
                this.landedTick.put(r.key, landedTick);
            }
        }
        long end = System.currentTimeMillis();
        if (isPrayer(r.kind)) {
            settleUntil(r.key, end + PRAYER_SETTLE_MS);
            synchronized (this) {
                pending.remove(r.key); // a duplicate that queued up meanwhile
            }
        }
        if (r.kind == Kind.EQUIP) settleUntil(r.key, end + EQUIP_SETTLE_MS);

        if (r.kind != Kind.HOVER) {
            boolean sameTick = landedTick == lastClickTick;
            Microbot.log(String.format("[Click] %s: queued %dms, click %dms, %s tick %d%s", r.label,
                    start - r.queuedAt, end - start, landed ? "landed" : "NOT landed", landedTick,
                    sameTick ? " (same tick as previous click)" : ""));
            if (tornadoesUp.getAsBoolean()) {
                synchronized (this) {
                    phaseClicks++;
                    if (sameTick) phaseSameTick++;
                    if (landedTick > queuedTick) phaseWaitedTick++;
                    if (lastClickEnd > 0) phaseGapSum += start - lastClickEnd;
                }
            }
            lastClickTick = landedTick;
        }
        lastClickEnd = end;
        // Prayers are direct menu actions without a mouse move: only a short pause before the next click.
        Thread.sleep(isPrayer(r.kind) ? Rs2Random.logNormalBounded(20, 50) : humanGap());
    }

    private synchronized void requeue(Request r) {
        if (pending.containsKey(r.key)) return; // a newer decision for the same click is already waiting
        r.queuedAt = System.currentTimeMillis();
        pending.put(r.key, r);
    }

    private static boolean isWalk(Kind k) {
        return k == Kind.WALK || k == Kind.FLEE;
    }

    private static boolean isPrayer(Kind k) {
        return k == Kind.PROTECT_PRAYER || k == Kind.OFFENSIVE_PRAYER;
    }

    private synchronized boolean settling(String key) {
        Long until = settleUntil.get(key);
        return until != null && System.currentTimeMillis() < until;
    }

    private synchronized void settleUntil(String key, long until) {
        settleUntil.put(key, until);
    }

    /**
     * ~110-300ms (mostly ~170) with ~5% slow ones; during tornadoes a focused player's ~80-180ms with ~5% at 250-350.
     * Never the same twice in a row.
     */
    private int humanGap() {
        boolean hurry = tornadoesUp.getAsBoolean();
        // An escape walk came in while this click was on its way (too late to put it back): go straight to it.
        // A walk waited ~200ms behind an offensive-prayer click and we stood 2 ticks between stacked tornadoes.
        if ((hurry && isPending("walk")) || isFleePending()) {
            lastGap = Rs2Random.logNormalBounded(30, 70);
            return lastGap;
        }
        int gap;
        do {
            boolean slow = ThreadLocalRandom.current().nextInt(100) < 5;
            gap = hurry
                    ? (slow ? Rs2Random.logNormalBounded(250, 350) : Rs2Random.logNormalBounded(80, 180))
                    : (slow ? Rs2Random.logNormalBounded(350, 450) : Rs2Random.logNormalBounded(110, 300));
        } while (gap == lastGap);
        lastGap = gap;
        return gap;
    }

    private static Tab currentTab() {
        InterfaceTab t = Rs2Tab.getCurrentTab();
        if (t == InterfaceTab.INVENTORY) return Tab.INVENTORY;
        if (t == InterfaceTab.PRAYER) return Tab.PRAYER;
        return Tab.NONE;
    }

    /** Opens the tab with the player's keybinds (ESC = inventory, F5 = prayer), waiting for it to show. */
    private void openTab(Tab tab) throws InterruptedException {
        if (tab == Tab.NONE || currentTab() == tab) return;
        Rs2Keyboard.keyPress(tab == Tab.PRAYER ? KeyEvent.VK_F5 : KeyEvent.VK_ESCAPE);
        long at = System.currentTimeMillis();
        while (currentTab() != tab && System.currentTimeMillis() - at < 400) Thread.sleep(10);
        if (currentTab() != tab) {
            if (!tabKeyWarned) {
                tabKeyWarned = true;
                Microbot.log("[Click] " + (tab == Tab.PRAYER ? "F5" : "ESC") + " didn't open the " + tab.name().toLowerCase()
                        + " tab - check the keybind; switching it directly instead");
            }
            Rs2Tab.switchTo(tab == Tab.PRAYER ? InterfaceTab.PRAYER : InterfaceTab.INVENTORY);
        }
        Thread.sleep(Rs2Random.logNormalBounded(40, 110)); // eyes on the tab before the click
    }

    /** Summary of the clicks in the tornado phase that just ended, or null if there were none; resets the counters. */
    public synchronized String takePhaseSummary() {
        if (phaseClicks == 0) return null;
        String s = String.format("%d clicks, %d in the same tick as the one before, %d waited a tick, avg gap %dms",
                phaseClicks, phaseSameTick, phaseWaitedTick, phaseClicks > 1 ? phaseGapSum / (phaseClicks - 1) : 0);
        phaseClicks = phaseSameTick = phaseWaitedTick = 0;
        phaseGapSum = 0;
        return s;
    }

    /** Currently waiting clicks, for diagnostics. */
    public synchronized List<String> pendingLabels() {
        List<String> l = new ArrayList<>();
        for (Request r : pending.values()) l.add(r.label);
        return l;
    }
}

package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Builds golems at a plinth, carving each side from the correct tile. For every side we FIRST walk to
 * that side's tile and WAIT until we're standing on it and idle — only then do we click the plinth
 * ({@code Start-golem} for the first side, {@code Shape-golem} after). Clicking mid-walk cancels the
 * walk, so we never click until in position, and we click each side only once (the shaping animation
 * and the "finished this angle" chat gate the next step). The carve order (see
 * {@link GolemHelpers#carveOrder}) ends on the golem's front, so we're already placed to Insert-core.
 */
@Slf4j
public class CraftGolemAction implements GolemAction {

    /** Retry a carve/insert click if this long passes with no progress (a click silently missed). */
    private static final long CARVE_RETRY_MS = 3000;
    /** Safety cap for the spam/perfect per-side carve loop, in case a side never reports finished. */
    private static final long SIDE_CARVE_TIMEOUT_MS = 12000;
    /** How long to wait after finishing a golem to collect its ground drops before starting the next. */
    private static final long LOOT_GRACE_MS = 4000;
    /** Lazy-mode natural AFK between sides: chance (%) and duration bounds (ms). */
    private static final int AFK_CHANCE_PCT = 25;
    private static final int AFK_MIN_MS = 500;
    private static final int AFK_MAX_MS = 2500;

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String key() {
        return "craft";
    }

    @Override
    public boolean needsExecution(GolemState state) {
        return state.context().getPhase() == GolemPhase.CRAFTING;
    }

    @Override
    public Object execute(GolemState state) {
        GolemContext ctx = state.context();

        // Track completions via the core count (Insert-core spends one core + a fur).
        int cores = GolemHelpers.coreCount();
        if (ctx.getLastCoreCount() >= 0 && cores < ctx.getLastCoreCount()) {
            int done = ctx.getLastCoreCount() - cores;
            ctx.setGolemsCompleted(ctx.getGolemsCompleted() + done);
            if (ctx.getFurRemaining() > 0) {
                ctx.setFurRemaining(Math.max(0, ctx.getFurRemaining() - done));
            }
            endGolem(ctx); // that golem is finished — reset per-golem build state
            ctx.setLootDeadlineMs(System.currentTimeMillis() + LOOT_GRACE_MS); // stay to collect drops
        }
        ctx.setLastCoreCount(cores);

        int sunstone = GolemHelpers.sunstoneCount();
        ctx.setStatus("Crafting (" + ctx.getGolemsCompleted() + " done, side " + ctx.getSidesCarved() + "/4)");

        // After finishing a golem, stay put and let LootGemAction (higher priority) collect the ground
        // drops — the gem, and the rare chisel — before we walk off to the next plinth and out of range.
        if (!ctx.isGolemActive() && System.currentTimeMillis() < ctx.getLootDeadlineMs()
                && GolemHelpers.groundLootPresent(ctx.isUseGemBag())) {
            ctx.setStatus("Collecting drops");
            return "awaiting-loot";
        }

        // Start a new golem if we aren't mid-build.
        if (!ctx.isGolemActive()) {
            if (sunstone < GolemConstants.SUNSTONE_PER_GOLEM || cores < 1) {
                // Nothing in progress and no materials — trip complete. Back to mining (reroutes to bank
                // if furs ran out).
                log.info("[golem] batch complete ({} golems this trip) — returning to mining", ctx.getGolemsTarget());
                ctx.setTripActive(false);
                ctx.setLastCoreCount(-1);
                ctx.setPhase(GolemPhase.MINING);
                return "trip-complete";
            }
            // Prefer a different plinth from the one we just finished, so we don't wait for it to reset.
            GameObject empty = GolemHelpers.nearestPlinthWithAction(
                    GolemConstants.ACTION_START, ctx.getLastCompletedPlinth());
            if (empty == null) {
                ctx.setStatus("Waiting for a free plinth");
                return "no-empty-plinth";
            }
            beginGolem(ctx, empty.getWorldLocation());
        }

        WorldPoint plinth = ctx.getCurrentPlinth();
        if (plinth == null) {
            endGolem(ctx);
            return "no-plinth";
        }

        // The tile for the current side (or the front tile once all sides are carved, for Insert-core).
        int sideIdx = Math.min(ctx.getSidesCarved(), 3);
        WorldPoint target = ctx.getCarveOrder()[sideIdx];
        WorldPoint me = Rs2Player.getWorldLocation();

        // Walk to the side tile and WAIT until we're standing on it and idle before clicking.
        if (me == null || !me.equals(target)) {
            if (!Rs2Player.isMoving()) {
                // Click the destination once, then wait for the walk to actually start so the next ticks
                // see us moving and don't re-click (spamming) the same tile.
                Rs2Walker.walkFastCanvas(target);
                sleepUntil(Rs2Player::isMoving, 800);
            }
            return "walk-to-side-" + sideIdx;
        }
        if (Rs2Player.isMoving()) {
            return "walking-to-side";
        }

        // Decide the action from the plinth's live (imposter-resolved) menu and how many sides are done.
        String action;
        if (ctx.getSidesCarved() >= 4) {
            if (cores >= 1 && GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_INSERT)) {
                action = GolemConstants.ACTION_INSERT;
            } else if (GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_START)) {
                // Core inserted and the plinth reset to empty — this golem is finished.
                endGolem(ctx);
                return "golem-finished";
            } else {
                return "insert-settle";
            }
        } else if (GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_START)) {
            action = GolemConstants.ACTION_START; // first side
        } else if (GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_SHAPE)) {
            action = GolemConstants.ACTION_SHAPE; // next side
        } else if (GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_INSERT)) {
            // The plinth is ready for the core but our side counter lagged — catch it up.
            ctx.setSidesCarved(4);
            return "advance-to-insert";
        } else {
            return "plinth-settling";
        }

        // Inserting the core is a single action (not shaping) — click once, never mid-animation.
        if (action.equals(GolemConstants.ACTION_INSERT)) {
            if (Rs2Player.isAnimating()) {
                return "insert-carving";
            }
            return singleClick(ctx, plinth, action);
        }

        // Carving a side: behaviour depends on the crafting mode.
        if (ctx.getCraftingMode() == CraftingMode.LAZY) {
            // One click, then let the game auto-repeat the shaping until the side finishes.
            if (Rs2Player.isAnimating()) {
                return "carving";
            }
            // Occasionally pause a moment before carving a new side, to look more natural.
            maybeAfkBetweenSides(ctx, action);
            return singleClick(ctx, plinth, action);
        }
        return carveSideWithMode(ctx, plinth, ctx.getCraftingMode());
    }

    /**
     * Lazy mode only: on a NEW side (not a retry), occasionally pause a short random moment before
     * clicking, so the switch between sides isn't robotically instant.
     */
    private void maybeAfkBetweenSides(GolemContext ctx, String action) {
        String sig = ctx.getSidesCarved() + ":" + action;
        if (sig.equals(ctx.getLastClickSig())) {
            return; // same step (a retry) — don't add another pause
        }
        if (Rs2Random.between(1, 100) <= AFK_CHANCE_PCT) {
            sleep(Rs2Random.between(AFK_MIN_MS, AFK_MAX_MS));
        }
    }

    /** A single, de-duplicated click for the current (side, action) step; retries only if it missed. */
    private Object singleClick(GolemContext ctx, WorldPoint plinth, String action) {
        String sig = ctx.getSidesCarved() + ":" + action;
        long now = System.currentTimeMillis();
        if (sig.equals(ctx.getLastClickSig()) && now - ctx.getLastCarveMs() < CARVE_RETRY_MS) {
            return "await-progress";
        }
        ctx.setLastClickSig(sig);
        ctx.setLastCarveMs(now);
        Rs2GameObject.interact(plinth, action);
        return action.toLowerCase();
    }

    /**
     * Spam / perfect carving of ONE side, blocking until it finishes. SPAM_CLICK re-clicks the statue
     * every 500-800 ms; PERFECT clicks once, 100-300 ms after each Crafting XP drop. Both turn the 4-tick
     * shaping actions into 3-tick ones. Repositioning to the next side happens on the following tick, once
     * the "finished this angle" message advances the side counter.
     */
    private Object carveSideWithMode(GolemContext ctx, WorldPoint plinth, CraftingMode mode) {
        int sidesBefore = ctx.getSidesCarved();
        clickCarve(plinth); // kick the side off
        long deadline = System.currentTimeMillis() + SIDE_CARVE_TIMEOUT_MS;
        int lastXp = craftingXp();
        while (Microbot.isLoggedIn() && ctx.isGolemActive()
                && ctx.getSidesCarved() == sidesBefore
                && System.currentTimeMillis() < deadline
                && !Thread.currentThread().isInterrupted()
                && !GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_INSERT)) {
            if (mode == CraftingMode.SPAM_CLICK) {
                sleep(Rs2Random.between(500, 800));
                clickCarve(plinth);
            } else { // PERFECT
                int xp = craftingXp();
                if (xp > lastXp) {
                    lastXp = xp;
                    sleep(Rs2Random.between(100, 300));
                    clickCarve(plinth);
                } else {
                    sleep(20);
                }
            }
        }
        return "carve-" + mode;
    }

    private void clickCarve(WorldPoint plinth) {
        String a = currentCarveAction(plinth);
        if (a != null) {
            Rs2GameObject.interact(plinth, a);
        }
    }

    private String currentCarveAction(WorldPoint plinth) {
        if (GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_SHAPE)) {
            return GolemConstants.ACTION_SHAPE;
        }
        if (GolemHelpers.plinthAtHasAction(plinth, GolemConstants.ACTION_START)) {
            return GolemConstants.ACTION_START;
        }
        return null;
    }

    private int craftingXp() {
        return Microbot.getClient().getSkillExperience(Skill.CRAFTING);
    }

    /** Begin a fresh golem on {@code plinth}: lock the tile, compute the carve order, reset counters. */
    private void beginGolem(GolemContext ctx, WorldPoint plinth) {
        ctx.setCurrentPlinth(plinth);
        ctx.setCarveOrder(GolemHelpers.carveOrder(plinth));
        ctx.setSidesCarved(0);
        ctx.setLastClickSig("");
        ctx.setGolemActive(true);
        log.info("[golem] starting golem at {} — carve order {}", plinth, java.util.Arrays.toString(ctx.getCarveOrder()));
    }

    private void endGolem(GolemContext ctx) {
        if (ctx.getCurrentPlinth() != null) {
            ctx.setLastCompletedPlinth(ctx.getCurrentPlinth()); // so the next golem alternates plinths
        }
        ctx.setGolemActive(false);
        ctx.setCurrentPlinth(null);
        ctx.setCarveOrder(null);
        ctx.setSidesCarved(0);
        ctx.setLastClickSig("");
    }
}

package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Mines sunstone until the trip's target is in the inventory. Momentum mining is a tight blocking loop:
 * click a rock, wait (fast 20 ms polling) precisely until its ore lands, then IMMEDIATELY click the next
 * rock in the fixed rotation. Doing the whole phase in one tick avoids the per-poll gap that let momentum
 * decay between rocks.
 */
@Slf4j
public class MineSunstoneAction implements GolemAction {

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String key() {
        return "mine";
    }

    @Override
    public boolean needsExecution(GolemState state) {
        return state.context().getPhase() == GolemPhase.MINING;
    }

    @Override
    public Object execute(GolemState state) {
        GolemContext ctx = state.context();

        // Trip not sized yet: learn the fur count first (Insert-core pulls a fur per golem), then size
        // the batch to furs + inventory, or divert to the bank if the pouch is empty.
        if (!ctx.isTripActive()) {
            if (ctx.getFurRemaining() < 0) {
                if (!ctx.isFurCheckPending()) {
                    ctx.setFurCheckPending(true);
                    net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory.interact(
                            GolemConstants.FUR_POUCH_OPEN, GolemConstants.ACTION_CHECK);
                }
                ctx.setStatus("Checking fur pouch");
                return "await-furcount";
            }
            // Holding both chisels wastes a slot we need for the 25-ore batch — bank the redundant one.
            if (GolemHelpers.hasRedundantChisel()) {
                log.info("[golem] redundant chisel in inventory — banking it before mining");
                ctx.setPhase(GolemPhase.BANKING);
                return "bank-chisel";
            }
            if (ctx.getFurRemaining() == 0) {
                if (ctx.isBankForFurs() && ctx.getEmptyBankCount() < 2) {
                    ctx.setEmptyBankCount(ctx.getEmptyBankCount() + 1);
                    log.info("[golem] fur pouch empty — banking to refill (attempt {})", ctx.getEmptyBankCount());
                    ctx.setPhase(GolemPhase.BANKING);
                } else {
                    log.warn("[golem] no furs available — stopping");
                    ctx.setStatus("Out of furs");
                    ctx.setPhase(GolemPhase.STOPPED);
                }
                return "furs-empty";
            }
            ctx.setEmptyBankCount(0);
            ctx.beginTrip(GolemHelpers.maxGolemsThatFit(), ctx.getFurRemaining(), GolemHelpers.coreCount());
            log.info("[golem] trip start — {} golems, mining {} sunstone (furs: {})",
                    ctx.getGolemsTarget(), ctx.getSunstoneTarget(), ctx.getFurRemaining());
        }

        int target = ctx.getSunstoneTarget();
        if (GolemHelpers.sunstoneCount() >= target) {
            log.info("[golem] mined {} sunstone — moving to chiselling", GolemHelpers.sunstoneCount());
            ctx.setPhase(GolemPhase.CHISELING);
            return "mining-done";
        }

        ctx.setStatus("Mining sunstone (" + GolemHelpers.sunstoneCount() + "/" + target + ")");

        // The monolith option is a simple single-rock loop (one interact per tick).
        if (ctx.isUseMonolith()) {
            if (GolemHelpers.isBusy()) {
                return "mining";
            }
            return Rs2GameObject.interact(GolemConstants.SUNSTONE_MONOLITH, GolemConstants.ACTION_MINE)
                    ? "mine-monolith" : "mine-monolith-fail";
        }

        // Approach the momentum start once, before running the fixed rotation.
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me != null && me.distanceTo(GolemConstants.MINING_ANCHOR) > 8) {
            Rs2Walker.walkTo(GolemConstants.MINING_ANCHOR, 2);
            return "mine-approach";
        }

        // Momentum loop (one tick, blocking). Per rock — a SINGLE click each:
        //   1. click the rock once (interact always invokes the Mine menu action, since nothing is ever
        //      out of the 51-tile range, so the game walks-and-mines automatically — no second click),
        //   2. wait until we're standing next to that rock (so far rocks don't hover a stale spot),
        //   3. hover the NEXT rock (cursor pre-positioned during the mine),
        //   4. wait for the XP drop, then advance to the next rock.
        int len = GolemConstants.ROCK_ROTATION.length;

        while (Microbot.isLoggedIn()
                && ctx.getPhase() == GolemPhase.MINING
                && GolemHelpers.sunstoneCount() < target
                && !Thread.currentThread().isInterrupted()) {

            // Scroll boxes and loose uncut gems eat inventory slots and block reaching the 25-sunstone
            // batch — drop them so mining can keep filling up.
            GolemHelpers.dropMiningJunk();

            final WorldPoint cur = GolemConstants.ROCK_ROTATION[ctx.getRockIndex()];
            int xpBefore = miningXp();

            // 1. One click — walks (if needed) and mines.
            Rs2GameObject.interact(cur, GolemConstants.ACTION_MINE);
            ctx.setLastRock(cur);

            // 2. Wait until we've arrived next to this rock and stopped moving.
            sleepUntil(() -> {
                WorldPoint p = Rs2Player.getWorldLocation();
                return p != null && !Rs2Player.isMoving() && p.distanceTo(cur) <= 1;
            }, () -> {
            }, 6000, 20);

            // 3. Hover the next rock while this one is being mined.
            WorldPoint nextTile = GolemConstants.ROCK_ROTATION[(ctx.getRockIndex() + 1) % len];
            net.runelite.api.TileObject nextRock = GolemHelpers.rockAt(nextTile);
            if (nextRock != null) {
                Rs2GameObject.hoverOverObject(nextRock);
            }

            // 4. Wait for this rock's XP drop (a sunstone), then advance. The cap only fires on an empty
            //    rock or a missed swing, so we never dwell.
            sleepUntil(() -> miningXp() > xpBefore, () -> {
            }, 6000, 20);

            ctx.setRockIndex((ctx.getRockIndex() + 1) % len);
            ctx.setStatus("Mining sunstone (" + GolemHelpers.sunstoneCount() + "/" + target + ")");
        }

        if (GolemHelpers.sunstoneCount() >= target) {
            log.info("[golem] mined {} sunstone — moving to chiselling", GolemHelpers.sunstoneCount());
            ctx.setPhase(GolemPhase.CHISELING);
        }
        return "mining-loop";
    }

    private int miningXp() {
        return Microbot.getClient().getSkillExperience(net.runelite.api.Skill.MINING);
    }
}

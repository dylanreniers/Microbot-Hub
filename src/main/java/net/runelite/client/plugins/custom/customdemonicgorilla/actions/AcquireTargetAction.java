package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.HeadIcon;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaConfig;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * FIGHTING phase: when there is no live target, top up food/prayer (once per kill), tally the kill,
 * acquire a fresh gorilla and switch gear to counter its overhead. Corresponds to the old
 * {@code handleNewTarget}, gated to run only when the current target is missing or dead.
 */
@Slf4j
public class AcquireTargetAction implements GorillaAction {

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String key() {
        return "acquire-target";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        GorillaContext ctx = state.context();
        return ctx.getBotStatus() == State.FIGHTING
                && (ctx.getCurrentTarget() == null || ctx.getCurrentTarget().getNpc().isDead());
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        CustomDemonicGorillaConfig config = state.config();

        ctx.setNpcAnimationCount(0);
        GorillaHelpers.logOnce(ctx, "Target is null or dead");

        if (!ctx.isLootAttempted()) {
            Rs2Player.eatAt(80);
            Rs2Player.drinkPrayerPotionAt(config.minEatPercent());
            ctx.setLootAttempted(true);
            if (ctx.getCurrentTarget() != null && ctx.getCurrentTarget().getNpc().isDead()) {
                ctx.setKillCount(ctx.getKillCount() + 1);
                ctx.setCurrentTripKillCount(ctx.getCurrentTripKillCount() + 1);
            }
            ctx.setCurrentTarget(null);
            ctx.setLastGorillaLocation(null);
            // Arm the loot wait: don't engage the next gorilla until looting is finished. The drop needs
            // a moment to appear; the looter then extends the deadline on each pickup (see the looter).
            ctx.setAwaitingLoot(true);
            ctx.setLootDeadlineMs(System.currentTimeMillis() + GorillaContext.INITIAL_LOOT_GRACE_MS);
        }

        // Hold until ALL looting is done before acquiring/attacking the next gorilla. The looter runs on
        // its own loop meanwhile and pushes the deadline out per pickup; we proceed only once it lapses.
        if (ctx.isAwaitingLoot()) {
            if (System.currentTimeMillis() < ctx.getLootDeadlineMs()) {
                GorillaHelpers.logOnce(ctx, "Waiting for looting to finish before next target.");
                return null;
            }
            ctx.setAwaitingLoot(false);
        }

        Rs2NpcModel target = GorillaHelpers.getTarget(ctx);
        ctx.setCurrentTarget(target);
        if (target != null) {
            HeadIcon overhead;
            try {
                overhead = target.getHeadIcon();
                if (overhead == null) {
                    GorillaHelpers.logOnce(ctx, "Failed to retrieve HeadIcon for target - NULL");
                    return null;
                }
            } catch (Exception e) {
                GorillaHelpers.logOnce(ctx, "Failed to retrieve HeadIcon for target - Exception");
                return null;
            }
            ctx.setCurrentOverheadIcon(overhead);
            GorillaHelpers.switchGear(ctx, config, overhead);
            ctx.setLootAttempted(false);
            return target;
        }
        GorillaHelpers.logOnce(ctx, "No target found for attack.");
        return null;
    }
}

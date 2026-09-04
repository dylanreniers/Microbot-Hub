package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.time.Instant;

import static net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers.GORILLA_LOCATION;
import static net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers.GORILLA_NAME;

/**
 * FIGHTING phase: keeps the selected target consistent with who we're actually attacking, and if
 * we've been out of combat for 6s forces a new target (walking back to the cave and clicking any
 * gorilla as a last resort). Corresponds to the old {@code handleTargetSelection}.
 */
@Slf4j
public class TargetSelectionAction implements GorillaAction {

    @Override
    public int order() {
        return 400;
    }

    @Override
    public String key() {
        return "target-selection";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        // Not while we're waiting on loot — its out-of-combat force-acquire would engage the next
        // gorilla before looting is finished.
        return state.context().getBotStatus() == State.FIGHTING && !state.context().isAwaitingLoot();
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();

        if (ctx.getCurrentTarget() != null) {
            Rs2NpcModel tempTarget = GorillaHelpers.getTarget(ctx, true);
            if ((tempTarget != null && tempTarget.getIndex() != ctx.getCurrentTarget().getIndex())
                    || ctx.getCurrentTarget().getNpc().isDead()) {
                GorillaHelpers.logOnce(ctx, "Invalid target was selected, switching to correct enemy");
                ctx.setCurrentTarget(tempTarget);
            }
        }

        if (!Rs2Player.isInCombat()) {
            if (ctx.getOutOfCombatTime() == null) {
                ctx.setOutOfCombatTime(Instant.now());
            } else if (Instant.now().isAfter(ctx.getOutOfCombatTime().plusSeconds(6))) {
                GorillaHelpers.logOnce(ctx, "Out of combat for 6 seconds, forcing new target");
                ctx.setCurrentTarget(GorillaHelpers.getTarget(ctx, true));
                if (ctx.getCurrentTarget() != null) {
                    ctx.getCurrentTarget().click("Attack");
                } else {
                    GorillaHelpers.logOnce(ctx, "Unable to force new target, walking to gorillas and trying again");
                    Rs2Walker.walkTo(GORILLA_LOCATION);
                    ctx.setCurrentTarget(GorillaHelpers.getTarget(ctx, true));
                    if (ctx.getCurrentTarget() == null) {
                        Microbot.getClientThread().invoke(() ->
                                Microbot.getRs2NpcCache().query().withName(GORILLA_NAME).interact("Attack"));
                    }
                }
                ctx.setOutOfCombatTime(null);
            }
        } else {
            ctx.setOutOfCombatTime(null);
        }
        return ctx.getCurrentTarget();
    }
}

package net.runelite.client.plugins.custom.zulrah.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Non-melee phases: keep on the safespot and attack; when repositioning, attack the moment the
 * weapon is off cooldown and Zulrah is in range (the cooldown ticks are free for movement),
 * otherwise walk. Stands down while gear is swapping.
 */
@Slf4j
public class RepositionAttackAction implements ZulrahAction {

    @Override
    public int order() {
        return 800;
    }

    @Override
    public String key() {
        return "reposition-attack";
    }

    @Override
    public boolean needsExecution(ZulrahState state) {
        FightContext ctx = state.context();
        return !ctx.isMeleeDodgePhase() && ctx.getStandLocation() != null && !state.executed(EquipGearAction.KEY);
    }

    @Override
    public Object execute(ZulrahState state) {
        FightContext ctx = state.context();
        WorldPoint target = ctx.getStandLocation();

        final long now = System.currentTimeMillis();
        final long cooldownMs = ZulrahHelpers.attackCooldownMs(ctx);
        // lastAttackAtMs == 0 means we haven't attacked yet: treat as exactly off cooldown (ready)
        // rather than "epoch 0", which produced the nonsensical multi-billion-ms log/timer values.
        final boolean everAttacked = ctx.getLastAttackAtMs() != 0L;
        final long sinceLastMs = everAttacked ? now - ctx.getLastAttackAtMs() : cooldownMs;
        final boolean offCooldown = sinceLastMs >= cooldownMs;

        // Arrived on (or as close as we can get to) the stand tile: attack in place and keep the
        // cooldown timer in sync with the game's cadence. atTargetTile() tolerates stopping one tile
        // short so we don't loop forever walking if we can't stand exactly on the tile.
        if (ZulrahHelpers.atTargetTile(ctx)) {
            if (!ZulrahHelpers.isInteractingWithZulrah()) {
                ZulrahHelpers.clickNearestZulrah();
            }
            if (ZulrahHelpers.isInteractingWithZulrah() && offCooldown) {
                ctx.setLastAttackAtMs(now);
            }
            return "attack-safespot";
        }

        // Repositioning: attack the moment the weapon is off cooldown and Zulrah is in range (the
        // cooldown ticks are free for movement), otherwise keep walking.
        if (offCooldown && ZulrahHelpers.zulrahInRange()) {
            log.info("[dps] attacking Zulrah mid-reposition to {} ({}ms since last attack)", target, sinceLastMs);
            ZulrahHelpers.clickNearestZulrah();
            ctx.setLastAttackAtMs(now);
            return "attack-moving";
        }
        if (!Rs2Player.isMoving()) {
            log.info("[dps] walking to {} | {}ms since last attack, {}ms until next attack",
                    target, sinceLastMs, Math.max(0L, cooldownMs - sinceLastMs));
            Rs2Walker.walkFastCanvas(target, true);
            return "walk";
        }
        return "moving";
    }
}

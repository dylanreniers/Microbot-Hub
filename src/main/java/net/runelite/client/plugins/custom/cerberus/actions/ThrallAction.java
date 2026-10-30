package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.magic.thralls.Rs2Thrall;

/**
 * Keeps a magic (ghost) thrall up during the fight: the strongest tier we can cast (Greater > Superior >
 * Lesser; {@link Rs2Thrall#getBestThrall} would pick the lesser one first). Re-summoned whenever none is
 * active or cooling down. With the no-ghosts strategy it waits until attack 14 — before that we hold her
 * just above 400 HP and the thrall's damage would push her under.
 */
@Slf4j
public class ThrallAction implements CerberusAction {

    private static final Rs2Thrall[] GHOSTS_STRONGEST_FIRST = {
            Rs2Thrall.GREATER_GHOST, Rs2Thrall.SUPERIOR_GHOST, Rs2Thrall.LESSER_GHOST
    };
    /** After an attempt, wait this long before trying again (the cast must register; no-runes spam). */
    private static final int RETRY_TICKS = 10;

    private int lastAttemptTick = -100;

    @Override
    public int order() {
        return 460;
    }

    @Override
    public String key() {
        return "thrall";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        CerberusContext ctx = state.context();
        if (!state.config().summonThrall() || ctx.getPhase() != CerberusContext.Phase.FIGHT || !ctx.isFightActive()
                || ctx.isPrayerCritical() || ctx.isHoldingDamage()
                || Microbot.getClient().getTickCount() - lastAttemptTick < RETRY_TICKS) {
            return false;
        }
        // No-ghosts only runs with a synced counter; then hold the thrall until attack 14 has passed.
        boolean waitForAttack14 = state.config().noGhosts() && ctx.isCounterSynced()
                && ctx.getAttackCount() < CerberusConstants.NO_GHOSTS_SAFE_ATTACK;
        return !waitForAttack14 && !Rs2Thrall.isActive();
    }

    @Override
    public Object execute(CerberusState state) {
        lastAttemptTick = Microbot.getClient().getTickCount();
        for (Rs2Thrall ghost : GHOSTS_STRONGEST_FIRST) {
            if (Rs2Thrall.canCast(ghost)) {
                if (!state.claimClick()) {
                    return null;
                }
                boolean cast = Rs2Thrall.cast(ghost);
                log.info("[cerberus] summoning {} (attack #{}) -> {}", ghost, state.context().getAttackCount(), cast);
                return cast;
            }
        }
        log.warn("[cerberus] can't summon a ghost thrall (Arceuus spellbook, Magic level and runes?)");
        return false;
    }
}

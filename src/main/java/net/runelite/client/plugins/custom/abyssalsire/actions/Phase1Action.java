package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Phase 1: from the original spot, cast Shadow Barrage on the Sire to disorient it (stunning the
 * tentacles). The disorient lasts 46 game ticks (~27.6s) — tracked from the "...disorientated
 * temporarily." chat message ({@code sireStunnedUntilMs}) rather than guessed from tentacle state.
 * While stunned we range down the respiratory systems; ~{@link #REBARRAGE_LEAD_MS} before it expires
 * we commit (latched) to walking back and re-barraging so it never lapses. Ends when the systems are
 * cleared (the script flips to phase 2 via count or the 5886 change).
 */
@Slf4j
public class Phase1Action implements SireAction {

    /** Re-barrage once the 46-tick disorient has this little left, leaving time to walk back + cast +
     *  let the projectile land so the stun refreshes before the tentacles wake. Tunable. */
    private static final long REBARRAGE_LEAD_MS = 2000;
    /** After a cast, don't re-commit to another barrage for this long — gives the disorient message time
     *  to land and refresh the stun, so the still-low OLD stun can't trigger an immediate second cast. */
    private static final long POST_CAST_GRACE_MS = 4000;
    /** If no "disorientated" message arrives this long after a cast, treat it as a miss and re-cast. */
    private static final long CAST_MISS_TIMEOUT_MS = 7000;

    /** Throttles the per-second tentacle diagnostic log. */
    private long lastTentacleLogMs;

    @Override
    public int order() {
        return 600;
    }

    @Override
    public String key() {
        return "phase1";
    }

    @Override
    public boolean needsExecution(SireState state) {
        return state.context().getPhase() == SirePhase.PHASE1;
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();

        // Hold combat until the range gear is on (GearSwitchAction equips it on this same tick).
        if (!SireHelpers.gearReady(ctx)) {
            return "gear-wait";
        }

        // All respiratory systems down: stop phase-1 combat NOW. Re-barraging here would re-stun the
        // Sire to 5888 and block the 5886 phase-2 tell (the exact stuck-in-phase-1 bug). Hold at the
        // original spot; the script advances us to phase 2 (advanceToPhase2IfRespiratoryCleared).
        if (ctx.isFightStarted() && livingRespiratoryCount() == 0) {
            SireHelpers.walkTo(SireConstants.ORIGINAL_POSITION);
            return "respiratory-clear";
        }

        // Ate/drank this tick: don't fire a barrage/walk/vent click that would collide with it. The
        // vent attack re-issues on its own next tick once the (broken) interaction clears.
        if (SireHelpers.consumedThisTick(state)) {
            return "consumed-hold";
        }

        final long now = System.currentTimeMillis();
        final long stunRemainingMs = Math.max(0, ctx.getSireStunnedUntilMs() - now);
        final boolean stunned = stunRemainingMs > 0;
        tentaclesActive(); // diagnostic log only; the 46-tick stun timer drives re-barrage timing now

        final boolean hasCast = ctx.getBarrageCastAtMs() != 0;
        final long sinceCastMs = hasCast ? now - ctx.getBarrageCastAtMs() : Long.MAX_VALUE;
        // A cast is confirmed by its disorient MESSAGE (which sets the stun timer, {@code stunned}) — the
        // message can even land DURING the blocking cast, so we gate purely on the stun timer plus a
        // post-cast grace, with NO separate "awaiting" flag a mid-cast message could clobber (that clobber
        // was the double-barrage at fight start).
        //   - initial: never cast yet.
        //   - stunExpiring: stunned and about to wear off — refresh it (but wait out the grace so the
        //     still-low OLD stun during the cast->message window doesn't fire a second cast).
        //   - castMissed: cast, but no disorient landed within the timeout — retry.
        final boolean castMissed = hasCast && !stunned && sinceCastMs >= CAST_MISS_TIMEOUT_MS;
        final boolean stunExpiring = stunned && stunRemainingMs <= REBARRAGE_LEAD_MS
                && sinceCastMs >= POST_CAST_GRACE_MS;

        if (!ctx.isRebarragePending() && (!hasCast || castMissed || stunExpiring)) {
            if (hasCast) {
                log.info("[sire] committing to a re-barrage (stun {}ms left, missed={})", stunRemainingMs, castMissed);
            }
            ctx.setRebarragePending(true);
        }

        if (ctx.isRebarragePending()) {
            if (!SireHelpers.atTile(SireConstants.ORIGINAL_POSITION)) {
                SireHelpers.walkTo(SireConstants.ORIGINAL_POSITION);
                return "walk-to-barrage";
            }
            // On the barrage spot — find the Sire and cast. The "disorientated" chat message
            // (onSireStunned) confirms it and sets the stun timer.
            if (castBarrageOnSire()) {
                ctx.setBarrageCastAtMs(now);
                // The fight has definitely started now — don't rely solely on catching a 5887/5888
                // event, so the later "change to 5886" is reliably read as the phase-2 tell.
                ctx.setFightStarted(true);
                ctx.setRebarragePending(false);
                log.info("[sire] cast Shadow Barrage — waiting for the disorient message");
                return "barrage";
            }
            return "barrage-failed";
        }

        // Cast, but the disorient hasn't landed yet — hold; don't range while the tentacles could be up.
        if (!stunned) {
            return "await-stun";
        }

        // Stunned: range the respiratory systems still up (the all-dead case is handled at the top).
        // Only (re)issue the attack when idle — not already walking to / fighting one — otherwise we
        // spam-click every tick while running toward it.
        if (isAttackingRespiratory() || Rs2Player.isMoving()) {
            return "engaging-respiratory";
        }
        attackNearestRespiratory();
        return "attack-respiratory";
    }

    /** True if we're already interacting with a respiratory system (so we shouldn't re-click it). */
    private boolean isAttackingRespiratory() {
        Actor interacting = Rs2Player.getInteracting();
        return interacting instanceof NPC && ((NPC) interacting).getId() == SireConstants.RESPIRATORY_SYSTEM_ID;
    }

    private int livingRespiratoryCount() {
        Integer count = Microbot.getClientThread().invoke(
                () -> Microbot.getRs2NpcCache().query().withId(SireConstants.RESPIRATORY_SYSTEM_ID).count());
        return count == null ? 0 : count;
    }

    /**
     * Cast Shadow Barrage on the Sire (stuns the tentacles). Returns true once the cast is issued.
     *
     * <p>{@code Rs2Magic.castOn} is broken for cache NPCs — it casts the {@code Rs2NpcModel} to
     * {@code NPC} (which it isn't) and throws — so we do it by hand: select the spell, then interact
     * with the Sire. With a spell selected, the NPC interact uses WIDGET_TARGET_ON_NPC, which casts
     * the held spell on the target.
     */
    private boolean castBarrageOnSire() {
        Rs2NpcModel sire = Microbot.getClientThread().invoke(
                () -> Microbot.getRs2NpcCache().query().withName(SireConstants.SIRE_NAME).nearest());
        if (sire == null) {
            log.info("[sire] barrage: no Sire found");
            return false;
        }
        if (!Rs2Magic.cast(MagicAction.SHADOW_BARRAGE)) {
            log.info("[sire] barrage: cannot cast Shadow Barrage — check Ancient spellbook + runes");
            return false;
        }
        if (!Global.sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1500)) {
            log.info("[sire] barrage: spell was not selected");
            return false;
        }
        Boolean cast = Microbot.getClientThread().invoke((Supplier<Boolean>)
                () -> Microbot.getRs2NpcCache().query().withName(SireConstants.SIRE_NAME).interact("Attack"));
        return Boolean.TRUE.equals(cast);
    }

    /** Attack the nearest living respiratory system (auto-walks into range). */
    private void attackNearestRespiratory() {
        Microbot.getClientThread().invoke((Supplier<Boolean>)
                () -> Microbot.getRs2NpcCache().query()
                        .withId(SireConstants.RESPIRATORY_SYSTEM_ID)
                        .interact("Attack"));
    }

    /** True if any tentacle is mid-animation (awake and dangerous); false once all are stunned (-1).
     *  Logs presence/animation once per second so we can see whether the tentacles are being detected. */
    private boolean tentaclesActive() {
        List<Rs2NpcModel> tentacles = Microbot.getClientThread().invoke((Supplier<List<Rs2NpcModel>>) () ->
                Microbot.getRs2NpcCache().query().within(25).withName(SireConstants.TENTACLE_NAME).toListOnClientThread());
        if (tentacles == null) {
            tentacles = Collections.emptyList();
        }
        long animating = tentacles.stream().filter(n -> n.getAnimation() != -1).count();
        long now = System.currentTimeMillis();
        if (now - lastTentacleLogMs > 1000) {
            lastTentacleLogMs = now;
            log.info("[sire] tentacles: present={}, animating={}", tentacles.size(), animating);
        }
        return animating > 0;
    }
}

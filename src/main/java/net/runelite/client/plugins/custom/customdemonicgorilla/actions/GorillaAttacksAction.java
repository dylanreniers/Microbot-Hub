package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.ArmorEquiped;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.AttackStyle;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import static net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers.DEMONIC_GORILLA_MAGIC_ATTACK;
import static net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers.DEMONIC_GORILLA_MELEE_ATTACK;
import static net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers.DEMONIC_GORILLA_RANGED_ATTACK;

/**
 * FIGHTING phase: reacts to the current gorilla's attack animation — flicks the matching overhead
 * protection prayer, re-evaluates gear on the gorilla's prayer-switch animation, dodges the AOE
 * boulder to a safe tile, and steps out of melee range while using range/magic. Corresponds to the
 * old {@code handleDemonicGorillaAttacks}.
 */
@Slf4j
public class GorillaAttacksAction implements GorillaAction {

    @Override
    public int order() {
        return 600;
    }

    @Override
    public String key() {
        return "gorilla-attacks";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        GorillaContext ctx = state.context();
        return ctx.getBotStatus() == State.FIGHTING
                && ctx.getCurrentTarget() != null
                && !ctx.getCurrentTarget().getNpc().isDead();
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        Rs2NpcModel target = ctx.getCurrentTarget();

        boolean movedThisTick = false;

        // Raw NPC.getAnimation() must be read on the client thread.
        int currentAnimation = Microbot.getClientThread().invoke(() -> target.getNpc().getAnimation());

        final boolean weRanging = ctx.getCurrentGear() == ArmorEquiped.RANGED
                || ctx.getCurrentGear() == ArmorEquiped.MAGIC;
        // "It moves to you" == it comes into melee distance. Range/magic gorillas stand still; a melee
        // one walks up to us. This proximity read is the reliable melee tell (movement-delta was not).
        final boolean gorillaInMeleeDistance = Microbot.getClientThread().runOnClientThreadOptional(() ->
                        target.getNpc().getWorldArea().isInMeleeDistance(Microbot.getClient().getLocalPlayer().getWorldArea()))
                .orElse(false);

        Rs2PrayerEnum newDefensivePrayer = null;
        AttackStyle observed = styleFromAnimation(currentAnimation);

        if (observed != null) {
            // Ground-truth backup (the plugin's AnimationChanged fail-check is the primary, faster path):
            // an actual attack animation reveals the exact style — confirm it and pray to match.
            ctx.setCurrentAttackStyle(observed);
            ctx.setAwaitingStyleSwitch(false);
            ctx.setStyleSwitchCryPending(false);
            newDefensivePrayer = prayerFor(observed);
        } else {
            // Consume the "Rhaaaa" cry: step ~2 tiles away (opens the gap so a melee gorilla has to close)
            // and arm the awaiting window.
            if (ctx.isStyleSwitchCryPending()) {
                ctx.setStyleSwitchCryPending(false);
                ctx.setPreviousAttackStyle(ctx.getCurrentAttackStyle());
                ctx.setAwaitingStyleSwitch(true);
                if (!weRanging || gorillaInMeleeDistance) {
                    movedThisTick = GorillaHelpers.moveAwayFromTarget(ctx);
                }
            }
            // Only pre-pray while the gorilla is at RANGE — there it can't melee, so the deterministic
            // magic<->range prediction is safe. When we're ADJACENT (meleeing it) we do NOT pre-pray:
            // the gorilla still ranges/mages point-blank where the projectile lands almost instantly, so
            // guessing melee would eat those and cause a melee/range flip-flop. There, protection is
            // purely reactive via the fail-check on the actual attack animation.
            if (ctx.isAwaitingStyleSwitch() && !gorillaInMeleeDistance) {
                newDefensivePrayer = predictedPrayerOnStay(ctx.getPreviousAttackStyle());
            }
        }

        // Re-pray whenever the needed overhead isn't ACTUALLY active — not merely when it differs from
        // the tracked value. Otherwise, if prayer got disabled (points ran out, then a restore is drunk)
        // while the context still thinks it's on, we'd never turn it back on for the rest of the fight.
        if (newDefensivePrayer != null && !Rs2Prayer.isPrayerActive(newDefensivePrayer)) {
            GorillaHelpers.switchDefensivePrayer(ctx, newDefensivePrayer);
        }

        // While ranging, keep a gap so we're never in melee range (avoids melee hits and keeps the
        // follow/stay tell readable). Don't fight the cry step-out we already did this tick. (The boulder
        // dodge is handled by BoulderDodgeAction, which runs earlier and independently of the target.)
        if (!movedThisTick && weRanging && gorillaInMeleeDistance
                && currentAnimation != DEMONIC_GORILLA_MELEE_ATTACK
                && ctx.getCurrentDefensivePrayer() != Rs2PrayerEnum.PROTECT_MELEE) {
            GorillaHelpers.moveAwayFromTarget(ctx);
        }

        if (currentAnimation != -1) {
            ctx.setLastAttackAnimation(currentAnimation);
        }
        ctx.setLastAnimation(currentAnimation);
        ctx.setLastGorillaLocation(target.getWorldLocation());
        ctx.setLastGameTick(ctx.getGameTickCount());
        return currentAnimation;
    }

    /** The confirmed style behind an attack animation, or null if this isn't an attack animation. */
    private static AttackStyle styleFromAnimation(int animation) {
        if (animation == DEMONIC_GORILLA_MAGIC_ATTACK) return AttackStyle.MAGIC;
        if (animation == DEMONIC_GORILLA_RANGED_ATTACK) return AttackStyle.RANGED;
        if (animation == DEMONIC_GORILLA_MELEE_ATTACK) return AttackStyle.MELEE;
        return null;
    }

    private static Rs2PrayerEnum prayerFor(AttackStyle style) {
        switch (style) {
            case MAGIC:
                return Rs2PrayerEnum.PROTECT_MAGIC;
            case RANGED:
                return Rs2PrayerEnum.PROTECT_RANGE;
            case MELEE:
                return Rs2PrayerEnum.PROTECT_MELEE;
            default:
                return null;
        }
    }

    /**
     * The prayer to pre-set on a style switch assuming the gorilla STAYS in place (i.e. it went
     * ranged/magic, not melee). The gorilla never repeats its previous style, so:
     * magic → range, ranged → magic. After melee (or an unknown opener) both range and magic are
     * possible and only the first hit confirms which — we guess magic, the more accurate protection.
     */
    private static Rs2PrayerEnum predictedPrayerOnStay(AttackStyle previous) {
        if (previous == AttackStyle.MAGIC) {
            return Rs2PrayerEnum.PROTECT_RANGE;
        }
        // RANGED, MELEE and UNKNOWN all fall back to magic.
        return Rs2PrayerEnum.PROTECT_MAGIC;
    }
}

package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.function.Supplier;

/**
 * The wiki's no-ghosts strategy. Souls only come on attacks 7, 14 and 28 (21 is the triple), and only
 * while she's below 400 HP — so keeping her at/above 400 until her 14th attack means no souls before 28,
 * by which point she's usually dead.
 * <ul>
 *   <li><b>Auto-retaliate</b>: off in the lair until attack 14 (the trip turns it off on entering, and it's
 *       kept off between kills), back on from attack 14 until the kill.</li>
 *   <li><b>Hold</b>: before attack 14, once her HP is at/below 400 + margin, stop attacking and step out of
 *       melee range (prayers keep running).</li>
 *   <li><b>Resume</b>: from attack 14; {@link AttackAction} re-engages.</li>
 *   <li><b>Reset</b>: below 400 before attack 14 -> phase RESETTING ({@link NoGhostsResetAction}).</li>
 * </ul>
 * Only with a synced attack counter (we saw her stand up), and clicks only outside flick windows.
 */
@Slf4j
public class NoGhostsAction implements CerberusAction {

    @Override
    public int order() {
        return 420;
    }

    @Override
    public String key() {
        return "no-ghosts";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        CerberusContext ctx = state.context();
        return state.config().noGhosts() && ctx.getPhase() == CerberusContext.Phase.FIGHT;
    }

    @Override
    public Object execute(CerberusState state) {
        CerberusContext ctx = state.context();
        if (!ctx.isFightActive()) {
            // In the lair before she stands up (after the trip, a reset or a kill): auto-retaliate stays off.
            setRetaliate(state, false);
            return "pre-fight";
        }
        if (!ctx.isCounterSynced()) {
            return "unsynced"; // can't count to 14 — fight normally (AttackAction attacks regardless)
        }
        if (ctx.getAttackCount() >= CerberusConstants.NO_GHOSTS_SAFE_ATTACK) {
            if (ctx.isHoldingDamage()) {
                log.info("[cerberus] no-ghosts: attack #{} reached — resuming damage", ctx.getAttackCount());
                ctx.setHoldingDamage(false);
            }
            setRetaliate(state, true);
            return "resume";
        }

        int hp = ctx.getBossHp();
        if (hp < 0) {
            return "no-hp-bar"; // not hit yet
        }
        if (hp < CerberusConstants.SOULS_HP_THRESHOLD) {
            log.info("[cerberus] no-ghosts: she's at ~{} HP before attack {} (now #{}) — resetting over the flames",
                    hp, CerberusConstants.NO_GHOSTS_SAFE_ATTACK, ctx.getAttackCount());
            ctx.setHoldingDamage(false);
            ctx.setPhase(CerberusContext.Phase.RESETTING);
            return "reset";
        }
        if (hp <= CerberusConstants.SOULS_HP_THRESHOLD + state.config().noGhostsHoldMargin() && !ctx.isHoldingDamage()) {
            log.info("[cerberus] no-ghosts: holding at ~{} HP until attack #{} (now #{})",
                    hp, CerberusConstants.NO_GHOSTS_SAFE_ATTACK, ctx.getAttackCount());
            ctx.setHoldingDamage(true);
        }
        if (!ctx.isHoldingDamage() || ctx.isPrayerCritical()) {
            return "fight";
        }

        // Holding: retaliate must be off (or her next hit makes us swing back), then step out of melee range.
        if (isAutoRetaliateOn()) {
            setRetaliate(state, false);
            return "retaliate-off";
        }
        if (ctx.isEngaged() && !Rs2Player.isMoving() && state.claimClick()) {
            WorldPoint away = stepBackTile(ctx);
            if (away != null) {
                Rs2Walker.walkFastCanvas(away);
                return away;
            }
        }
        return "holding";
    }

    /** Switch auto-retaliate if it isn't already, outside flick windows (it's a tab switch + click). */
    private void setRetaliate(CerberusState state, boolean on) {
        CerberusContext ctx = state.context();
        if (isAutoRetaliateOn() == on || ctx.isPrayerCritical() || !state.claimClick()) {
            return;
        }
        if (Rs2Combat.setAutoRetaliate(on)) {
            ctx.setRetaliateDisabledByUs(!on);
            log.info("[cerberus] no-ghosts: auto-retaliate {}", on ? "on" : "off");
        }
    }

    private boolean isAutoRetaliateOn() {
        return Microbot.getVarbitPlayerValue(VarPlayerID.OPTION_NODEF) == 0;
    }

    /** A couple of tiles further out on our side of her (east/west), out of melee range. */
    private WorldPoint stepBackTile(CerberusContext ctx) {
        WorldPoint center = Microbot.getClientThread().invoke((Supplier<WorldPoint>) () -> {
            Rs2NpcModel boss = Microbot.getRs2NpcCache().query()
                    .withId(CerberusConstants.CERBERUS_ATTACKABLE_ID).nearest(CerberusConstants.IN_LAIR_RADIUS);
            LocalPoint lp = boss == null ? null : boss.getLocalLocation();
            return lp == null ? null : WorldPoint.fromLocal(Microbot.getClient(), lp);
        });
        WorldPoint player = Rs2Player.getWorldLocation();
        if (center == null || player == null) {
            return null;
        }
        int side = ctx.getSideOffset() != null ? Integer.signum(ctx.getSideOffset())
                : (player.getX() >= center.getX() ? 1 : -1);
        return center.dx(side * (CerberusConstants.SIDE_SPOT_OFFSET + CerberusConstants.HOLD_STEP_BACK_TILES));
    }
}

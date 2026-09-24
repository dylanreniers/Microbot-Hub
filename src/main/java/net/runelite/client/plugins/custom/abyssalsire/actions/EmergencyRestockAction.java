package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

/**
 * Out-of-food safety net: if we're mid-fight with NO food left and our HP drops to/below
 * {@value #EMERGENCY_HP_PERCENT}%, teleport home immediately and arm the restock trip so
 * {@link ReturnToSireAction} resupplies (and returns) for the next kill.
 *
 * <p>Runs at the lowest order so its house teleport is the first (and only) menu action that tick —
 * nothing else can steal the click, so the escape is clean. Once home it sets {@code prepPending}, which
 * hands the rest of the trip (GE resupply -> pool -> fairy ring -> walk back -> reset) to
 * {@link ReturnToSireAction}. The combat/phase actions defer while {@code prepPending} is set.
 *
 * <p>Does NOT flee when the Sire is nearly dead ({@value #SIRE_FINISH_HP_PERCENT}% or less) — finishing
 * the kill (and looting) is safer and worth more than bailing a hit early; the normal post-kill restock
 * then resupplies. Without this, running out of food at the end of a kill flees before the drop, which
 * looked like "looting is skipped".
 */
@Slf4j
public class EmergencyRestockAction implements SireAction {

    private static final int EMERGENCY_HP_PERCENT = 25;
    /** If the Sire is at/below this HP%, finish the kill instead of fleeing. */
    private static final int SIRE_FINISH_HP_PERCENT = 20;

    @Override
    public int order() {
        return 10; // before every other action — escaping takes priority over everything
    }

    @Override
    public String key() {
        return "emergency-restock";
    }

    @Override
    public boolean needsExecution(SireState state) {
        SireContext ctx = state.context();
        if (ctx.isPrepPending()) {
            return false; // a restock trip is already armed / running
        }
        SirePhase phase = ctx.getPhase();
        boolean inFight = phase == SirePhase.PHASE1 || phase == SirePhase.PHASE2 || phase == SirePhase.PHASE3;
        // Cheap checks first; only query the Sire's HP if we'd otherwise flee, and don't flee if it's
        // nearly dead — finish the kill and loot instead.
        return inFight
                && Rs2Inventory.getInventoryFood().isEmpty()
                && Rs2Player.getHealthPercentage() <= EMERGENCY_HP_PERCENT
                && !sireNearlyDead();
    }

    /** True if the Sire is at/below the finish threshold (so we should finish the kill, not flee). */
    private boolean sireNearlyDead() {
        Rs2NpcModel sire = Microbot.getRs2NpcCache().query()
                .withName(SireConstants.SIRE_NAME).nearestOnClientThread();
        if (sire == null) {
            return false;
        }
        double hp = sire.getHealthPercentage();
        return hp >= 0 && hp <= SIRE_FINISH_HP_PERCENT;
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();
        log.warn("[sire] EMERGENCY: out of food at {}% HP — teleporting home to resupply",
                Rs2Player.getHealthPercentage());
        Rs2Prayer.disableAllPrayers();
        if (!SireHelpers.teleportToHouse()) {
            log.error("[sire] EMERGENCY escape failed — no '{}' tablet? Cannot get home.", "Teleport to House");
            return "emergency-no-teleport";
        }
        // Home and safe — hand the resupply + return trip to ReturnToSireAction.
        ctx.setPrepPending(true);
        return "emergency-teleport";
    }
}

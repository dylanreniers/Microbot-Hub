package net.runelite.client.plugins.custom.cerberus.actions;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.combat.weapons.Halberd;
import net.runelite.client.plugins.microbot.util.combat.weapons.ManualCastable;
import net.runelite.client.plugins.microbot.util.combat.weapons.Melee;
import net.runelite.client.plugins.microbot.util.combat.weapons.PoweredStaff;
import net.runelite.client.plugins.microbot.util.combat.weapons.Weapon;
import net.runelite.client.plugins.microbot.util.combat.weapons.WeaponsGenerator;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import java.util.Map;

/**
 * Keeps the strongest unlocked offensive prayer up for the equipped weapon's style (melee / ranged /
 * magic). Re-checked every tick so it comes back on after running dry and drinking a restore. With the
 * no-ghosts strategy it stays off until her 14th attack (only the protection prayer, to save prayer).
 */
public class OffensivePrayerAction implements CerberusAction {

    private static final Map<Integer, Weapon> WEAPONS = WeaponsGenerator.generate();
    /** Ticks to wait for a clicked prayer to show as active before clicking again (a second landing click
     *  would toggle it back off). */
    private static final int PENDING_TICKS = 2;

    private int lastClickTick = -100;

    private static final Rs2PrayerEnum[] OFFENSIVE_PRAYERS = {
            Rs2PrayerEnum.PIETY, Rs2PrayerEnum.CHIVALRY, Rs2PrayerEnum.ULTIMATE_STRENGTH,
            Rs2PrayerEnum.SUPERHUMAN_STRENGTH, Rs2PrayerEnum.INCREDIBLE_REFLEXES,
            Rs2PrayerEnum.RIGOUR, Rs2PrayerEnum.DEAD_EYE, Rs2PrayerEnum.EAGLE_EYE, Rs2PrayerEnum.HAWK_EYE,
            Rs2PrayerEnum.SHARP_EYE, Rs2PrayerEnum.AUGURY, Rs2PrayerEnum.MYSTIC_VIGOUR, Rs2PrayerEnum.MYSTIC_MIGHT,
            Rs2PrayerEnum.MYSTIC_LORE, Rs2PrayerEnum.MYSTIC_WILL
    };

    @Override
    public int order() {
        return 400;
    }

    @Override
    public String key() {
        return "offensive-prayer";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        // Not while a protection flick is due: this click would compete with it for the input lock.
        return state.config().offensivePrayer() && state.context().isFightActive()
                && !state.context().isPrayerCritical();
    }

    @Override
    public Object execute(CerberusState state) {
        int tick = Microbot.getClient().getTickCount();
        if (state.context().isHoldingDamage() || beforeNoGhostsDamagePhase(state)) {
            return switchOffOffensive(state, tick);
        }
        Rs2PrayerEnum best = bestOffensivePrayer();
        if (best == null || Rs2Prayer.isPrayerActive(best) || Rs2Prayer.isOutOfPrayer()
                || tick - lastClickTick <= PENDING_TICKS || !state.claimClick()) {
            return best;
        }
        // Non-blocking click: toggle(prayer, true) waits up to 10 s for the prayer to show as active, and a
        // click that didn't land (right after drinking from 0 prayer) froze the whole pipeline — no
        // re-attacks — for those 10 s. Instead, click once and retry if it's still off a couple ticks later.
        Rs2Prayer.toggle(best);
        lastClickTick = tick;
        return best;
    }

    /**
     * No-ghosts: before her 14th attack we only bring her down to the hold point (and then hold), so the
     * offensive prayer isn't worth its drain — only from attack 14, when we race the kill. Needs a synced
     * counter (otherwise the strategy isn't running and we fight normally).
     */
    private boolean beforeNoGhostsDamagePhase(CerberusState state) {
        CerberusContext ctx = state.context();
        return state.config().noGhosts() && ctx.isCounterSynced()
                && ctx.getAttackCount() < CerberusConstants.NO_GHOSTS_SAFE_ATTACK;
    }

    /**
     * No-ghosts hold: we've stepped away and aren't attacking, so keep only the protection prayer up — switch
     * any active offensive prayer off (one per tick, non-blocking). It comes back on once the hold ends.
     */
    private Rs2PrayerEnum switchOffOffensive(CerberusState state, int tick) {
        for (Rs2PrayerEnum prayer : OFFENSIVE_PRAYERS) {
            if (Rs2Prayer.isPrayerActive(prayer)) {
                if (tick - lastClickTick > PENDING_TICKS && state.claimClick()) {
                    Rs2Prayer.toggle(prayer);
                    lastClickTick = tick;
                }
                return prayer;
            }
        }
        return null;
    }

    /** Unknown weapons (not in the weapon table) and unarmed are treated as melee. */
    private Rs2PrayerEnum bestOffensivePrayer() {
        Rs2ItemModel weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON);
        Weapon type = weapon == null ? null : WEAPONS.get(weapon.getId());
        if (type instanceof ManualCastable || type instanceof PoweredStaff) {
            return Rs2Prayer.getBestMagePrayer();
        }
        if (type == null || type instanceof Melee || type instanceof Halberd) {
            return Rs2Prayer.getBestMeleePrayer();
        }
        return Rs2Prayer.getBestRangePrayer();
    }
}

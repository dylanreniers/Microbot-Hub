package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Eats and drinks prayer potions at the configured thresholds while fighting.
 * <ul>
 *   <li>Eating only wins the tick when there is food — otherwise low HP would starve the prayer drink.</li>
 *   <li>One eat/drink per {@link CerberusConstants#SUSTAIN_COOLDOWN_TICKS}: the previous dose must
 *       register first, or it drinks again and wastes doses.</li>
 *   <li>Held while a protection flick is due — its mouse gesture delayed the third soul's prayer switch —
 *       unless HP is critically low or prayer is (nearly) empty. Prayer is topped up the moment a soul wave spawns, in the
 *       ticks before the first switch (three blocked souls drain 90).</li>
 * </ul>
 */
@Slf4j
public class SustainAction implements CerberusAction {

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String key() {
        return "sustain";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        CerberusContext ctx = state.context();
        if (!ctx.isFightActive()
                || tick() - ctx.getLastSustainTick() < CerberusConstants.SUSTAIN_COOLDOWN_TICKS) {
            return false;
        }
        if (ctx.isPrayerCritical()) {
            // No flick works without prayer, so an (almost) empty prayer bar beats the flick too.
            return needsEmergencyFood(state) || prayer() <= CerberusConstants.EMERGENCY_PRAYER;
        }
        return needsFood(state) || prayer() <= prayerThreshold(state);
    }

    @Override
    public Object execute(CerberusState state) {
        if (!state.claimClick()) {
            return null;
        }
        state.context().setLastSustainTick(tick());
        if (needsFood(state) && (!state.context().isPrayerCritical() || needsEmergencyFood(state))) {
            boolean ate = Rs2Player.useFood();
            log.info("[cerberus] eat at {}% HP -> {}", (int) Rs2Player.getHealthPercentage(), ate);
            return ate;
        }
        int prayer = prayer();
        boolean drank = Rs2Player.drinkPrayerPotionAt(prayerThreshold(state));
        log.info("[cerberus] drink at {} prayer -> {}", prayer, drank);
        return drank;
    }

    /**
     * The configured threshold, raised while a soul wave is out (before its switches) toward the prayer the
     * three souls drain — capped below the real prayer level so each top-up dose still fits.
     */
    private int prayerThreshold(CerberusState state) {
        int threshold = state.config().drinkPrayerAt();
        if (state.context().getSoulWaveSpawnTick() >= 0) {
            int cap = Rs2Player.getRealSkillLevel(Skill.PRAYER) - CerberusConstants.PRAYER_DOSE_HEADROOM;
            threshold = Math.max(threshold, Math.min(CerberusConstants.SOUL_WAVE_PRAYER_COST, cap));
        }
        return threshold;
    }

    private boolean needsFood(CerberusState state) {
        return Rs2Player.getHealthPercentage() <= state.config().eatAtPercent()
                && !Rs2Inventory.getInventoryFood().isEmpty();
    }

    /** Below half the eat threshold, eating beats the flick. */
    private boolean needsEmergencyFood(CerberusState state) {
        return Rs2Player.getHealthPercentage() <= state.config().eatAtPercent() / 2.0
                && !Rs2Inventory.getInventoryFood().isEmpty();
    }

    private int prayer() {
        return Rs2Player.getBoostedSkillLevel(Skill.PRAYER);
    }

    private int tick() {
        return Microbot.getClient().getTickCount();
    }
}

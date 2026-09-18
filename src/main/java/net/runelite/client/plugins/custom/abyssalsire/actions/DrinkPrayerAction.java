package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/** Drinks a prayer potion when prayer points fall to/below the configured threshold. */
@Slf4j
public class DrinkPrayerAction implements SireAction {

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String key() {
        return "drink-prayer";
    }

    @Override
    public boolean needsExecution(SireState state) {
        return Rs2Player.getPrayerPercentage() <= state.context().getPrayerPercent();
    }

    @Override
    public Object execute(SireState state) {
        boolean drank = Rs2Player.drinkPrayerPotionAt(state.context().getPrayerPercent());
        if (drank) {
            log.info("[sire] drinking prayer potion");
        }
        return drank;
    }
}

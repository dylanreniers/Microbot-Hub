package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/** Eats to keep health above the configured threshold, every tick. */
@Slf4j
public class EatAction implements SireAction {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String key() {
        return "eat";
    }

    @Override
    public boolean needsExecution(SireState state) {
        return Rs2Player.getHealthPercentage() <= state.context().getEatPercent();
    }

    @Override
    public Object execute(SireState state) {
        boolean ate = Rs2Player.eatAt(state.context().getEatPercent(), true);
        if (ate) {
            log.info("[sire] eating");
        }
        return ate;
    }
}

package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

/** Drops all prayers once the fight ends (Cerberus left id 5862 / despawned). */
@Slf4j
public class PrayersOffAction implements CerberusAction {

    @Override
    public int order() {
        return 900;
    }

    @Override
    public String key() {
        return "prayers-off";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        return state.context().isPrayersOffPending() && !state.context().isFightActive();
    }

    @Override
    public Object execute(CerberusState state) {
        state.context().setPrayersOffPending(false);
        log.info("[cerberus] fight over — disabling prayers");
        Rs2Prayer.disableAllPrayers();
        return true;
    }
}

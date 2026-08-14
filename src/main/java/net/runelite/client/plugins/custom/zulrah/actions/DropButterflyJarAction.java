package net.runelite.client.plugins.custom.zulrah.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * Drops the empty butterfly jars left behind after releasing a moonlight moth (see
 * {@link DrinkPrayerAction}). Kept as its own action so the release doesn't have to sleep waiting for
 * the jar to appear — the pipeline simply picks the jar up on a later tick.
 */
@Slf4j
public class DropButterflyJarAction implements ZulrahAction {

    @Override
    public int order() {
        return 210;
    }

    @Override
    public String key() {
        return "drop-butterfly-jar";
    }

    @Override
    public boolean needsExecution(ZulrahState state) {
        return Rs2Inventory.hasItem("Butterfly jar");
    }

    @Override
    public Object execute(ZulrahState state) {
        log.info("Dropping empty butterfly jar(s).");
        return Rs2Inventory.dropAll("Butterfly jar");
    }
}

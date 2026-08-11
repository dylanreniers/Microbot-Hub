package net.runelite.client.plugins.custom.microhunter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("DonderHunter")
@ConfigInformation("1. This script only supports box catching.<br/> 2. Stand where you want the trap pattern centred<br/> 3. Enable the plugin — it lays and tends the traps for you.")
public interface AutoHunterConfig extends Config {

    @ConfigItem(
            position = 1,
            keyName = "tickManipulation",
            name = "Tick Manipulation",
            description = "Use knife and logs for tick manipulation. NOTE: temporarily inactive after the "
                    + "tick-driven rewrite — the old timing relied on blocking sleeps. Leave off until it is "
                    + "reimplemented and verified live."
    )
    default boolean tickManipulation() {
        return false;
    }
}

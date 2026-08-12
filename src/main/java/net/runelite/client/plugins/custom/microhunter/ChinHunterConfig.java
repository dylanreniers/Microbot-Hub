package net.runelite.client.plugins.custom.microhunter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("DonderHunter")
@ConfigInformation("1. This script only supports box catching.<br/> 2. Stand where you want the trap pattern centred<br/> 3. Enable the plugin — it lays and tends the traps for you.")
public interface ChinHunterConfig extends Config {

    @ConfigItem(
            position = 1,
            keyName = "tickManipulation",
            name = "Tick Manipulation",
            description = "Use the knife &amp; logs 2-tick catch method. Requires a Knife and Teak logs in "
                    + "your inventory; without them the plugin falls back to normal catching."
    )
    default boolean tickManipulation() {
        return false;
    }
}

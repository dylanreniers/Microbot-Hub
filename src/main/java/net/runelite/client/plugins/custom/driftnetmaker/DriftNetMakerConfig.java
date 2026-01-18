



package net.runelite.client.plugins.custom.driftnetmaker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.custom.blastoisefurnace.enums.Bars;

@ConfigGroup("driftnetmaker")
@ConfigInformation("Must be at Auburnvale")
public interface DriftNetMakerConfig extends Config {

    @ConfigItem(
            keyName = "Credits",
            name = "Credits",
            description = "Credits",
            position = 3,
            section = "Credits"
    )
    default String credits() {
        return "Created by: Donder";
    }
}

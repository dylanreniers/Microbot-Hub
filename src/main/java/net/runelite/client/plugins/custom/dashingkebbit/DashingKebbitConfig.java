package net.runelite.client.plugins.custom.dashingkebbit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("dashingkebbit")
public interface DashingKebbitConfig extends Config {

    @ConfigItem(
            keyName = "travelToFalconry",
            name = "Travel to falconry",
            description = "On start, run to the Piscatoris Falconry: Teleport to House -> fairy ring (AKS) -> stile. "
                    + "Turn off if you're already standing in the falconry pen.",
            position = 0
    )
    default boolean travelToFalconry() {
        return true;
    }

    @ConfigItem(
            keyName = "rentFalcon",
            name = "Rent falcon automatically",
            description = "When you have no falcon, talk to Matthias and Pay-falcon (500 coins) to rent one.",
            position = 1
    )
    default boolean rentFalcon() {
        return true;
    }
}

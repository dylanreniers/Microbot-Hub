package net.runelite.client.plugins.custom.zulrah;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;

@ConfigGroup("donderzulrah")
public interface ZulrahConfig extends Config {
    @ConfigSection(
            name = "Zulrah",
            description = "",
            position = 0,
            closedByDefault = true
    )
    String zulrahSection = "zulrahSection";
    @ConfigItem(
            keyName = "mageInventorySetup",
            name = "Mage Inventory Setup",
            description = "Inventory Setup to use for magic",
            section = zulrahSection,
            position = 0
    )
    default InventorySetup mageInventorySetup() {
        return null;
    }

    @ConfigItem(
            keyName = "rangeInventorySetup",
            name = "Range Inventory Setup",
            description = "Inventory Setup to use for range",
            section = zulrahSection,
            position = 1
    )
    default InventorySetup rangeInventorySetup() {
        return null;
    }
}

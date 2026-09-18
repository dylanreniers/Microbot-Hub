package net.runelite.client.plugins.custom.abyssalsire;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;

@ConfigGroup("donderabyssalsire")
public interface AbyssalSireConfig extends Config {

    @ConfigSection(
            name = "Gear",
            description = "Inventory setups for each combat style",
            position = 0,
            closedByDefault = false
    )
    String gearSection = "gearSection";

    @ConfigItem(
            keyName = "rangeInventorySetup",
            name = "Range Inventory Setup",
            description = "Setup used for phase 1: Shadow Barrage the Sire (needs runes/rune pouch) and "
                    + "range down the respiratory systems.",
            section = gearSection,
            position = 0
    )
    default InventorySetup rangeInventorySetup() {
        return null;
    }

    @ConfigItem(
            keyName = "meleeInventorySetup",
            name = "Melee Inventory Setup",
            description = "Setup used for phases 2-3 (meleeing the exposed Sire).",
            section = gearSection,
            position = 1
    )
    default InventorySetup meleeInventorySetup() {
        return null;
    }

    @ConfigSection(
            name = "Combat",
            description = "Health/prayer thresholds and behaviour",
            position = 1,
            closedByDefault = false
    )
    String combatSection = "combatSection";

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "eatPercent",
            name = "Eat at HP %",
            description = "Eat when health falls to or below this percentage.",
            section = combatSection,
            position = 0
    )
    default int eatPercent() {
        return 50;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "prayerPercent",
            name = "Restore prayer at %",
            description = "Drink a prayer potion when prayer falls to or below this percentage.",
            section = combatSection,
            position = 1
    )
    default int prayerPercent() {
        return 25;
    }

    @ConfigItem(
            keyName = "hopIfPlayerPresent",
            name = "Hop if player present",
            description = "Before a fight, hop worlds if another player is at the Sire.",
            section = combatSection,
            position = 2
    )
    default boolean hopIfPlayerPresent() {
        return true;
    }

    @ConfigSection(
            name = "Looting",
            description = "What to pick up after a kill",
            position = 2,
            closedByDefault = true
    )
    String lootSection = "lootSection";

    @ConfigItem(
            keyName = "lootMinValue",
            name = "Min loot value",
            description = "Only pick up tradeable drops worth at least this many GP (untradeables like the "
                    + "Unsired are always looted).",
            section = lootSection,
            position = 0
    )
    default int lootMinValue() {
        return 1000;
    }
}

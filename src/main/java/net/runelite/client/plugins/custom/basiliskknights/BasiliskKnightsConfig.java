package net.runelite.client.plugins.custom.basiliskknights;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;

@ConfigInformation("Automates Basilisk Knights (Jormungand's Prison) via the Lunar Isle route.<br/><br/>"
        + "<p>Before starting:</p>"
        + "<ol>"
        + "<li>Create an <b>Inventory Setup</b> with your ranged gear, food, prayer restoration "
        + "(prayer potions / moonlight moths / moonlight moth mixes) and a <b>Teleport to House</b> method, "
        + "then select it as <b>Gear &amp; Inventory setup</b>.</li>"
        + "<li>Your POH must contain a <b>Lunar Isle Portal</b> and a <b>jewellery box</b> with the "
        + "Ferox Enclave teleport (used to restore &amp; resupply).</li>"
        + "<li>Stand anywhere &mdash; the plugin teleports home, enters the Lunar Isle portal and travels to the knights.</li>"
        + "</ol>"
        + "When out of prayer restoration items it finishes the current kill, teleports home, restores at "
        + "Ferox, resupplies from the bank, and repeats.")
@ConfigGroup("basiliskknights")
public interface BasiliskKnightsConfig extends Config
{
    @ConfigSection(
            name = "Gear",
            description = "Inventory setup used for banking / resupply",
            position = 0
    )
    String gearSection = "gearSection";

    @ConfigItem(
            keyName = "gearSetup",
            name = "Gear & Inventory setup",
            description = "Inventory setup with ranged gear, food, prayer restoration and a Teleport to House method",
            section = gearSection,
            position = 0
    )
    default InventorySetup gearSetup()
    {
        return null;
    }

    @ConfigSection(
            name = "Combat",
            description = "Food, prayer and looting thresholds",
            position = 1
    )
    String combatSection = "combatSection";

    @ConfigItem(
            keyName = "minEatPercent",
            name = "Minimum Health %",
            description = "Eat food when health drops below this percentage",
            section = combatSection,
            position = 0
    )
    @Range(min = 1, max = 99)
    default int minEatPercent()
    {
        return 50;
    }

    @ConfigItem(
            keyName = "minPrayerPercent",
            name = "Minimum Prayer %",
            description = "Restore prayer (moth / moth mix / prayer potion) when prayer drops below this percentage",
            section = combatSection,
            position = 1
    )
    @Range(min = 1, max = 99)
    default int minPrayerPercent()
    {
        return 20;
    }

    @ConfigItem(
            keyName = "lootMyLootOnly",
            name = "Only loot my loot",
            description = "Only pick up items that dropped for you (ignore other players' loot)",
            section = combatSection,
            position = 2
    )
    default boolean lootMyLootOnly()
    {
        return true;
    }

    @ConfigItem(
            keyName = "ignoreAmmunition",
            name = "Ignore ammunition",
            description = "Don't pick up ammunition (bolts, arrows, darts, etc.) — useful for leaving your spent ranged ammo on the floor",
            section = combatSection,
            position = 3
    )
    default boolean ignoreAmmunition()
    {
        return true;
    }

    @ConfigSection(
            name = "Debug",
            description = "Options for testing the plugin",
            position = 2,
            closedByDefault = true
    )
    String debugSection = "debugSection";

    @ConfigItem(
            keyName = "startState",
            name = "Start state",
            description = "Force the plugin to begin in this state instead of deciding automatically. "
                    + "AUTO does the normal start (resupply if ungeared, otherwise travel). "
                    + "Use FIGHTING when you're already standing at the knights.",
            section = debugSection,
            position = 0
    )
    default StartState startState()
    {
        return StartState.AUTO;
    }

    enum StartState
    {
        AUTO,
        TRAVEL,
        FEROX,
        FIGHTING
    }
}

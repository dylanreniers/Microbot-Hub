package net.runelite.client.plugins.custom.tormenteddemons;

import lombok.Getter;
import net.runelite.client.config.*;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;

@ConfigInformation("IMPORTANT!<br/>"
        + "This plugin automates Tormented Demon kills, including combat, prayer, banking, and restocking.<br/><br/>"
        + "Gear is now driven entirely by <b>Inventory Setups</b> (like Zulrah / Demonic Gorillas).<br/><br />"
        + "<p>Before starting:</p>\n"
        + "<ol>\n"
        + "    <li>Create an <b>Inventory Setup</b> for banking/restock (food, potions, teleports) and select it as <b>Gear &amp; Inventory setup</b></li>\n"
        + "    <li>For each combat style you enable, create and select its matching <b>Inventory Setup</b> (Range/Magic/Melee gear)</li>\n"
        + "    <li>The banking setup must include Guthixian temple teleport scrolls and a Ring of dueling</li>\n"
        + "</ol>\n"
        + "Full Auto mode also supports auto-looting and retreating when low on supplies to restock.<br/><br/>"
        + "<br/>"
        + "<b>For Combat Only</b>: Position your character near the demons and start the plugin.<br/>"
        + "Configure options in the settings to enable prayer switching, auto gear change, and looting preferences.<br/>"
        + "</html>")


        @ConfigGroup("customtormenteddemon")
public interface CustomTormentedDemonConfig extends Config {

    @ConfigSection(
            name = "General Settings",
            description = "Full Auto or Combat only",
            position = 0
    )
    String generalSettings = "generalSettings";

    @ConfigItem(
            keyName = "mode",
            name = "Mode",
            description = "Choose the bot mode: Full Auto or Combat Only",
            section = generalSettings,
            position = 0
    )
    default MODE mode() {
        return MODE.FULL_AUTO;
    }

    @ConfigItem(
            keyName = "walkMethod",
            name = "Walk Method",
            description = "How to travel to the demons: POH (house -> jewellery box -> Tears of Guthix, then climb down) "
                    + "or Teleport Scroll (use a Guthixian temple teleport from your inventory to the chasm, then climb the walls).",
            section = generalSettings,
            position = 1
    )
    default WalkMethod walkMethod() {
        return WalkMethod.POH;
    }

    @ConfigItem(
            keyName = "hopWhenPlayersNearby",
            name = "Hop If Players Nearby",
            description = "On arrival at the demons, hop worlds until no other player is within 20 tiles.",
            section = generalSettings,
            position = 2
    )
    default boolean hopWhenPlayersNearby() {
        return true;
    }

    @ConfigSection(
            name = "Tormented Demon Settings",
            description = "Settings for Tormented Demon boss helper",
            position = 1
    )
    String tormentedDemonSection = "tormentedDemon";

    @ConfigItem(
            keyName = "autoPrayerSwitch",
            name = "Enable Defensive Prayer",
            description = "Automatically switch prayers for Tormented Demon.",
            section = tormentedDemonSection,
            position = 2
    )
    default boolean enableDefensivePrayer() {
        return true;
    }

    @ConfigItem(
            keyName = "enableOffensivePrayer",
            name = "Enable Offensive Prayer",
            description = "Toggle to enable or disable offensive prayer during combat",
            section = tormentedDemonSection,
            position = 3
    )
    default boolean enableOffensivePrayer() {
        return false;
    }

    @ConfigItem(
            keyName = "autoGearSwitch",
            name = "Auto Gear Switch",
            description = "Automatically switch gear for Tormented Demon.",
            section = tormentedDemonSection,
            position = 4
    )
    default boolean autoGearSwitch() {
        return true;
    }


    @ConfigItem(
            keyName = "dodgeDelay",
            name = "Dodging delay(ms)",
            description = "Delay between dodge clicks (ms). The dodge randomly clicks 2-4 times "
                    + "spaced between 100 ms and this delay.",
            section = tormentedDemonSection,
            position = 5
    )
    default int dodgeDelay() {
        return 200;
    }

    @ConfigItem(
            keyName = "castThralls",
            name = "Cast Thralls",
            description = "Resurrect a thrall during combat (highest tier you can cast). Requires the Arceuus "
                    + "spellbook, the Book of the Dead, and the thrall runes in your setup.",
            section = tormentedDemonSection,
            position = 6
    )
    default boolean castThralls() {
        return false;
    }


    @ConfigSection(
            name = "Looting",
            description = "Settings for item looting",
            position = 2
    )
    String lootingSection = "looting";

    @ConfigItem(
            keyName = "lootEverything",
            name = "Loot Everything",
            description = "Loot all drops (any value, plus untradeables and coins). Disable to only loot the items listed below.",
            section = lootingSection,
            position = 0
    )
    default boolean lootEverything() {
        return true;
    }

    @ConfigItem(
            keyName = "lootMyLootOnly",
            name = "Only Loot My Loot",
            description = "Only pick up items that dropped for you (ignore other players' loot).",
            section = lootingSection,
            position = 1
    )
    default boolean lootMyLootOnly() {
        return true;
    }

    @ConfigItem(
            keyName = "lootItems",
            name = "Loot Items",
            description = "Comma-separated list of item names to always loot (in addition to 'Loot Everything')",
            section = lootingSection,
            position = 2
    )
    default String lootItems() {
        return "";
    }

    @ConfigItem(
            keyName = "scatterAshes",
            name = "Scatter Ashes",
            description = "Scatter Infernal Ashes upon looting",
            section = lootingSection,
            position = 3
    )
    default boolean scatterAshes() {
        return false;
    }



    @ConfigSection(
            name = "Food and Potions",
            description = "Settings for banking and required supplies",
            position = 3
    )
    String bankingAndSuppliesSection = "bankingAndSupplies";

    @ConfigItem(
            keyName = "minEatPercent",
            name = "Minimum Health Percent",
            description = "Percentage of health below which the bot will eat food",
            section = bankingAndSuppliesSection,
            position = 0
    )
    default int minEatPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "minPrayerPercent",
            name = "Minimum Prayer Percent",
            description = "Percentage of prayer points below which the bot will drink a prayer potion",
            section = bankingAndSuppliesSection,
            position = 1
    )
    default int minPrayerPercent() {
        return 20;
    }

    @ConfigItem(
            keyName = "healthThreshold",
            name = "Health Threshold to Exit",
            description = "Minimum health percentage to stay and fight",
            section = bankingAndSuppliesSection,
            position = 2
    )
    default int healthThreshold() {
        return 30;
    }


    @ConfigItem(
            keyName = "combatPotionType",
            name = "Combat Potion Type",
            description = "Select the type of combat potion to use",
            section = bankingAndSuppliesSection,
            position = 3
    )
    default CombatPotionType combatPotionType() {
        return CombatPotionType.SUPER_COMBAT;
    }

    @ConfigItem(
            keyName = "rangingPotionType",
            name = "Ranging Potion Type",
            description = "Select the type of ranging potion to use",
            section = bankingAndSuppliesSection,
            position = 4
    )
    default RangingPotionType rangingPotionType() {
        return RangingPotionType.RANGING;
    }

    @ConfigItem(
            keyName = "boostedStatsThreshold",
            name = "% Boosted Stats Threshold",
            description = "The threshold for using a potion when the boosted stats are below the maximum.",
            section = bankingAndSuppliesSection,
            position = 5
    )
    @Range(
            min = 1,
            max = 100
    )
    default int boostedStatsThreshold() {
        return 10;
    }

    @ConfigSection(
            name = "Gear Settings",
            description = "Select inventory setups for banking and each combat style",
            position = 4,
            closedByDefault = true
    )
    String gearSettingsSection = "gearSettings";

    @ConfigItem(
            keyName = "gearSetup",
            name = "Gear & Inventory setup",
            description = "Inventory setup used for banking/restock (food, potions, teleports, ring of dueling)",
            section = gearSettingsSection,
            position = 0
    )
    default InventorySetup gearSetup() {
        return null;
    }

    @ConfigItem(
            keyName = "useRangeStyle",
            name = "Use Range Style",
            description = "Toggle to use range gear and style",
            section = gearSettingsSection,
            position = 1
    )
    default boolean useRangeStyle() {
        return true;
    }

    @ConfigItem(
            keyName = "rangeGear",
            name = "Range Gear",
            description = "Inventory setup to equip when using ranged",
            section = gearSettingsSection,
            position = 2
    )
    default InventorySetup rangeGear() {
        return null;
    }

    @ConfigItem(
            keyName = "useRangeSpec",
            name = "Use Range Special Attack",
            description = "Use the equipped range weapon's special attack when attacking with range (when the spec bar has enough energy)",
            section = gearSettingsSection,
            position = 3
    )
    default boolean useRangeSpec() {
        return false;
    }

    @ConfigItem(
            keyName = "rangeSpecPercent",
            name = "Range Spec Cost %",
            description = "Special attack energy (%) the equipped range weapon needs, e.g. 50 for a Scorching bow",
            section = gearSettingsSection,
            position = 4
    )
    @Range(min = 1, max = 100)
    default int rangeSpecPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "useMagicStyle",
            name = "Use Magic Style",
            description = "Toggle to use magic gear and style",
            section = gearSettingsSection,
            position = 5
    )
    default boolean useMagicStyle() {
        return true;
    }

    @ConfigItem(
            keyName = "magicGear",
            name = "Magic Gear",
            description = "Inventory setup to equip when using magic",
            section = gearSettingsSection,
            position = 6
    )
    default InventorySetup magicGear() {
        return null;
    }

    @ConfigItem(
            keyName = "useMagicSpec",
            name = "Use Magic Special Attack",
            description = "Use the equipped magic weapon's special attack when attacking with magic (when the spec bar has enough energy)",
            section = gearSettingsSection,
            position = 7
    )
    default boolean useMagicSpec() {
        return false;
    }

    @ConfigItem(
            keyName = "magicSpecPercent",
            name = "Magic Spec Cost %",
            description = "Special attack energy (%) the equipped magic weapon needs",
            section = gearSettingsSection,
            position = 8
    )
    @Range(min = 1, max = 100)
    default int magicSpecPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "useMeleeStyle",
            name = "Use Melee Style",
            description = "Toggle to use melee gear and style",
            section = gearSettingsSection,
            position = 9
    )
    default boolean useMeleeStyle() {
        return true;
    }

    @ConfigItem(
            keyName = "meleeGear",
            name = "Melee Gear",
            description = "Inventory setup to equip when using melee",
            section = gearSettingsSection,
            position = 10
    )
    default InventorySetup meleeGear() {
        return null;
    }

    @ConfigItem(
            keyName = "useMeleeSpec",
            name = "Use Melee Special Attack",
            description = "Use the equipped melee weapon's special attack when attacking with melee (when the spec bar has enough energy)",
            section = gearSettingsSection,
            position = 11
    )
    default boolean useMeleeSpec() {
        return false;
    }

    @ConfigItem(
            keyName = "meleeSpecPercent",
            name = "Melee Spec Cost %",
            description = "Special attack energy (%) the equipped melee weapon needs, e.g. 50 for Dragon claws",
            section = gearSettingsSection,
            position = 12
    )
    @Range(min = 1, max = 100)
    default int meleeSpecPercent() {
        return 50;
    }

    enum MODE {
        FULL_AUTO,
        COMBAT_ONLY
    }

    enum WalkMethod {
        POH,
        TELEPORT_SCROLL
    }

    @Getter
    enum CombatPotionType {
        SUPER_COMBAT,
        DIVINE_SUPER_COMBAT,
        SUPER_ATTACK_AND_STRENGTH

    }

    @Getter
    enum RangingPotionType {
        RANGING,
        DIVINE_RANGING,
        BASTION
    }

}

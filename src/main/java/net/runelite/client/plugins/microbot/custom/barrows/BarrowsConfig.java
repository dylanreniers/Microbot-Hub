package net.runelite.client.plugins.microbot.custom.barrows;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;

@ConfigGroup("donder barrows")
@ConfigInformation("1. Have an inventory setup named Barrows <br><br> 2. Required items: prayer potions or moonlight moth mixes(2), barrows teleports tablets, or teleport to house tablets, food, Catalyic runes (if using wind spells), and a spade.<br /><br /> 3. Spells: Wind: Blast, Wave, and Surge. Or Powered staffs: supports any trident, any sceptre, any crystal staff, Tumeken's, and Sanguinesti. <br /><br /> Special thanks to george for adding the barrows dungeon to the walker; and Crannyy for script testing!<br /><br /> Config by Crannyy")
public interface BarrowsConfig extends Config {

    @ConfigItem(
            keyName = "food",
            name = "Food",
            description = "type of food",
            position = 1
    )
    default Rs2Food food()
    {
        return Rs2Food.POTATO_WITH_CHEESE;
    }

    @ConfigItem(
            keyName = "foodAmount",
            name = "Food amount",
            description = "Amount of food to withdraw from the bank.",
            position = 2
    )
    @Range(min = 1, max = 28)
    default int foodAmount() {
        return 12;
    }

    @ConfigItem(
            keyName = "prayerRestoreType",
            name = "Prayer Restore Type:",
            description = "Between prayer potions, or moonlight moth mixes.",
            position = 4
    )
    default PrayerRestoreType prayerRestoreType() {
        return PrayerRestoreType.PRAYER_POTION;
    }

    @Getter
    @RequiredArgsConstructor
    enum PrayerRestoreType {
        PRAYER_POTION("Prayer Potion", ItemID._4DOSEPRAYERRESTORE),
        MOONLIGHT_MOTH_MIX("Moonlight Moth Mix", ItemID.HUNTER_MIX_MOONMOTH_2DOSE),
        MOONLIGHT_MOTH("Moonlight Moth", ItemID.BUTTERFLY_JAR_MOONMOTH);

        private final String name;
        private final int id;
    }

    @ConfigItem(
            keyName = "minPrayerPots",
            name = "Prayer Restoration items",
            description = "Number of prayer potions, or moonlight moth mixes to withdraw from the bank.",
            position = 5
    )
    @Range(min = 1, max = 10)
    default int prayerRestorationItems() {
        return 2;
    }

    @ConfigItem(
            keyName = "magicAttack",
            name = "Magic attack",
            description = "Which magic attack to use",
            position = 6
    )
    default MagicAttack magicAttack() {
        return MagicAttack.WIND_BLAST;
    }

    @ConfigItem(
            keyName = "shouldGainRP",
            name = "Aim for 86+% rewards potential",
            description = "Should we gain additional RP other than the barrows brothers?",
            position = 7
    )
    default boolean shouldGainRP() {
        return false;
    }

    @ConfigItem(
            keyName = "shouldPrayAgainstWeakerBrothers",
            name = "Pray against Torag, Verac, and Guthans?",
            description = "Should we Pray against Torag, Verac, and Guthans?",
            position = 8
    )
    default boolean shouldPrayAgainstWeakerBrothers() {
        return true;
    }

    @ConfigItem(
            keyName = "inventorySetupMelee",
            name = "Inventory Setup (melee)",
            description = "Inventory Setup to use for melee brothers (and karil)",
            position = 9
    )
    default InventorySetup inventorySetupMelee() { return null; }

    @ConfigItem(
            keyName = "inventoryMagic",
            name = "Inventory Setup (Ahrim)",
            description = "Inventory Setup to use for Ahrim",
            position = 10
    )
    default InventorySetup inventorySetupAhrim() { return null; }

    @ConfigItem(
            keyName = "inventoryTunnels",
            name = "Inventory Setup",
            description = "Inventory Setup to use for the tunnels while gaining reward potential",
            position = 11
    )
    default InventorySetup inventorySetupTunnels() { return null; }
}

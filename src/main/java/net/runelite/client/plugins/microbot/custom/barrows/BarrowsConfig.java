package net.runelite.client.plugins.microbot.barrows;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.*;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;

@ConfigGroup("barrows")
@ConfigInformation("1. Have an inventory setup named Barrows <br><br> 2. Required items: prayer potions or moonlight moth mixes(2), barrows teleports tablets, or teleport to house tablets, food, Catalyic runes (if using wind spells), and a spade.<br /><br /> 3. Spells: Wind: Blast, Wave, and Surge. Or Powered staffs: supports any trident, any sceptre, any crystal staff, Tumeken's, and Sanguinesti. <br /><br /> Special thanks to george for adding the barrows dungeon to the walker; and Crannyy for script testing!<br /><br /> Config by Crannyy")
public interface BarrowsConfig extends Config {

    @ConfigItem(
            keyName = "Food",
            name = "Food",
            description = "type of food",
            position = 1
    )
    default Rs2Food food()
    {
        return Rs2Food.POTATO_WITH_CHEESE;
    }

    @ConfigItem(
            keyName = "targetFoodAmount",
            name = "Max Food Amount",
            description = "Max amount of food to withdraw from the bank.",
            position = 2
    )
    @Range(min = 1, max = 28)
    default int targetFoodAmount() {
        return 10;
    }

    @ConfigItem(
            keyName = "minFood",
            name = "Min Food",
            description = "Minimum amount of food to withdraw from the bank.",
            position = 3
    )
    @Range(min = 1, max = 28)
    default int minFood() {
        return 5;
    }

    @ConfigItem(
            keyName = "selectedPrayerRestoreType",
            name = "Prayer Restore Type:",
            description = "Between prayer potions, or moonlight moth mixes.",
            position = 4
    )
    default PrayerRestoreType prayerRestoreType() {
        return PrayerRestoreType.PRAYER_POTION;
    }

    enum PrayerRestoreType {
        PRAYER_POTION(ItemID._4DOSEPRAYERRESTORE),
        MOONLIGHT_MOTH_MIX(ItemID.HUNTER_MIX_MOONMOTH_2DOSE),
        MOONLIGHT_MOTH(ItemID.BUTTERFLY_JAR_MOONMOTH);

        private final int id;

        PrayerRestoreType(int id) {
            this.id = id;
        }


        public int getPrayerRestoreTypeID() {
            return id;
        }
    }

    @ConfigItem(
            keyName = "targetPrayerPots",
            name = "Max Prayer Restore",
            description = "Max amount of prayer potions, or moonlight moth mixes to withdraw from the bank.",
            position = 5
    )
    @Range(min = 1, max = 20)
    default int targetPrayerPots() {
        return 8;
    }

    @ConfigItem(
            keyName = "minPrayerPots",
            name = "Min Prayer Restore",
            description = "Minimum amount of prayer potions, or moonlight moth mixes to withdraw from the bank.",
            position = 6
    )
    @Range(min = 1, max = 10)
    default int minPrayerPots() {
        return 4;
    }

    @ConfigItem(
            keyName = "minRuneAmount",
            name = "Min Runes",
            description = "Minimum amount of runes before banking",
            position = 7
    )
    @Range(min = 50, max = 1000)
    default int minRuneAmount() {
        return 180;
    }

    @ConfigItem(
            keyName = "shouldGainRP",
            name = "Aim for 86+% rewards potential",
            description = "Should we gain additional RP other than the barrows brothers?",
            position = 8
    )
    default boolean shouldGainRP() {
        return false;
    }

    @ConfigItem(
            keyName = "shouldPrayAgainstWeakerBrothers",
            name = "Pray against Torag, Verac, and Guthans?",
            description = "Should we Pray against Torag, Verac, and Guthans?",
            position = 9
    )
    default boolean shouldPrayAgainstWeakerBrothers() {
        return true;
    }

    @ConfigItem(
            keyName = "inventorySetupMelee",
            name = "Inventory Setup (melee)",
            description = "Inventory Setup to use for melee brothers (and karil)",
            position = 10
    )
    default InventorySetup inventorySetupMelee() { return null; }

    @ConfigItem(
            keyName = "inventoryMagic",
            name = "Inventory Setup (Ahrim)",
            description = "Inventory Setup to use for Ahrim",
            position = 11
    )
    default InventorySetup inventorySetupAhrim() { return null; }

    @ConfigItem(
            keyName = "inventoryTunnels",
            name = "Inventory Setup",
            description = "Inventory Setup to use for the tunnels while gaining reward potential",
            position = 12
    )
    default InventorySetup inventorySetupTunnels() { return null; }
}

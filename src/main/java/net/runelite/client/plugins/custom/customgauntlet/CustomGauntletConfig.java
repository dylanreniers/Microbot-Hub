package net.runelite.client.plugins.custom.customgauntlet;

import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.*;

@ConfigGroup("CustomGauntlet")
@ConfigInformation("Gauntlet Helper V 1.0 <br><br> by Jam, Inspired by LifedMango <br><br>Switches Prayers & Weapons <br><br> Auto Eats + Drinks <br><br> Work in Progress "
)

public interface CustomGauntletConfig extends Config {

    @ConfigItem(
            keyName = "enOverlay",
            name = "Enable Overlay",
            description = "",
            position = 0
    )
    default boolean enableOverlay() {return false;}

    @ConfigSection(
            name = "General Settings",
            description = "General settings for the script",
            position = 0
    )
    String generalSection = "generalSection";

    @ConfigSection(
            name = "Food/Drink Settings",
            description = "General settings for the script",
            position = 1
    )
    String foodSection = "foodSection";

    @ConfigSection(
            name = "Feature Options",
            description = "General settings for the script",
            position = 2
    )
    String featureSection = "featureSection";

    @ConfigSection(
            name = "Debug Section",
            description = "Debugging Section",
            position = 3
    )
    String debugSection = "debugSection";



    @ConfigItem(
            keyName = "TitanPrayers",
            name = "Use Deadeye & Mystic Vigour",
            description = "Will use deadeye and mystic vigour",
            position = 0,
            section = "generalSection"
    )
    default boolean titansPrayers() {return false;}

    @ConfigItem(
            keyName = "HigherPrayers",
            name = "Use Augory & Rigour",
            description = "Turn on to use augory and Rigour",
            position = 1,
            section = "generalSection"
    )
    default boolean higherPrayers() {return false;}


    @ConfigItem(
            keyName = "ppotvalue",
            name = "Drink Potion at:",
            description = "Below this prayer amount, potion will auto drink",
            position = 0,
            section = "foodSection"

    )
    @Range(
            min = 1,
            max = 75
    )
    default int prayerPotionValue(){return 45;}

    @ConfigItem(
            keyName = "eatFood",
            name = "Eat below HP:",
            description = "Eats food whenever hitpoints fall below this value",
            position = 1,
            section = "foodSection"
    )
    @Range(
            min = 1,
            max = 99
    )
    default int lowHpEatValue() { return 65; }



    @ConfigItem(
            keyName = "enablefood",
            name = "Enable Auto Eat",
            description = "Below this prayer amount, potion will auto drink, this is a safe action so there should be no reason to turn off",
            position = 0,
            section = "featureSection"

    )
    default boolean enableFood() {
        return true;
    }

    @ConfigItem(
            keyName = "enabledrink",
            name = "Enable Auto Drink",
            description = "Below this prayer amount, potion will auto drink",
            position = 1,
            section = "featureSection"

    )
    default boolean enableDrink() {
        return true;
    }

    @ConfigItem(
            keyName = "autoAttack",
            name = "Attack after WeaponSwap",
            description = "Script attempts to attack after swapping weapons",
            position = 2,
            section = "featureSection"
    )
    default boolean autoAttack() {
        return true;
    }

    @ConfigItem(
            keyName = "runToSafespot",
            name = "Run to safespot",
            description = "Automatically run to a safe floor tile (id 36149) that is not under the Hunllef. Only while no tornados are active.",
            position = 3,
            section = "featureSection"
    )
    default boolean runToSafespot() {
        return false;
    }

    @ConfigItem(
            keyName = "dodgeTornadoes",
            name = "Dodge tornadoes",
            description = "When tornadoes are active, kite to the reachable safe tile that is furthest from them "
                    + "(never crossing damaging tiles or the Hunllef). Takes effect immediately when toggled.",
            position = 4,
            section = "featureSection"
    )
    default boolean dodgeTornadoes() {
        return false;
    }



    @ConfigItem(
            keyName = "startState",
            name = "Starting State",
            description = "The starting state of the bot. This is only used if override state is enabled.",
            position = 0,
            section = debugSection
    )
    default State startState() {return State.IDLE;}

    @ConfigItem(
            keyName = "debug",
            name = "Verbose logs",
            description = "turns verbose logging on",
            position = 1,
            section = debugSection
    )
    default boolean verboseLog() {
        return true;
    }

    @ConfigItem(
            keyName = "captureManualDodge",
            name = "Capture manual dodge",
            description = "Observe-only: during tornado phases the bot does NOT move you (you dodge by hand) "
                    + "and logs a [Capture] line each tick with your click target vs the live tornado/danger/boss "
                    + "tiles, so the human path can be analysed. Turn off for normal auto-play.",
            position = 2,
            section = debugSection
    )
    default boolean captureManualDodge() {
        return false;
    }
}

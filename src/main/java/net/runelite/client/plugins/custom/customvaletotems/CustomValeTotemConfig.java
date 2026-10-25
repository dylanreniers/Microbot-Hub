package net.runelite.client.plugins.custom.customvaletotems;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("customValeTotems")
@ConfigInformation(
    "<html>" +
    "🤖 <b>VALE TOTEM BOT - SETUP GUIDE</b><br /><br />" +
    
    "🔴 <b>REQUIREMENTS:</b><br />" +
    "✅ Children of the Sun quest completed (Varlamore access)<br />" +
    "✅ Vale Totems miniquest completed<br />" +
    "✅ Fletching level 20+<br />" +
    "✅ Agility 45+ highly recommended (untested below 45)<br />" +
    "✅ Knife or Fletching knife in bank<br />" +
    "✅ logs in bank<br /><br />" +
    
    "📊 <b>FLETCHING REQUIREMENTS:</b><br />" +
    "• <b>Oak:</b> Shortbow (20) | Longbow (25)<br />" +
    "• <b>Willow:</b> Shortbow (35) | Longbow (40)<br />" +
    "• <b>Maple:</b> Shortbow (50) | Longbow (55)<br />" +
    "• <b>Yew:</b> Shortbow (65) | Longbow (70)<br />" +
    "• <b>Magic:</b> Shortbow (80) | Longbow (85)<br /><br />" +
    
    "🎯 <b>FEATURES:</b><br />" +
    "• Automatically plays Vale Totem minigame<br />" +
    "• <b>Log Basket support:</b> Takes longer route with basket, shorter without<br />" +
    "• Automatically walks over ent trails for extra points and XP<br />" +
    "• Fletches while walking for efficiency<br />" +
    "• Auto banking and item management<br /><br />" +
    
    "🪵 <b>MATERIAL SOURCE:</b><br />" +
    "• <b>Logs:</b> Withdraws logs and fletches them into unstrung bows (needs a knife)<br />" +
    "• <b>Unstrung bows:</b> Withdraws pre-fletched unstrung bows for the carvings, no cutting<br />" +
    "• <b>Strung bows:</b> Withdraws finished strung bows for the carvings, no cutting<br />" +
    "• <i>Note: every totem base is still built from 1 raw log, so bow modes also keep a knife " +
    "and withdraw logs (1 per totem) for the bases — keep logs + a knife in the bank</i><br /><br />" +
    
    "🖥️ <b>CRITICAL REQUIREMENT:</b><br />" +
    "• <b>GPU Plugin</b> OR <b>117 HD Plugin</b> must be enabled<br />" +
    "• <b>Draw Distance:</b> Minimum 40<br />" +
    "• <i>Note: Bot clicks far distances to fletch while walking</i><br /><br />" +
    
    "🏁 <b>QUICK START:</b><br />" +
    "• Start anywhere with empty inventory (recommended)<br />" +
    "• Ensure desired logs and knife/fletching knife are in bank<br />" +
    "• <b>Equip desired gear before starting</b> (bot doesn't wield/unwield)<br />" +
    "• <b>Recommended:</b> Graceful gear for reduced run energy drain<br />" +
    "• Bot handles all navigation and banking automatically!<br /><br />" +

    "⚡ <b>STAMINA SUPPORT:</b><br />" +
    "• Enable 'Use Stamina Potions' to automatically drink stamina potions<br />" +
    "• Bot will withdraw potions from bank and drink when run energy is low<br />" +
    "• Stamina potions are prioritized over energy potions"
)
public interface CustomValeTotemConfig extends Config {
    
    @ConfigItem(
            keyName = "materialSource",
            name = "Material Source",
            description = "Logs are fletched into unstrung bows (needs a knife). Unstrung/Strung bows are withdrawn ready-made for the carvings. Every mode still needs a knife and 1 raw log per totem for the bases.",
            position = 0
    )
    default MaterialMode materialSource() {
        return MaterialMode.LOGS;
    }

    @ConfigItem(
            keyName = "logType",
            name = "Tree Type",
            description = "Select which tree the logs/bows come from (Oak, Willow, Maple, Yew, Magic)",
            position = 1
    )
    default LogType logType() {
        return LogType.YEW;
    }

    @ConfigItem(
            keyName = "bowType",
            name = "Bow Type",
            description = "Select whether to use shortbows or longbows",
            position = 2
    )
    default BowType bowType() {
        return BowType.LONGBOW;
    }

    @ConfigItem(
            keyName = "collectOfferingsFrequency",
            name = "Offering Collection Frequency",
            description = "How often to collect offerings (every X rounds) - +- 1",
            position = 3
    )
    default int collectOfferingsFrequency() {
        return 5;
    }

    @ConfigSection(
            name = "Stamina",
            description = "Stamina potion settings",
            position = 10
    )
    String staminaSection = "stamina";

    @ConfigItem(
            keyName = "useStaminaPotions",
            name = "Use Stamina Potions",
            description = "Automatically drink stamina potions when run energy is low",
            position = 11,
            section = staminaSection
    )
    default boolean useStaminaPotions() {
        return false;
    }

    @ConfigItem(
            keyName = "staminaThreshold",
            name = "Drink At Energy %",
            description = "Drink stamina potion when run energy falls below this percentage",
            position = 12,
            section = staminaSection
    )
    @Range(min = 10, max = 90)
    default int staminaThreshold() {
        return 50;
    }

    enum MaterialMode {
        LOGS("Logs (fletch into unstrung bows)"),
        UNSTRUNG_BOWS("Unstrung bows (no fletching)"),
        STRUNG_BOWS("Strung bows (no fletching)");

        private final String displayName;

        MaterialMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        /**
         * @return true if this mode requires cutting logs into unstrung bows
         */
        public boolean requiresFletching() {
            return this == LOGS;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    enum LogType {
        OAK("Oak Logs", 20),
        WILLOW("Willow Logs", 35),
        MAPLE("Maple Logs", 50),
        YEW("Yew Logs", 65),
        MAGIC("Magic Logs", 80);

        private final String displayName;
        private final int requiredLevel;

        LogType(String displayName, int requiredLevel) {
            this.displayName = displayName;
            this.requiredLevel = requiredLevel;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getRequiredLevel() {
            return requiredLevel;
        }

        @Override
        public String toString() {
            return displayName + " (" + requiredLevel + " Fletching)";
        }
    }

    enum BowType {
        SHORTBOW("Shortbow"),
        LONGBOW("Longbow");

        private final String displayName;

        BowType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}

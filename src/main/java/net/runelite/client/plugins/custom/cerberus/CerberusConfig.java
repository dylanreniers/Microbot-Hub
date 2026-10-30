package net.runelite.client.plugins.custom.cerberus;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

@ConfigGroup("customcerberus")
public interface CerberusConfig extends Config {

    /** The protection prayer kept up between the triple attack and the souls. */
    enum CampPrayer {
        MAGIC("Protect from Magic", Rs2PrayerEnum.PROTECT_MAGIC),
        MISSILES("Protect from Missiles", Rs2PrayerEnum.PROTECT_RANGE),
        MELEE("Protect from Melee", Rs2PrayerEnum.PROTECT_MELEE);

        private final String label;
        private final Rs2PrayerEnum prayer;

        CampPrayer(String label, Rs2PrayerEnum prayer) {
            this.label = label;
            this.prayer = prayer;
        }

        public Rs2PrayerEnum getPrayer() {
            return prayer;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigSection(
            name = "Prayer",
            description = "Protection and offensive prayers",
            position = 0
    )
    String prayerSection = "prayer";

    @ConfigSection(
            name = "Fight",
            description = "Lava dodging, the no-ghosts strategy and thralls",
            position = 1
    )
    String fightSection = "fight";

    @ConfigSection(
            name = "Sustain",
            description = "Eating and prayer restoring thresholds",
            position = 2
    )
    String sustainSection = "sustain";

    @ConfigSection(
            name = "Resupply",
            description = "When and how to restock between kills (Ferox Enclave) and get back into the lair",
            position = 3
    )
    String resupplySection = "resupply";

    @ConfigItem(
            keyName = "resupplySetup",
            name = "Inventory setup",
            description = "Setup loaded at the Ferox bank. Needs a ring of dueling (worn or in the inventory) "
                    + "and 'Teleport to house' tablets (house in Taverley).",
            section = resupplySection,
            position = 0
    )
    default InventorySetup resupplySetup() {
        return null;
    }

    @ConfigItem(
            keyName = "minPrayerPoints",
            name = "Min prayer points",
            description = "Resupply before the next kill when current prayer + all prayer doses/moths in the "
                    + "inventory is at or below this",
            section = resupplySection,
            position = 1
    )
    default int minPrayerPoints() {
        return 200;
    }

    @ConfigItem(
            keyName = "minHitpoints",
            name = "Min hitpoints",
            description = "Resupply before the next kill when current HP + all food in the inventory is below this",
            section = resupplySection,
            position = 2
    )
    default int minHitpoints() {
        return 120;
    }

    @ConfigItem(
            keyName = "campPrayer",
            name = "Camp prayer",
            description = "Protection prayer kept up all fight — pick the style you're weakest against. "
                    + "Only the triple attack and the summoned souls switch away from it.",
            section = prayerSection,
            position = 0
    )
    default CampPrayer campPrayer() {
        return CampPrayer.MAGIC;
    }

    @ConfigItem(
            keyName = "offensivePrayer",
            name = "Offensive prayer",
            description = "Keep the strongest unlocked offensive prayer up for the equipped weapon's style",
            section = prayerSection,
            position = 1
    )
    default boolean offensivePrayer() {
        return true;
    }

    @ConfigItem(
            keyName = "dodgeLava",
            name = "Dodge lava",
            description = "Step off lava pools (graphics object 1246) to the nearest pool-free tile",
            section = fightSection,
            position = 0
    )
    default boolean dodgeLava() {
        return true;
    }

    @ConfigItem(
            keyName = "noGhosts",
            name = "No-ghosts strategy",
            description = "Keep her at/above 400 HP until her 14th attack so no souls come before attack 28: "
                    + "stop attacking near 400 HP, resume at attack 14, and reset over the entrance flames if "
                    + "she drops below 400 too early",
            section = fightSection,
            position = 1
    )
    default boolean noGhosts() {
        return false;
    }

    @Range(min = 0, max = 150)
    @ConfigItem(
            keyName = "noGhostsHoldMargin",
            name = "No-ghosts hold margin",
            description = "Stop attacking once her HP is at or below 400 + this (cover your max hit; the health "
                    + "bar is only accurate to ~20 HP)",
            section = fightSection,
            position = 2
    )
    default int noGhostsHoldMargin() {
        return 60;
    }

    @ConfigItem(
            keyName = "summonThrall",
            name = "Summon thrall",
            description = "Keep a magic (ghost) thrall up — strongest tier you can cast (Arceuus spellbook). "
                    + "From the start of the fight, or from attack 14 with the no-ghosts strategy.",
            section = fightSection,
            position = 3
    )
    default boolean summonThrall() {
        return false;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "eatAtPercent",
            name = "Eat at HP %",
            description = "Eat when health drops to or below this percentage",
            section = sustainSection,
            position = 0
    )
    default int eatAtPercent() {
        return 50;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "drinkPrayerAt",
            name = "Drink prayer at",
            description = "Drink a prayer/restore potion when prayer points drop to or below this. "
                    + "Blocking all three souls without a spectral shield needs ~65 points.",
            section = sustainSection,
            position = 1
    )
    default int drinkPrayerAt() {
        return 45;
    }
}

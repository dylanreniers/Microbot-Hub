package net.runelite.client.plugins.custom.madangel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("madangel")
public interface MadAngelConfig extends Config {

    @ConfigSection(
            name = "Reactions",
            description = "Which of the Mad Angel's special-attack reactions to handle",
            position = 0
    )
    String reactionsSection = "reactions";

    @ConfigSection(
            name = "Sustain",
            description = "Eating and prayer restoring thresholds",
            position = 1
    )
    String sustainSection = "sustain";

    @ConfigSection(
            name = "Diagnostics",
            description = "Logging aids for tuning the reactions",
            position = 2
    )
    String diagnosticsSection = "diagnostics";

    @ConfigItem(
            keyName = "enableSweepDodge",
            name = "Dodge sweep",
            description = "Strafe out of the sweep cleave (2 tiles to the safe side + 1 forward)",
            section = reactionsSection,
            position = 0
    )
    default boolean enableSweepDodge() {
        return true;
    }

    @ConfigItem(
            keyName = "enableBlastDodge",
            name = "Bounce blast",
            description = "Stand on the marked tile to bounce the energy-ball (\"burst\") back",
            section = reactionsSection,
            position = 1
    )
    default boolean enableBlastDodge() {
        return true;
    }

    @ConfigItem(
            keyName = "enableSmitePrayer",
            name = "Flick smite prayer",
            description = "Flick Protect from Magic on the correct ticks during the smite attack",
            section = reactionsSection,
            position = 2
    )
    default boolean enableSmitePrayer() {
        return true;
    }

    @ConfigItem(
            keyName = "enableProtectFromMelee",
            name = "Protect from Melee",
            description = "Keep Protect from Melee up by default (restored after each smite flick)",
            section = reactionsSection,
            position = 3
    )
    default boolean enableProtectFromMelee() {
        return true;
    }

    @ConfigItem(
            keyName = "enableOffensivePrayer",
            name = "Offensive prayer",
            description = "Keep the best melee offensive prayer (Piety/Chivalry/…) active",
            section = reactionsSection,
            position = 4
    )
    default boolean enableOffensivePrayer() {
        return true;
    }

    @Range(min = 0, max = 99)
    @ConfigItem(
            keyName = "minEatPercent",
            name = "Eat at HP %",
            description = "Eat food when hitpoints drop to this percent (0 to disable)",
            section = sustainSection,
            position = 0
    )
    default int minEatPercent() {
        return 50;
    }

    @Range(min = 0, max = 99)
    @ConfigItem(
            keyName = "minPrayerPercent",
            name = "Drink at Prayer %",
            description = "Drink a prayer potion when prayer drops to this percent (0 to disable)",
            section = sustainSection,
            position = 1
    )
    default int minPrayerPercent() {
        return 30;
    }

    @ConfigItem(
            keyName = "logAnimTiming",
            name = "Log animation timing",
            description = "Log every angel animation change with the tick delta since the previous one "
                    + "(and boss HP%/phase), to measure cleave intervals for the sweep tick schedule",
            section = diagnosticsSection,
            position = 0
    )
    default boolean logAnimTiming() {
        return true;
    }

    @ConfigItem(
            keyName = "sweepDodgeDryRun",
            name = "Sweep dodge: dry-run",
            description = "Detect sweeps and draw the dodge tiles/overlay, but DON'T actually move. "
                    + "Lets you stand still and verify the green safe-tile against the sword visually",
            section = diagnosticsSection,
            position = 1
    )
    default boolean sweepDodgeDryRun() {
        return false;
    }
}

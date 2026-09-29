package net.runelite.client.plugins.custom.golemcrafting;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.custom.golemcrafting.actions.CraftingMode;

@ConfigGroup("golemcrafting")
public interface GolemCraftingConfig extends Config {

    @ConfigItem(
            keyName = "craftingMode",
            name = "Crafting mode",
            description = "Lazy: one click per side, then wait. Spam-click: re-click every 500-800ms (faster). "
                    + "Perfect: one click 100-300ms after each XP drop (fast, fewer clicks).",
            position = -1
    )
    default CraftingMode craftingMode() {
        return CraftingMode.LAZY;
    }

    @Range(min = 1, max = 5)
    @ConfigItem(
            keyName = "golemsPerTrip",
            name = "Golems per trip",
            description = "How many golems to make per mining batch (capped by inventory space and furs). 5 = 25 sunstone.",
            position = 0
    )
    default int golemsPerTrip() {
        return 5;
    }

    @ConfigItem(
            keyName = "furType",
            name = "Fur type",
            description = "Exact name of the fur to keep in the pouch (must match your bank/pouch fur).",
            position = 1
    )
    default String furType() {
        return "Dashing kebbit fur";
    }

    @ConfigItem(
            keyName = "bankForFurs",
            name = "Bank to refill furs",
            description = "When the fur pouch runs out, refill it from the Wyrmscraig bank chest. If off, the plugin stops instead.",
            position = 2
    )
    default boolean bankForFurs() {
        return true;
    }

    @ConfigItem(
            keyName = "useGemBag",
            name = "Use gem bag",
            description = "Carry a gem bag: pick up all uncut gems (mined + from golems) and Empty the bag into the bank each trip.",
            position = 4
    )
    default boolean useGemBag() {
        return false;
    }

    @ConfigItem(
            keyName = "useMonolith",
            name = "Mine the monolith",
            description = "Mine the single Sunstone monolith instead of hopping rocks with momentum. Simpler but lower yield.",
            position = 3
    )
    default boolean useMonolith() {
        return false;
    }
}

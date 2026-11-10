package net.runelite.client.plugins.custom.custommixology;

import net.runelite.client.config.*;
import net.runelite.client.plugins.custom.custommixology.enums.AgaHerbs;
import net.runelite.client.plugins.custom.custommixology.enums.LyeHerbs;
import net.runelite.client.plugins.custom.custommixology.enums.MoxHerbs;
import net.runelite.client.plugins.custom.custommixology.enums.OrderStrategy;
import net.runelite.client.plugins.custom.custommixology.enums.PotionComponent;

@ConfigGroup("custommixology")
@ConfigInformation("<p>Start the script at the bankchest of the mixology minigame room with an empty inventory.</p> <br />" +
        "<br />Requirements: " +
        "<ol> " +
        "<li>60 Herblore</li>" +
        "<li>Herbs for making mox/aga/lye paste</li>" +
        "</ol>")
public interface CustomMixologyConfig extends Config {
    @ConfigSection(
            name = "Refiner",
            description = "Refiner configuration",
            position = 0
    )
    String refiner = "Refiner";

    @ConfigSection(
            name = "Strategy",
            description = "Which orders to make (see the OSRS wiki Mastering Mixology strategies page)",
            position = 1
    )
    String strategy = "Strategy";

    @ConfigSection(
            name = "Minigame",
            description = "General minigame configuration",
            position = 2
    )
    String minigame = "Minigame";

    @ConfigItem(
            keyName = "RefinerHerbMox",
            name = "Refining Mox Herb",
            description = "Refine herbs into mox paste (tarromin is the cheapest per paste)",
            position = 0,
            section = refiner
    )
    default MoxHerbs moxHerb() {
        return MoxHerbs.Tarromin;
    }
    @Range(min = 100, max = 3000)
    @ConfigItem(
            keyName = "RefinerHerbMoxAmt",
            name = "Mox paste target",
            description = "Refine until hopper + bank hold this much mox paste",
            position = 1,
            section = refiner
    )
    default int amtMoxHerb() {
        return 1000;
    }

    @ConfigItem(
            keyName = "RefinerHerbLye",
            name = "Refining Lye Herb",
            description = "Refine herbs into lye paste (avantoe is the cheapest per paste)",
            position = 2,
            section = refiner
    )
    default LyeHerbs lyeHerb() {
        return LyeHerbs.Avantoe;
    }
    @Range(min = 100, max = 3000)
    @ConfigItem(
            keyName = "RefinerHerbLyeAmt",
            name = "Lye paste target",
            description = "Refine until hopper + bank hold this much lye paste",
            position = 3,
            section = refiner
    )
    default int amtLyeHerb() {
        return 1000;
    }

    @ConfigItem(
            keyName = "RefinerHerbAga",
            name = "Refining Aga Herb",
            description = "Refine herbs into aga paste (lantadyme is the cheapest per paste)",
            position = 4,
            section = refiner
    )
    default AgaHerbs agaHerb() {
        return AgaHerbs.Lantadyme;
    }
    @Range(min = 100, max = 3000)
    @ConfigItem(
            keyName = "RefinerHerbAgaAmt",
            name = "Aga paste target",
            description = "Refine until hopper + bank hold this much aga paste",
            position = 5,
            section = refiner
    )
    default int amtAgaHerb() {
        return 1000;
    }

    @Range(min = 30, max = 1000)
    @ConfigItem(
            keyName = "refillThreshold",
            name = "Refill below",
            description = "Go refine once any paste in the hopper drops below this amount",
            position = 6,
            section = refiner
    )
    default int refillThreshold() {
        return 100;
    }

    @ConfigItem(
            keyName = "useQuickActionRefiner",
            name = "Use Quick Action on Refiner",
            description = "Clicks the refiner every tick, doubling the paste rate",
            position = 7,
            section = refiner
    )
    default boolean useQuickActionRefiner() {
        return true;
    }

    // -- STRATEGY SECTION -- //

    @ConfigItem(
            keyName = "orderStrategy",
            name = "Order strategy",
            description = "All orders: make everything. Experience: skip single-paste potions. "
                    + "Total points: skip singles unless a MAL is offered. Single resin: favour the preferred resin.",
            position = 0,
            section = strategy
    )
    default OrderStrategy orderStrategy() {
        return OrderStrategy.ALL_ORDERS;
    }

    @ConfigItem(
            keyName = "preferredResin",
            name = "Preferred resin",
            description = "Target resin for 'Single resin', and the tie-break for single-only boards under 'Total points'",
            position = 1,
            section = strategy
    )
    default PotionComponent preferredResin() {
        return PotionComponent.LYE;
    }

    @ConfigItem(
            keyName = "stockPotions",
            name = "Stock potions (batching)",
            description = "Keeps finished potions of the types you deliver in the inventory, so new orders "
                    + "can be handed in straight away (wiki experience method). Experimental.",
            position = 2,
            section = strategy
    )
    default boolean stockPotions() {
        return false;
    }

    @Range(min = 1, max = 3)
    @ConfigItem(
            keyName = "stockPerType",
            name = "Stock per type",
            description = "How many potions of each delivered type to keep in stock",
            position = 3,
            section = strategy
    )
    default int stockPerType() {
        return 2;
    }

    // -- MINIGAME SECTION -- //

    @ConfigItem(
            keyName = "useQuickActionAlembic",
            name = "Use Quick Action on Alembic",
            description = "Will click once there is a quick action available on the alembic",
            position = 0,
            section = minigame
    )
    default boolean useQuickActionOnAlembic() {
        return true;
    }
    @ConfigItem(
            keyName = "useQuickActionAgitator",
            name = "Use Quick Action on Agitator",
            description = "Will click once there is a quick action available on the agitator",
            position = 1,
            section = minigame
    )
    default boolean useQuickActionOnAgitator() {
        return true;
    }

    @ConfigItem(
            keyName = "agitatorDoubleClick",
            name = "Agitator double click",
            description = "Also click on the second tick of the agitator window for a double speed-up. "
                    + "Disable if clicks keep missing the window (a miss costs progress).",
            position = 2,
            section = minigame
    )
    default boolean agitatorDoubleClick() {
        return true;
    }

    @ConfigItem(
            keyName = "useQuickActionRetort",
            name = "Use Quick Action on Retort",
            description = "Clicks the retort once every tick while it is concentrating",
            position = 3,
            section = minigame
    )
    default boolean useQuickActionOnRetort() {
        return true;
    }

    @ConfigItem(
            keyName = "pickDigWeed",
            name = "Pick DigWeed",
            description = "Picks digweed and adds it to a MAL (or, below 81 Herblore, the highest experience potion)",
            position = 4,
            section = minigame
    )
    default boolean pickDigWeed() {
        return true;
    }

    @ConfigItem(
            keyName = "useQuickActionLever",
            name = "Use Quick Action on Lever",
            description = "Will click fast when interacting with the lever for mixing potions",
            position = 5,
            section = minigame
    )
    default boolean useQuickActionLever() {
        return true;
    }
}

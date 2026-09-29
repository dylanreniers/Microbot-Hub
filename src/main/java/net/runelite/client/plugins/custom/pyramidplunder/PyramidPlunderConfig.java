package net.runelite.client.plugins.custom.pyramidplunder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("custompyramidplunder")
public interface PyramidPlunderConfig extends Config {

    @ConfigItem(
            keyName = "lootChestFromRoom",
            name = "Loot chest from room",
            description = "Only search the Grand Gold Chest from this room number onward (earlier rooms' chests are low value).",
            position = 0
    )
    @Range(min = 1, max = 8)
    default int lootChestFromRoom() {
        return 4;
    }

    @ConfigItem(
            keyName = "lootSarcophagusFromRoom",
            name = "Loot sarcophagus from room",
            description = "Only search the Sarcophagus from this room number onward. Set higher than 8 to never loot it "
                    + "(it is slow and can spawn a mummy).",
            position = 1
    )
    @Range(min = 1, max = 9)
    default int lootSarcophagusFromRoom() {
        return 9;
    }

    @ConfigItem(
            keyName = "saveLastRoomSeconds",
            name = "Save seconds for urns",
            description = "When this many seconds or fewer remain (or the deepest reachable room is hit), stop advancing and loot urns until the game ends.",
            position = 2
    )
    @Range(min = 30, max = 240)
    default int saveLastRoomSeconds() {
        return 90;
    }

    @ConfigItem(
            keyName = "lootUrns",
            name = "Loot urns",
            description = "Loot urns in the deepest reachable room. Turn off to ignore urns entirely and rush rooms "
                    + "for the sceptre (leave as soon as chests/sarcophagi are done).",
            position = 3
    )
    default boolean lootUrns() {
        return true;
    }

    @ConfigItem(
            keyName = "dropJunk",
            name = "Drop low-value loot",
            description = "Drop pottery/stone-tier artefacts when the inventory is nearly full, keeping the valuable gold-tier loot and sceptre.",
            position = 4
    )
    default boolean dropJunk() {
        return true;
    }

    @ConfigItem(
            keyName = "bankAtSophanem",
            name = "Bank at Sophanem",
            description = "When a run ends with fewer than 4 free inventory slots, bank the loot at the Sophanem Dungeon "
                    + "(keeping lockpicks) and return. If off, it just keeps re-entering with a full inventory.",
            position = 5
    )
    default boolean bankAtSophanem() {
        return true;
    }
}

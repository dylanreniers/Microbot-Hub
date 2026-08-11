package net.runelite.client.plugins.custom.karambwans;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("Karambwans")
@ConfigInformation(
        "<ol>" +
                "<li>Configure the fairy rings to DKP (last destination must be DKP)</li>" +
                "<li>Make sure to have karambwan vessel and raw karambwanji in your inventory</li>" +
                "<li>Start the script next to the karambwan fishing spot</li>" +
                "</ol>"
)
public interface KarambwansConfig extends Config {

    @ConfigSection(
            name = "Starting State",
            description = "Starting State",
            position = 0,
            closedByDefault = true
    )
    String startingStateSection = "startingStateSection";

    @ConfigItem(
            keyName = "startingState",
            name = "(Debug) Starting State",
            description = "Starting State. Only used for development.",
            position = 0,
            section = startingStateSection
    )
    default KarambwansInfo.states STARTING_STATE() {
        return KarambwansInfo.states.FISHING;
    }
}



package net.runelite.client.plugins.custom.mortmyrecollector;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = "Donder's fungus collector",
        description = "A plugin to farm the Moons of Peril",
        tags = {"mort", "myre", "fungus", "herblore"},
        authors = "Donder",
        minClientVersion = "2.0.14",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class MortMyrteFungusCollectorPlugin extends Plugin {

    @Inject
    private MortMyreFungusCollectorScript mortMyreFungusCollectorScript;

    @Override
    protected void startUp() throws Exception {
        log.info("Starting up!");
        log.info("script: {}", this.mortMyreFungusCollectorScript);
        mortMyreFungusCollectorScript.run();
    }

    @Override
    protected void shutDown() throws Exception {
        mortMyreFungusCollectorScript.shutdown();
    }
}

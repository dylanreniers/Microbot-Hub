package net.runelite.client.plugins.custom.driftnetmaker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = "Donder's Drift net maker",
        description = "Donder's Drift net maker plugin",
        tags = {"microbot", "crafting", "driftnet"},
        authors = {"Donder"},
        version = DriftNetMakerPlugin.VERSION,
        minClientVersion = "2.1.0",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class DriftNetMakerPlugin extends Plugin {

    public static final String VERSION = "1.0.0";

    @Inject
    private DriftNetMakerScript driftNetMakerScript;

    @Override
    protected void startUp() throws AWTException {
        driftNetMakerScript.run();
    }

    protected void shutDown() {
        log.info("Shutting down DriftNetMaker");
        driftNetMakerScript.shutdown();
    }

}

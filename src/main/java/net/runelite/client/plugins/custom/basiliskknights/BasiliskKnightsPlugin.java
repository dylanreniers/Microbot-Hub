package net.runelite.client.plugins.custom.basiliskknights;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.AWTException;

@PluginDescriptor(
        name = "Basilisk Knights (Custom)",
        description = "Automates Basilisk Knights via the Lunar Isle route: travel, ranging, prayer, looting and Ferox restocking",
        tags = {"basilisk", "knight", "slayer", "combat", "ranged", "custom"},
        authors = {"Dylan"},
        version = BasiliskKnightsPlugin.version,
        minClientVersion = "2.1.0",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class BasiliskKnightsPlugin extends Plugin
{
    public static final String version = "1.0.0";

    static String status = "Idle";

    @Inject
    private BasiliskKnightsConfig config;

    @Provides
    BasiliskKnightsConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BasiliskKnightsConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private BasiliskKnightsOverlay overlay;
    @Inject
    private BasiliskKnightsScript script;

    @Override
    protected void startUp() throws AWTException
    {
        if (overlayManager != null)
        {
            overlayManager.add(overlay);
        }
        script.run(config);
    }

    @Override
    protected void shutDown()
    {
        script.shutdown();
        overlayManager.remove(overlay);
        status = "Idle";
    }
}

package net.runelite.client.plugins.custom.customherbrun;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = "Herb Runner (Custom)",
        description = "Herb runner with an optional repeat timer (logs out between runs and resumes automatically)",
        tags = {"herb", "farming", "money making", "skilling", "custom"},
        authors = {"Mocrosoft", "Dylan"},
        version = CustomHerbrunPlugin.version,
        minClientVersion = "2.1.0",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class CustomHerbrunPlugin extends Plugin {
    public static final String version = "1.0.1";
    @Inject
    private CustomHerbrunConfig config;

    @Provides
    CustomHerbrunConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CustomHerbrunConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private CustomHerbrunOverlay customHerbrunOverlay;

    @Inject
    CustomHerbrunScript herbrunScript;

    static String status;


    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(customHerbrunOverlay);
        }
        herbrunScript.run();
    }

    protected void shutDown() {
        herbrunScript.shutdown();
        overlayManager.remove(customHerbrunOverlay);
        status = null; // Reset status on shutdown
    }
}

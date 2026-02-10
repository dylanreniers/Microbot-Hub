package net.runelite.client.plugins.custom.karambwans;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = "Donder's Karambwan fisher",
        description = "",
        tags = {"GabulhasKarambwans", "Gabulhas"},
        version = GabulhasKarambwansPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class GabulhasKarambwansPlugin extends Plugin {
    public static final String version = "1.1.0";
    @Inject
    GabulhasKarambwansScript gabulhasKarambwansScript;
    @Inject
    private GabulhasKarambwansConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private PluginManager pluginManager;
    @Inject
    private GabulhasKarambwansOverlay gabulhasKarambwansOverlay;

    @Provides
    GabulhasKarambwansConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GabulhasKarambwansConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(gabulhasKarambwansOverlay);
        }
        gabulhasKarambwansScript.run(config);
        GabulhasKarambwansInfo.botStatus = config.STARTING_STATE();
        log.info("bot status {}", GabulhasKarambwansInfo.botStatus);
    }

    protected void shutDown() {
        gabulhasKarambwansScript.shutdown();
        overlayManager.remove(gabulhasKarambwansOverlay);
    }
}

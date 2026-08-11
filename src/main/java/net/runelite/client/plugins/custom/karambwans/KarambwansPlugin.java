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
        tags = {"Karambwans", "karambwan", "fishing"},
        version = KarambwansPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class KarambwansPlugin extends Plugin {
    public static final String version = "2.0.0";
    @Inject
    KarambwansScript karambwansScript;
    @Inject
    private KarambwansConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private PluginManager pluginManager;
    @Inject
    private KarambwansOverlay karambwansOverlay;

    @Provides
    KarambwansConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(KarambwansConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(karambwansOverlay);
        }
        karambwansScript.run(config);
        KarambwansInfo.botStatus = config.STARTING_STATE();
        log.info("bot status {}", KarambwansInfo.botStatus);
    }

    protected void shutDown() {
        karambwansScript.shutdown();
        overlayManager.remove(karambwansOverlay);
    }
}

package net.runelite.client.plugins.custom.customvaletotems;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.UnderDevelopment;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginConstants.MKE + "Vale Totems (Custom)",
        description = "Automated Vale Totems minigame bot with advanced optimizations",
        tags = {"valetotems", "fletching", "minigame"},
        authors = { "Make" },
        version = CustomValeTotemPlugin.version,
        minClientVersion = "1.9.7",
        iconUrl = "https://chsami.github.io/Microbot-Hub/CustomValeTotemPlugin/assets/card.png",
        cardUrl = "https://chsami.github.io/Microbot-Hub/CustomValeTotemPlugin/assets/card.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
@UnderDevelopment
public class CustomValeTotemPlugin extends Plugin {
    static final String version = "1.1.0";

    @Inject
    private CustomValeTotemConfig config;
    
    @Provides
    CustomValeTotemConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CustomValeTotemConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private CustomValeTotemOverlay valeTotemOverlay;

    @Inject
    CustomValeTotemScript valeTotemScript;

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(valeTotemOverlay);
        }
        valeTotemScript.run(config);
        log.info("Vale Totems plugin started");
    }

    protected void shutDown() {
        if (valeTotemScript != null) {
            valeTotemScript.shutdown();
        }
        if (overlayManager != null && valeTotemOverlay != null) {
            overlayManager.remove(valeTotemOverlay);
        }
        log.info("Vale Totems plugin stopped");
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        // Plugin tick events can be used for additional monitoring or UI updates
        // Main bot logic is handled in the CustomValeTotemScript
    }

    /**
     * Get the current script instance
     * @return the vale totem script
     */
    public CustomValeTotemScript getScript() {
        return valeTotemScript;
    }

    /**
     * Get the plugin configuration
     * @return the vale totem config
     */
    public CustomValeTotemConfig getPluginConfig() {
        return config;
    }
}

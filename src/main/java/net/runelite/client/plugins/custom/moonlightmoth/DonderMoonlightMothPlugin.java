package net.runelite.client.plugins.custom.moonlightmoth;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.misc.TimeUtils;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Instant;

@PluginDescriptor(
        name = "Donder's Moonlight Moth",
        description = "Moonlight moth catcher",
        tags = {"moonlight", "moth", "catcher", "microbot", "prayer"},
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
public class DonderMoonlightMothPlugin extends Plugin {
    public static final String version = "1.0.1";
    static final String CONFIG = "moonlightmoth";
    public Instant scriptStartTime;
    @Inject
    public MoonlightMothScript script;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private MoonlightMothConfig config;

    @Inject
    private MoonlightMothOverlay moonlightMothOverlay;

    @Override
    protected void startUp() {
        scriptStartTime = Instant.now();
        if (overlayManager != null) {
            overlayManager.add(moonlightMothOverlay);
        }
        script.run();
    }

    @Override
    protected void shutDown() {
        scriptStartTime = null;
        overlayManager.remove(moonlightMothOverlay);
        script.stop();
    }


    @Provides
    MoonlightMothConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MoonlightMothConfig.class);
    }

    protected String getTimeRunning() {
        return scriptStartTime != null ? TimeUtils.getFormattedDurationBetween(scriptStartTime, Instant.now()) : "";
    }
}

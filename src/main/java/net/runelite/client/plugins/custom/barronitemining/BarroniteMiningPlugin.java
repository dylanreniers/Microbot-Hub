package net.runelite.client.plugins.custom.barronitemining;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Instant;

@PluginDescriptor(
        name = "Barronite Mining (Custom)",
        description = "Mines Barronite deposits in Camdozaal, crushes them into shards, and banks the junk.",
        tags = {"mining", "barronite", "camdozaal", "shards", "skilling", "custom"},
        authors = {"Dylan"},
        version = BarroniteMiningPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class BarroniteMiningPlugin extends Plugin {

    public static final String version = "1.0.0";

    @Getter
    private Instant startTime;

    @Inject
    private BarroniteMiningConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private BarroniteMiningOverlay overlay;
    @Inject
    private BarroniteMiningScript script;

    @Provides
    BarroniteMiningConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BarroniteMiningConfig.class);
    }

    public BarroniteMiningScript getScript() {
        return script;
    }

    @Override
    protected void startUp() {
        startTime = Instant.now();
        if (overlayManager != null) {
            overlayManager.add(overlay);
        }
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        startTime = null;
        if (overlayManager != null) {
            overlayManager.remove(overlay);
        }
    }
}

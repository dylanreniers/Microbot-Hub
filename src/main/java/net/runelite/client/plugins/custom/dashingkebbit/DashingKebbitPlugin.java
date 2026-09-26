package net.runelite.client.plugins.custom.dashingkebbit;

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
        name = "Dashing Kebbit (Custom)",
        description = "Falconry auto-hunter for Dashing kebbits at Piscatoris: travels there, rents a falcon, "
                + "catches kebbits for fur and drops the bones.",
        tags = {"hunter", "falconry", "kebbit", "dashing", "piscatoris", "custom"},
        authors = {"Dylan"},
        version = DashingKebbitPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class DashingKebbitPlugin extends Plugin {

    public static final String version = "1.0.0";

    @Getter
    private Instant startTime;

    @Inject
    private DashingKebbitConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private DashingKebbitOverlay overlay;
    @Inject
    private DashingKebbitScript script;

    @Provides
    DashingKebbitConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(DashingKebbitConfig.class);
    }

    public DashingKebbitScript getScript() {
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

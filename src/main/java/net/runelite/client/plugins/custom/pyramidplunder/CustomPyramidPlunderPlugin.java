package net.runelite.client.plugins.custom.pyramidplunder;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Instant;

@PluginDescriptor(
        name = "Pyramid Plunder (Custom)",
        description = "Automates the Pyramid Plunder minigame: enters via the Guardian mummy, loots chests, "
                + "advances through the rooms, and loots urns at the end.",
        tags = {"thieving", "pyramid", "plunder", "minigame", "sophanem", "custom"},
        authors = {"Dylan"},
        version = CustomPyramidPlunderPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class CustomPyramidPlunderPlugin extends Plugin {

    public static final String version = "1.0.0";

    @Getter
    private Instant startTime;

    @Inject
    private PyramidPlunderConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private PyramidPlunderOverlay overlay;
    @Inject
    private PyramidPlunderScript script;

    @Provides
    PyramidPlunderConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PyramidPlunderConfig.class);
    }

    public PyramidPlunderScript getScript() {
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

    /**
     * Feeds the spear-trap outcome to the script. The trap object stays put after it's disarmed, so the
     * chat message is the only reliable "done" signal: TRUE on success, FALSE on a failed attempt.
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getMessage() == null) {
            return;
        }
        String msg = event.getMessage().toLowerCase();
        if (msg.contains("you deactivate the trap")) {
            script.setTrapDeactivated(true);
        } else if (msg.contains("you fail to deactivate the trap")) {
            script.setTrapDeactivated(false);
        } else if (msg.contains("this door leads to a dead end")) {
            script.setDoorDeadEnd();
        } else if (msg.contains("your attempt fails")) {
            script.setDoorAttemptFailed();
        }
    }
}

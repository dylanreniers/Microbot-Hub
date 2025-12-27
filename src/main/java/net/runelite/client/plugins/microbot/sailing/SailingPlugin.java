package net.runelite.client.plugins.microbot.sailing;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.util.stream.Collectors;

@PluginDescriptor(
	name = PluginConstants.MOCROSOFT + "Sailing",
	description = "Microbot Sailing Plugin",
	tags = {"sailing"},
	authors = { "Mocrosoft" },
	version = SailingPlugin.version,
	minClientVersion = "2.1.0",
	enabledByDefault = PluginConstants.DEFAULT_ENABLED,
	isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class SailingPlugin extends Plugin {

	static final String version = "0.0.1";

    @Inject
    private SailingConfig config;
    @Provides
    SailingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SailingConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private SailingOverlay exampleOverlay;

    @Inject
    private SailingScript sailingScript;


    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(exampleOverlay);
            exampleOverlay.myButton.hookMouseListener();
        }
        sailingScript.run();
    }

    protected void shutDown() {
        sailingScript.shutdown();
        overlayManager.remove(exampleOverlay);
        exampleOverlay.myButton.unhookMouseListener();
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        log.info(getName().chars().mapToObj(i -> (char)(i + 3)).map(String::valueOf).collect(Collectors.joining()));
    }
}

package net.runelite.client.plugins.custom.customdemonicgorilla;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.customdemonicgorilla.utils.FixedSizeQueue;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.misc.TimeUtils;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;

@PluginDescriptor(
        name = PluginDescriptor.TaFCat + "Demonic Gorillas (Custom)",
        description = "Custom build: automates restocking, prayer flicking, and gear switching during Demonic Gorillas",
        tags = {"demonic", "Gorilla", "flicker", "weapon", "switch", "microbot", "custom"},
        version = CustomDemonicGorillaPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class CustomDemonicGorillaPlugin extends Plugin {

    public final static String version = "1.2.5";

    private static final int DEMONIC_GORILLA_ROCK = 856;
    public static FixedSizeQueue<WorldPoint> lastLocation = new FixedSizeQueue<>(2);
    private ScheduledExecutorService scheduledExecutorService;
    @Inject
    private CustomDemonicGorillaConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private CustomDemonicGorillaOverlay demonicGorillaOverlay;
    @Inject
    private CustomDemonicGorillaScript demonicGorillaScript;
    private Instant scriptStartTime;

    private final CustomDemonicGorillaLooterScript lootScript = new CustomDemonicGorillaLooterScript();

    @Provides
    CustomDemonicGorillaConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CustomDemonicGorillaConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        scriptStartTime = Instant.now();
        if (overlayManager != null) {
            overlayManager.add(demonicGorillaOverlay);
        }
        demonicGorillaScript.run(config);
        lootScript.run(config);
    }

    @Override
    protected void shutDown() {
        demonicGorillaScript.shutdown();
        lootScript.shutdown();
        overlayManager.remove(demonicGorillaOverlay);
        if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
            scheduledExecutorService.shutdown();
        }
    }

    protected String getTimeRunning() {
        return scriptStartTime != null ? TimeUtils.getFormattedDurationBetween(scriptStartTime, Instant.now()) : "";
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        final Projectile projectile = event.getProjectile();
        if (projectile.getId() == DEMONIC_GORILLA_ROCK && event.getPosition().equals(Rs2Player.getLocalLocation())) {
            demonicGorillaScript.demonicGorillaRockPosition = event.getPosition();
            demonicGorillaScript.demonicGorillaRockLifeCycle = projectile.getEndCycle();
        }
    }

    @Subscribe
    public void onGameTick(GameTick gameTick) {
        var currentLocation = Rs2Player.getWorldLocation();
        CustomDemonicGorillaScript.playerMoved = !lastLocation.contains(currentLocation);
        lastLocation.add(currentLocation);
        CustomDemonicGorillaScript.gameTickCount++;
    }
}

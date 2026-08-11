package net.runelite.client.plugins.custom.microhunter;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.microhunter.scripts.AutoChinScript;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

import static net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity.MODERATE;

@PluginDescriptor(
        name = "Donder's AutoHunter",
        description = "Microbot AutoHunter plugin",
        tags = {"hunter", "microbot"},
        version = AutoHunterPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class AutoHunterPlugin extends Plugin {

    public static final String version = "2.1.0";

    static {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyHunterSetup();
        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.simulateFatigue = true;
        Rs2AntibanSettings.simulateAttentionSpan = true;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.nonLinearIntervals = true;
        Rs2AntibanSettings.dynamicActivity = true;
        Rs2AntibanSettings.profileSwitching = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.moveMouseOffScreen = true;
        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.1;
        Rs2Antiban.setActivityIntensity(MODERATE);
        Rs2Antiban.setActivity(Activity.HUNTING_CARNIVOROUS_CHINCHOMPAS);
    }

    @Inject
    private AutoHunterConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private AutoHunterOverlay autoHunterOverlay;
    @Inject
    private AutoChinScript autoChinScript;

    @Provides
    AutoHunterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AutoHunterConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(autoHunterOverlay);
        }
        // Drive the action pipeline off the game clock (onGameTick) instead of the script's internal
        // fixed-delay executor, so every decision is aligned to a game tick.
        autoChinScript.initialize();
    }

    protected void shutDown() {
        autoChinScript.shutdown();
        overlayManager.remove(autoHunterOverlay);
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        autoChinScript.gameTick();
    }
}

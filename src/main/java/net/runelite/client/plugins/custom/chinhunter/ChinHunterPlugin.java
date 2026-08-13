package net.runelite.client.plugins.custom.chinhunter;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.chinhunter.scripts.ChinHunterScript;
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
        name = "Donder's Chin Hunter",
        description = "Chinchompa Hunter plugin",
        tags = {"hunter", "microbot"},
        version = ChinHunterPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class ChinHunterPlugin extends Plugin {

    public static final String version = "1.0.1";

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
    private ChinHunterConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private ChinHunterOverlay chinHunterOverlay;
    @Inject
    private ChinHunterScript chinHunterScript;

    @Provides
    ChinHunterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ChinHunterConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(chinHunterOverlay);
        }
        chinHunterScript.run();
    }

    protected void shutDown() {
        chinHunterScript.shutdown();
        overlayManager.remove(chinHunterOverlay);
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        chinHunterScript.onGameObjectSpawn(event.getGameObject());
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        chinHunterScript.onItemSpawned(event);
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        chinHunterScript.onItemDespawned(event);
    }
}

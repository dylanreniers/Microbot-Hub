package net.runelite.client.plugins.custom.tithefarming;


import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.UnderDevelopment;
import net.runelite.client.plugins.custom.tithefarming.enums.CustomTitheFarmMaterial;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@PluginDescriptor(
        name = "Tithe Farm (Custom)",
        description = "Plays the Tithe farm minigame for you!",
        tags = {"tithe farming", "microbot", "skills", "minigame"},
        authors = { "Mocrosoft" },
        version = CustomTitheFarmingPlugin.version,
        minClientVersion = "1.9.8.8",
        iconUrl = "https://chsami.github.io/Microbot-Hub/TitheFarmingPlugin/assets/icon.png",
        cardUrl = "https://chsami.github.io/Microbot-Hub/TitheFarmingPlugin/assets/card.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
@UnderDevelopment
public class CustomTitheFarmingPlugin extends Plugin {

    final static String version = "1.1.19";

    @Inject
    public CustomTitheFarmingConfig config;

    @Provides
    CustomTitheFarmingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CustomTitheFarmingConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private CustomTitheFarmingOverlay titheFarmOverlay;

    private final CustomTitheFarmingScript titheFarmScript = new CustomTitheFarmingScript();

    @Override
    protected void startUp() throws AWTException {
		Microbot.pauseAllScripts.compareAndSet(true, false);

        if (config.enableAntiban()){
            Rs2AntibanSettings.naturalMouse = true;
            Rs2Antiban.antibanSetupTemplates.applyFarmingSetup();
            Rs2Antiban.setActivity(Activity.GENERAL_FARMING);
            Rs2Antiban.activateAntiban();
        }

        if (overlayManager != null) {
            overlayManager.add(titheFarmOverlay);
        }
        titheFarmScript.run(config);
    }

    protected void shutDown() {
        titheFarmScript.shutdown();
        overlayManager.remove(titheFarmOverlay);
    }

    // Record every tithe patch's object id the instant it (re)spawns - this is the same event
    // RuneLite's tithe plugin uses, so our state checks flip exactly when the overlay changes colour
    // (orange->blue), with no cache lag.
    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        int id = event.getGameObject().getId();
        if (id >= 27383 && id <= 27426) { // HOSIDIUS_TITHE_EMPTY .. HOSIDIUS_TITHE_C_4_DEAD
            CustomTitheFarmingScript.recordPatch(event.getGameObject().getWorldLocation(), id);
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (CustomTitheFarmMaterial.getSeedForLevel() != null) {
            Item fruit = Arrays.stream(event.getItemContainer().getItems()).filter(x -> x.getId() == CustomTitheFarmMaterial.getSeedForLevel().getFruitId()).findFirst().orElse(null);
            if (fruit != null) {
                CustomTitheFarmingScript.fruits = fruit.getQuantity() - CustomTitheFarmingScript.initialFruit;
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage)
    {
        String message = chatMessage.getMessage();
        if (message.contains("%")) {
            Pattern pattern = Pattern.compile("(\\d+(\\.\\d+)?)%");
            Matcher matcher = pattern.matcher(message);

            if (matcher.find()) {
                String percentage = matcher.group(1);
                CustomTitheFarmingScript.gricollerCanCharges = (int) (Float.parseFloat(percentage));
            }
        } else if (message.equalsIgnoreCase("Gricoller's can is already full.")) {
            CustomTitheFarmingScript.gricollerCanCharges = 100;
        }
        // NOTE: deliberately no longer forcing REFILL_WATERCANS on "no suitable vessel of water".
        // Refilling is restricted to run start + right before harvest so the bot never abandons a
        // busy cycle to run back to the barrel. Carry enough water for a full cycle (Gricoller's can
        // recommended; 8x Watering can(8) only just covers one 20-patch cycle).
    }
}

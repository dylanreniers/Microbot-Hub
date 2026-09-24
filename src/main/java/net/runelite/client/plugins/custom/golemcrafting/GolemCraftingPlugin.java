package net.runelite.client.plugins.custom.golemcrafting;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.golemcrafting.actions.GolemContext;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@PluginDescriptor(
        name = "Golem Crafting (Custom)",
        description = "Fully automated Golem crafting on Wyrmscraig: mine sunstone, chisel cores, build and power golems.",
        tags = {"golem", "crafting", "mining", "wyrmscraig", "skilling", "custom"},
        authors = {"Dylan"},
        version = GolemCraftingPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class GolemCraftingPlugin extends Plugin {

    public static final String version = "1.0.0";

    /** Pulls the fur count out of the pouch's Check message, e.g. "... contains 24 x Dashing kebbit fur". */
    private static final Pattern FUR_COUNT = Pattern.compile("(\\d+)");

    @Inject
    private GolemCraftingConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private GolemCraftingOverlay overlay;
    @Inject
    private GolemCraftingScript script;

    @Provides
    GolemCraftingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GolemCraftingConfig.class);
    }

    public GolemContext getContext() {
        return script.context();
    }

    @Override
    protected void startUp() {
        if (overlayManager != null) {
            overlayManager.add(overlay);
        }
        script.run();
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        if (overlayManager != null) {
            overlayManager.remove(overlay);
        }
    }

    /**
     * Reads the fur count from the pouch's Check message (and the empty message) so the batch can be
     * sized to the furs on hand. Only consumed while a Check is pending, so unrelated "fur" messages
     * don't clobber the count.
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        GolemContext ctx = getContext();
        if (ctx == null) {
            return;
        }
        String msg = event.getMessage();
        if (msg == null) {
            return;
        }
        String lower = msg.toLowerCase();

        // Each carved side prints this — advance the side counter so we reposition for the next side.
        // Must run regardless of the fur-check state (the fur guard below only gates fur parsing).
        if (lower.contains("finish crafting") && lower.contains("angle")) {
            if (ctx.isGolemActive() && ctx.getSidesCarved() < 4) {
                ctx.setSidesCarved(ctx.getSidesCarved() + 1);
                log.info("[golem] side carved — {}/4", ctx.getSidesCarved());
            }
            return;
        }

        // Fur count parsing only matters right after we click Check.
        if (!ctx.isFurCheckPending() || !lower.contains("fur")) {
            return;
        }
        if (lower.contains("empty") || lower.contains("no fur") || lower.contains("nothing")) {
            ctx.setFurRemaining(0);
            ctx.setFurCheckPending(false);
            log.info("[golem] fur pouch reports empty");
            return;
        }
        Matcher m = FUR_COUNT.matcher(msg);
        if (m.find()) {
            int count = Integer.parseInt(m.group(1));
            ctx.setFurRemaining(count);
            ctx.setFurCheckPending(false);
            log.info("[golem] fur pouch holds {} furs", count);
        }
    }
}

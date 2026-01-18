package net.runelite.client.plugins.custom.gotr;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerPlugin;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.pouch.PouchOverlay;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.plugins.custom.gotr.services.TimerService;
import net.runelite.client.plugins.custom.gotr.services.MiningService;

import javax.inject.Inject;
import java.awt.*;
import java.time.Instant;
import java.util.regex.Matcher;

@PluginDescriptor(
        name = PluginDescriptor.Mocrosoft + "GuardiansOfTheRift",
        description = "Guardians of the rift plugin",
        tags = {"runecrafting", "guardians of the rift", "gotr", "microbot"},
        version = GotrPlugin.version,
        minClientVersion = "2.1.0",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class GotrPlugin extends Plugin {
    public static final String version = "1.5.0";

    @Inject
    private GotrConfig config;

    @Provides
    GotrConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GotrConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private GotrOverlay gotrOverlay;
    @Inject
    private PouchOverlay pouchOverlay;
    @Inject
    private GotrScript gotrScript;

    @Inject
    private TimerService timerService;

    @Inject
    private MiningService miningService;

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(pouchOverlay);
            overlayManager.add(gotrOverlay);
        }

        // Initialize pre/post schedule tasks
        if (Microbot.isLoggedIn()) {
                log.info("GOTR Plugin started in Normal Mode");
                // In normal mode, start the script directly
                gotrScript.run(config);
        }
    }

    protected void shutDown() {
        gotrScript.shutdown();
        overlayManager.remove(gotrOverlay);
        overlayManager.remove(pouchOverlay);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            gotrScript.resetGameState();
        }
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned npcSpawned) {
        NPC npc = npcSpawned.getNpc();
        if (npc.getId() == GotrConstants.GREAT_GUARDIAN_ID) {
            miningService.setGreatGuardian(npc);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned npcDespawned) {
        NPC npc = npcDespawned.getNpc();
        if (npc.getId() == GotrConstants.GREAT_GUARDIAN_ID) {
            miningService.setGreatGuardian(null);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getType() != ChatMessageType.SPAM && chatMessage.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }

        String msg = chatMessage.getMessage();

        if (msg.contains("You step through the portal")) {
            Microbot.getClient().clearHintArrow();
            timerService.resetForNewGame();
        }

        if (msg.contains("The rift becomes active!")) {
            if (Microbot.isPluginEnabled(BreakHandlerPlugin.class)) {
                BreakHandlerScript.setLockState(true);
            }
            timerService.resetForNewGame();
            timerService.markPortalSpawn();
            gotrScript.setShouldMineGuardianRemains(true);
            gotrScript.setState(GotrState.ENTER_GAME);
        } else if (msg.contains("The rift will become active in 30 seconds.")) {
            if (Microbot.isPluginEnabled(BreakHandlerPlugin.class)) {
                BreakHandlerScript.setLockState(true);
            }
            gotrScript.setShouldMineGuardianRemains(true);
            timerService.setNextGameStart(Instant.now().plusSeconds(30));
        } else if (msg.contains("The rift will become active in 10 seconds.")) {
            gotrScript.setShouldMineGuardianRemains(true);
            timerService.setNextGameStart(Instant.now().plusSeconds(10));
        } else if (msg.contains("The rift will become active in 5 seconds.")) {
            gotrScript.setShouldMineGuardianRemains(true);
            timerService.setNextGameStart(Instant.now().plusSeconds(5));
        } else if (msg.contains("The Portal Guardians will keep their rifts open for another 30 seconds.")) {
            gotrScript.setShouldMineGuardianRemains(true);
            timerService.setNextGameStart(Instant.now().plusSeconds(60));
        } else if (msg.toLowerCase().contains("closed the rift!") || msg.toLowerCase().contains("the great guardian was defeated!")) {
            if (Microbot.isPluginEnabled(BreakHandlerPlugin.class)) {
                Global.sleep(Rs2Random.randomGaussian(2000, 300));
                BreakHandlerScript.setLockState(false);
            }
            gotrScript.setShouldMineGuardianRemains(true);
        }

        Matcher rewardPointMatcher = GotrConstants.REWARD_POINT_PATTERN.matcher(msg);
        if (rewardPointMatcher.find()) {
            gotrScript.setElementalRewardPoints(Integer.parseInt(rewardPointMatcher.group(1).replaceAll(",", "")));
            gotrScript.setCatalyticRewardPoints(Integer.parseInt(rewardPointMatcher.group(2).replaceAll(",", "")));
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject gameObject = event.getGameObject();
        if (gotrScript.isGuardianPortal(gameObject)) {
            gotrScript.addGuardian(gameObject);
        }

        if (gameObject.getId() == GotrConstants.PORTAL_ID) {
            Microbot.getClient().setHintArrow(gameObject.getWorldLocation());
            timerService.markPortalSpawn();
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject gameObject = event.getGameObject();

        gotrScript.removeGuardian(gameObject);
        gotrScript.removeActivePortal(gameObject);

        if (gameObject.getId() == GotrConstants.PORTAL_ID) {
            Microbot.getClient().clearHintArrow();
            timerService.markPortalSpawn();
        }
    }
}

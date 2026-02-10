package net.runelite.client.plugins.custom.moonsofperil;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.moonsofperil.enums.GameObjects;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.time.Instant;

@PluginDescriptor(
        name = "Donder's Moons of Peril",
        description = "A plugin to farm the Moons of Peril",
        tags = {"bossing", "pvm", "moneymaking", "combat", "microbot"},
        authors = "Donder",
        version = MoonsOfPerilPlugin.version,
        minClientVersion = "2.0.14",
        iconUrl = "https://chsami.github.io/Microbot-Hub/MoonsOfPerilPlugin/assets/icon.png",
        cardUrl = "https://chsami.github.io/Microbot-Hub/MoonsOfPerilPlugin/assets/card.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class MoonsOfPerilPlugin extends Plugin {

    static final String version = "2.0.0";
    public static int bloodPoolTick;
    public static Instant scriptStartTime;
    @Inject
    MoonsOfPerilScript moonsOfPerilScript;
    @Inject
    private MoonsOfPerilConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private MoonsOfPerilOverlay moonsOfPerilOverlay;
    @Inject
    private MoonsOfPerilConfig moonsOfPerilConfig;

    @Provides
    MoonsOfPerilConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MoonsOfPerilConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(moonsOfPerilOverlay);
        }
        moonsOfPerilScript.run();
        Rs2Tile.init();
        scriptStartTime = Instant.now();
    }

    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        final GraphicsObject graphicEvent = event.getGraphicsObject();
        if (graphicEvent.getId() == SpotanimID.VFX_DJINN_ICE_FLOOR_SPAWN_01) {
            Rs2Tile.addDangerousGraphicsObjectTile(graphicEvent, 600 * 3);
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        final GameObject bloodPool = event.getGameObject();
        if (bloodPool.getId() == ObjectID.PMOON_BOSS_BLOOD_POOL) {
            bloodPoolTick = 0;
        }
    }

    protected void shutDown() {
        moonsOfPerilScript.shutdown();
        overlayManager.remove(moonsOfPerilOverlay);
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned npcSpawned) {
        NPC npc = npcSpawned.getNpc();
        if (npc.getId() == GameObjects.SIGIL_NPC_ID.getID()) {
            log.info("Spawned sigil.");
            log.info("Location: {}", npc.getWorldLocation());
            MoonsOfPerilScript.sigilNpc.set(npc);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned npcDespawned) {
        NPC npc = npcDespawned.getNpc();
        if (npc.getId() == GameObjects.SIGIL_NPC_ID.getID()) {
            log.info("Despawned sigil.");
            if (npc.getIndex() == MoonsOfPerilScript.sigilNpc.get().getIndex()) {
                log.info("Despawned and no other sigil was previously set.");
                MoonsOfPerilScript.sigilNpc.set(null);
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        bloodPoolTick++;
    }
}

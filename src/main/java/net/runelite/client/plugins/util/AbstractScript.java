package net.runelite.client.plugins.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class AbstractScript extends Script {

    @Inject
    protected Rs2NpcCache rs2NpcCache;
    @Inject
    protected Rs2TileObjectCache rs2TileObjectCache;
    @Inject
    protected Rs2TileItemCache rs2TileItemCache;

    public abstract void tick();

    public void initialize() {

    }

    public void onException(Exception e) {

    }

    @Override
    public boolean run() {
        initialize();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() ->
        {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }

                tick();
            } catch (Exception ex) {
                onException(ex);
                log.error("Exception during tick.", ex);
            }
        }, 0, getTickDelay(), TimeUnit.MILLISECONDS);

        return true;
    }

    public int getTickDelay() {
        return 100;
    }
}

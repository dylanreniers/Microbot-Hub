package net.runelite.client.plugins.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class AbstractScript extends Script {

    @Inject
    protected Rs2NpcCache rs2NpcCache;
    @Inject
    protected Rs2TileObjectCache rs2TileObjectCache;

    public abstract void tick();

    public abstract void initialize();

    public abstract void onException(Exception e);

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
            }
        }, 0, getTickDelay(), TimeUnit.MILLISECONDS);

        return true;
    }

    public int getTickDelay() {
        return 100;
    }
}

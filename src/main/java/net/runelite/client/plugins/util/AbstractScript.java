package net.runelite.client.plugins.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractScript extends Script {

    @Inject
    protected Rs2NpcCache rs2NpcCache;
    @Inject
    protected Rs2TileObjectCache rs2TileObjectCache;
    @Inject
    protected Rs2TileItemCache rs2TileItemCache;

    // Thread-safe: futures are submitted from both the tick thread and worker threads.
    private final List<Future<?>> futures = new CopyOnWriteArrayList<>();

    // Guards {@link #gameTick()} so an externally-driven tick never stacks on the previous one.
    private final AtomicBoolean tickInProgress = new AtomicBoolean(false);

    public abstract void tick();

    public void initialize() {

    }

    public void onException(Exception e) {

    }

    @Override
    public void shutdown() {
        futures.forEach(future -> {
            if (future != null && !future.isCancelled()) {
                future.cancel(true);
            }
        });
        futures.clear();

        onShutdown();
        super.shutdown();
    }

    public void onShutdown() {

    }

    @Override
    public boolean run() {
        initialize();
        futures.clear();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
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

    /**
     * Runs a single guarded tick, driven externally (e.g. a plugin's onGameTick) instead of the
     * internal fixed-delay scheduler in {@link #run()}. The tick body is dispatched to the script's
     * worker pool rather than run inline: onGameTick fires on the client thread, but Rs2 walking /
     * clicking use blocking sleeps and must not run there. Applies the same login and base-{@link
     * Script} gate ({@code super.run()}) the scheduler loop uses, and skips overlapping ticks so a
     * slow tick never stacks on the next one. Call {@link #initialize()} once before ticking.
     */
    public void gameTick() {
        if (!tickInProgress.compareAndSet(false, true)) {
            return; // previous tick still running; let it finish rather than stacking work
        }
        scheduledExecutorService.submit(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }
                tick();
            } catch (Exception ex) {
                onException(ex);
                log.error("Exception during tick.", ex);
            } finally {
                tickInProgress.set(false);
            }
        });
    }

    public ScheduledFuture<?> executeOnSeparateThread(Runnable runnable, long initialDelay, long delay) {
        var future = scheduledExecutorService.scheduleWithFixedDelay(runnable, initialDelay, delay, TimeUnit.MILLISECONDS);
        futures.add(future);
        return future;
    }

    public Future<?> executeOnSeparateThread(Runnable runnable) {
        var future = scheduledExecutorService.submit(runnable);
        futures.add(future);
        return future;
    }

    public int getTickDelay() {
        return 100;
    }
}

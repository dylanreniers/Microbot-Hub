package net.runelite.client.plugins.microbot.sailing;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.sailing.features.PortTaskFeature;
import net.runelite.client.plugins.microbot.sailing.features.SalvagingFeature;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SailingScript extends Script {

    private final SailingPlugin plugin;
	private final SailingConfig config;

    private final SalvagingFeature salvagingFeature = new SalvagingFeature();
    private final PortTaskFeature portTaskFeature = new PortTaskFeature();

	@Inject
	public SailingScript(SailingPlugin plugin, SailingConfig config) {
		this.plugin = plugin;
		this.config = config;
	}

    public boolean run() {
        Microbot.enableAutoRunOn = false;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) {
                    return;
                }
                long startTime = System.currentTimeMillis();

                log.info("Salvaging? {}", config.salvaging());

                if (config.salvaging()) {
                    salvagingFeature.run(config);
                } else {
                    portTaskFeature.run(config);
                }

                long endTime = System.currentTimeMillis();
                long totalTime = endTime - startTime;
                log.info("Total time for loop {}ms", totalTime);

            } catch (Exception ex) {
                log.trace("Exception in main loop: ", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}

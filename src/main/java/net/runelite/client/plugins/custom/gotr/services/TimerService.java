package net.runelite.client.plugins.custom.gotr.services;

import lombok.Setter;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.custom.gotr.GotrConstants;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Singleton;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Service for handling timer-related functionality in GOTR
 */
@Singleton
public class TimerService {

    private Optional<Instant> nextGameStart = Optional.empty();
    private Optional<Instant> timeSincePortal = Optional.empty();
    private boolean isFirstPortal = true;

    /**
     * Gets the start timer from the game widget
     * @return seconds until game start, or -1 if timer not available
     */
    public int getStartTimer() {
        Widget timerWidget = Rs2Widget.getWidget(GotrConstants.TIMER_WIDGET_ID);
        if (timerWidget == null) {
            return -1;
        }

        String timer = timerWidget.getText();
        if (timer == null) {
            return -1;
        }

        return parseTimerString(timer);
    }

    /**
     * Gets the time elapsed since the last portal spawned
     * @return seconds since portal, or -1 if not available
     */
    public int getTimeSincePortal() {
        if (getStartTimer() == -1) {
            return -1;
        }

        int firstPortalAdjustment = isFirstPortal ? GotrConstants.FIRST_PORTAL_TIME_ADJUSTMENT : 0;
        return timeSincePortal
            .map(instant -> (int) ChronoUnit.SECONDS.between(instant, Instant.now()) - firstPortalAdjustment)
            .orElse(-1);
    }

    /**
     * Gets the time until the next game starts
     * @return seconds until next game, or 0 if not available
     */
    public int getTimeToStart() {
        return nextGameStart
            .map(instant -> (int) ChronoUnit.SECONDS.between(Instant.now(), instant))
            .orElse(0);
    }

    /**
     * Marks the start of a new portal spawn
     */
    public void markPortalSpawn() {
        timeSincePortal = Optional.of(Instant.now());
        if (isFirstPortal) {
            isFirstPortal = false;
        }
    }

    /**
     * Resets all timers for a new game
     */
    public void resetForNewGame() {
        nextGameStart = Optional.empty();
        timeSincePortal = Optional.empty();
        isFirstPortal = true;
    }

    public void setNextGameStart(Instant nextGameStart) {
        this.nextGameStart = Optional.of(nextGameStart);
    }

    private int parseTimerString(String timer) {
        String[] timeParts = timer.split(":");
        if (timeParts.length != 2) {
            return -1;
        }

        try {
            int minutes = Integer.parseInt(timeParts[0]);
            int seconds = Integer.parseInt(timeParts[1]);
            return (minutes * 60) + seconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

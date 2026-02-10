package net.runelite.client.plugins.custom.gotr.services;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.gotr.GotrConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Singleton;

/**
 * Service for handling location-based checks in GOTR minigame
 */
@Singleton
public class LocationService {

    /**
     * Checks if the player is outside the barrier
     */
    public boolean isOutsideBarrier() {
        WorldPoint location = Rs2Player.getWorldLocation();
        return location.getY() <= GotrConstants.OUTSIDE_BARRIER_Y
                && location.getRegionID() == GotrConstants.GOTR_REGION_ID;
    }

    /**
     * Checks if the player is in the large mine
     */
    public boolean isInLargeMine() {
        WorldPoint location = Rs2Player.getWorldLocation();
        return location.getRegionID() == GotrConstants.GOTR_REGION_ID
                && location.getX() >= GotrConstants.LARGE_MINE_X;
    }

    /**
     * Checks if the player is in the huge mine (portal area)
     */
    public boolean isInHugeMine() {
        WorldPoint location = Rs2Player.getWorldLocation();
        return location.getRegionID() == GotrConstants.GOTR_REGION_ID
                && location.getX() <= GotrConstants.HUGE_MINE_X;
    }

    /**
     * Checks if the player is in the main GOTR region
     */
    public boolean isInMainRegion() {
        return Rs2Player.getWorldLocation().getRegionID() == GotrConstants.GOTR_REGION_ID;
    }

    /**
     * Checks if the player is in the minigame (has the widget visible)
     */
    public boolean isInMinigame() {
        return Microbot.getClient().getWidget(GotrConstants.MINIGAME_WIDGET_ID) != null;
    }

    /**
     * Determines if the player is currently in the active minigame area
     */
    public boolean isInActiveMinigame() {
        return !isOutsideBarrier() && isInMainRegion();
    }
}

package net.runelite.client.plugins.microbot.zulrah.constants;

import net.runelite.api.coords.LocalPoint;

public enum ZulrahLocation {
    NORTH(6720, 7616),
    EAST(8000, 7360),
    SOUTH(6720, 6208),
    WEST(5440, 7360);

    private final int localX;
    private final int localY;

    private ZulrahLocation(final int localX, final int localY) {
        this.localX = localX;
        this.localY = localY;
    }

    public static ZulrahLocation valueOf(final LocalPoint localPoint) {
        for (final ZulrahLocation loc : values()) {
            if (loc.toLocalPoint().equals(localPoint)) {
                return loc;
            }
        }
        return null;
    }

    public LocalPoint toLocalPoint() {
        return new LocalPoint(this.localX, this.localY);
    }
}

package net.runelite.client.plugins.custom.moonsofperil.handlers;

import net.runelite.client.plugins.custom.moonsofperil.enums.State;

public interface BaseHandler {
    /**
     * Should we run now?
     */
    boolean validate();

    /**
     * Do the work for this state.
     *
     * @return
     */
    State execute();
}

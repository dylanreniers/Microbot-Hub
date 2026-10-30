package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;

/**
 * Logs the tick-stamped events the plugin's client-thread handlers queue (attacks counted, soul
 * spawns/animations, prayer switches, hits on us). Logging happens here, off the client thread, because
 * logging inside a handler can deadlock the game loop through Microbot's GameChatAppender.
 */
@Slf4j
public class DiagnosticsAction implements CerberusAction {

    @Override
    public int order() {
        return 0;
    }

    @Override
    public String key() {
        return "diagnostics";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        return !state.context().getEventLog().isEmpty();
    }

    @Override
    public Object execute(CerberusState state) {
        String event;
        while ((event = state.context().getEventLog().poll()) != null) {
            log.info("[cerberus] {}", event);
        }
        return null;
    }
}

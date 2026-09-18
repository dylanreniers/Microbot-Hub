package net.runelite.client.plugins.custom.abyssalsire.actions;

import net.runelite.client.plugins.custom.actions.Action;

/**
 * Marker interface for Abyssal Sire actions so the {@link net.runelite.client.plugins.custom.actions.ActionRegistry}
 * discovers them by scanning this package. Bound to {@link SireState}.
 */
public interface SireAction extends Action<SireState> {
}

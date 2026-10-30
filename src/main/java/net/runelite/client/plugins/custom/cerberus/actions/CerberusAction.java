package net.runelite.client.plugins.custom.cerberus.actions;

import net.runelite.client.plugins.custom.actions.Action;

/**
 * An {@link Action} bound to {@link CerberusState}. Every implementation in this package is
 * auto-discovered by the pipeline and run each tick in ascending {@link #order()}.
 */
public interface CerberusAction extends Action<CerberusState> {
}

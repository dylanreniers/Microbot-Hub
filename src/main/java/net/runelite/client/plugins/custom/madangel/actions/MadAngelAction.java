package net.runelite.client.plugins.custom.madangel.actions;

import net.runelite.client.plugins.custom.actions.Action;

/**
 * An {@link Action} bound to {@link MadAngelState}, so Mad Angel actions get typed access to the
 * {@link MadAngelContext} and config without a cast. Every implementation in this package is
 * auto-discovered by the pipeline and run each tick in ascending {@link #order()}.
 */
public interface MadAngelAction extends Action<MadAngelState> {
}

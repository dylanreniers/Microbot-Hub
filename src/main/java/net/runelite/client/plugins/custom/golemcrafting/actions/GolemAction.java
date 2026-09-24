package net.runelite.client.plugins.custom.golemcrafting.actions;

import net.runelite.client.plugins.custom.actions.Action;

/** Marker interface so {@link net.runelite.client.plugins.custom.actions.ActionRegistry} discovers
 *  every golem-crafting action in this package and binds it to {@link GolemState}. */
public interface GolemAction extends Action<GolemState> {
}

package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import net.runelite.client.plugins.custom.actions.Action;

/**
 * An {@link Action} bound to {@link GorillaState}, so Demonic Gorilla actions get typed access to
 * the {@link GorillaContext} and config without a cast. Concrete gorilla actions implement this
 * rather than {@code Action} directly; every implementation in this package is auto-discovered and
 * ordered by {@link #order()}.
 */
public interface GorillaAction extends Action<GorillaState> {
}

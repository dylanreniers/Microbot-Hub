package net.runelite.client.plugins.custom.microhunter.actions;

import net.runelite.client.plugins.custom.actions.Action;

/**
 * An {@link Action} bound to {@link HunterState}, so hunter actions get typed access to the
 * per-tick pattern-tile snapshot and durable {@link HunterContext} without a cast. Concrete hunter
 * actions implement this rather than {@code Action} directly.
 */
public interface HunterAction extends Action<HunterState> {
}

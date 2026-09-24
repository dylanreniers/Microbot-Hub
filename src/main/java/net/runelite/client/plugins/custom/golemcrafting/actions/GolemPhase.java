package net.runelite.client.plugins.custom.golemcrafting.actions;

/** High-level phase of the golem-crafting loop; each {@link GolemAction} gates on it for mutual exclusion. */
public enum GolemPhase {
    /** One-time validation of tools/pouch on start. */
    SETUP,
    /** Mining sunstone until we hold enough for a full batch. */
    MINING,
    /** Chiselling the batch's cores out of the mined sunstone. */
    CHISELING,
    /** Building golems at the plinths (start / shape / insert-core) and looting gems. */
    CRAFTING,
    /** Refilling the fur pouch at the bank chest. */
    BANKING,
    /** Terminal: stop requested (missing tools, out of resources with banking off, etc.). */
    STOPPED
}

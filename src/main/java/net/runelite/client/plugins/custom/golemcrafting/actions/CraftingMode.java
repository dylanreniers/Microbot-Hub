package net.runelite.client.plugins.custom.golemcrafting.actions;

/** How each golem side is carved. */
public enum CraftingMode {
    /** One click per side, then wait for it to finish. Slowest, lowest click rate. */
    LAZY,
    /** Re-click the statue every 500–800 ms while carving a side. Faster (3 ticks/side vs 4). */
    SPAM_CLICK,
    /** One click 100–300 ms after each XP drop. Same speed-up as spam but far fewer clicks. */
    PERFECT
}

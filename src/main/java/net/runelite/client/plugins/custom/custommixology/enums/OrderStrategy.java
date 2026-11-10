package net.runelite.client.plugins.custom.custommixology.enums;

/**
 * Which of the three orders to make, following the OSRS wiki Mastering Mixology strategies page.
 */
public enum OrderStrategy {
    // Make every order: simplest, always gets the multi-order bonus.
    ALL_ORDERS("All orders"),
    // Skip single-paste (AAA/MMM/LLL) potions; if only singles are offered, make the fastest one.
    EXPERIENCE("Experience"),
    // Skip singles unless a MAL is offered (then make all); only-singles -> most wanted resin.
    TOTAL_POINTS("Total points"),
    // Only spend effort where the target resin is earned.
    SINGLE_RESIN("Single resin");

    private final String name;

    OrderStrategy(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}

package net.runelite.client.plugins.custom.abyssalsire.actions;

/** The stage of the Abyssal Sire fight, driving which combat action owns the tick. */
public enum SirePhase {
    /** No fight active (waiting for the Sire / respiratory systems to be present). */
    IDLE,
    /** Barrage the Sire to stun the tentacles, then range down the four respiratory systems. */
    PHASE1,
    /** Respiratory systems cleared (Sire back to 5886) until it hits 50% HP: melee under Protect from Melee. */
    PHASE2,
    /** Sire below 50% HP: melee under Protect from Missiles, dodging the miasma and explosion. */
    PHASE3,
    /** Sire dead: loot the drop, then reset for the next kill. */
    DEAD
}

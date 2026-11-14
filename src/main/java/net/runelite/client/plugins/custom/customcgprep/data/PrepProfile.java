package net.runelite.client.plugins.custom.customcgprep.data;

import java.util.EnumMap;
import java.util.Map;

/**
 * Target loadouts. The planner shops for whatever is missing between the current ledger and the
 * profile, and degrades gracefully (see PrepPlanner) when the timer runs short.
 */
public enum PrepProfile
{
	/** The project's goal: T1 armour, perfected bow + perfected staff. Needs Dark Beast + Dragon. */
	T1_ARMOUR_T3_BOW_STAFF("T1 armour + T3 bow + T3 staff", 1, 1, 1, 3, 3, 0, 3),
	/**
	 * T2 armour with both T3 weapons: +200 shards and +4 each of ore/bark/linum over the T1 set
	 * (7 of each in total, 3 nodes per type). When short, the T3 bow is crafted before the T2
	 * upgrades (Needs.craftOrder), and the upgrades go body -> legs -> helm.
	 */
	T2_ARMOUR_T3_BOW_STAFF("T2 armour + T3 bow + T3 staff", 2, 2, 2, 3, 3, 0, 3),
	/**
	 * Full T3 (perfected) armour with both T3 weapons: 650 shards and 13 each of ore/bark/linum for
	 * the armour (+300 shards, +6 of each over T2). For accounts with low Defence (see the README's
	 * loadout table).
	 */
	T3_ARMOUR_T3_BOW_STAFF("T3 armour + T3 bow + T3 staff", 3, 3, 3, 3, 3, 0, 3),
	/** Speed variant: perfected bow, basic staff. Only needs the Dark Beast. */
	T1_ARMOUR_T3_BOW_T1_STAFF("T1 armour + T3 bow + T1 staff", 1, 1, 1, 3, 1, 0, 3),
	/**
	 * One demi-boss (user, 1.9.3): T1 armour and one T3 weapon, the other at T2. Whichever part
	 * comes first decides (bowstring: T3 bow + T2 staff, orb: T3 staff + T2 bow); until then both
	 * are hunted, so the nearest Dark Beast or Dragon does. Resolved in PrepPlanner.profileNow.
	 */
	T1_ARMOUR_ONE_T3_WEAPON("T1 armour + T3 bow/staff (first demi decides) + T2 other", 1, 1, 1, 3, 3, 0, 3),
	/** T1_ARMOUR_ONE_T3_WEAPON after a bowstring. */
	T1_ARMOUR_T3_BOW_T2_STAFF("T1 armour + T3 bow + T2 staff", 1, 1, 1, 3, 2, 0, 3),
	/** T1_ARMOUR_ONE_T3_WEAPON after an orb. */
	T1_ARMOUR_T2_BOW_T3_STAFF("T1 armour + T2 bow + T3 staff", 1, 1, 1, 2, 3, 0, 3),
	/** As T1_ARMOUR_ONE_T3_WEAPON with T2 armour (user, 1.9.5). */
	T2_ARMOUR_ONE_T3_WEAPON("T2 armour + T3 bow/staff (first demi decides) + T2 other", 2, 2, 2, 3, 3, 0, 3),
	/** T2_ARMOUR_ONE_T3_WEAPON after a bowstring. */
	T2_ARMOUR_T3_BOW_T2_STAFF("T2 armour + T3 bow + T2 staff", 2, 2, 2, 3, 2, 0, 3),
	/** T2_ARMOUR_ONE_T3_WEAPON after an orb. */
	T2_ARMOUR_T2_BOW_T3_STAFF("T2 armour + T2 bow + T3 staff", 2, 2, 2, 2, 3, 0, 3),
	/** As T1_ARMOUR_ONE_T3_WEAPON with the other weapon at T1 (user, 1.9.9). */
	T1_ARMOUR_ONE_T3_WEAPON_T1_OTHER("T1 armour + T3 bow/staff (first demi decides) + T1 other", 1, 1, 1, 3, 3, 0, 3),
	/** T1_ARMOUR_ONE_T3_WEAPON_T1_OTHER after an orb (after a bowstring: T1_ARMOUR_T3_BOW_T1_STAFF). */
	T1_ARMOUR_T1_BOW_T3_STAFF("T1 armour + T1 bow + T3 staff", 1, 1, 1, 1, 3, 0, 3);

	public final String label;
	public final Map<GearSlot, Integer> targetTier = new EnumMap<>(GearSlot.class);
	public final int potions;

	PrepProfile(String label, int helm, int body, int legs, int bow, int staff, int halberd, int potions)
	{
		this.label = label;
		targetTier.put(GearSlot.HELM, helm);
		targetTier.put(GearSlot.BODY, body);
		targetTier.put(GearSlot.LEGS, legs);
		targetTier.put(GearSlot.BOW, bow);
		targetTier.put(GearSlot.STAFF, staff);
		targetTier.put(GearSlot.HALBERD, halberd);
		this.potions = potions;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

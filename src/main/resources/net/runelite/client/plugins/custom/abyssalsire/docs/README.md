# Abyssal Sire

A full-fight helper for the Abyssal Sire. Assumes you're already in the arena — it fights, loots,
and waits for the next kill (in-fight helper only; no banking/travel).

## Setup

1. Create two [Inventory Setups](https://github.com/chsami/Microbot):
   - **Range** — used for phase 1. Must include the runes / rune pouch for **Shadow Barrage** (you must
     be on the **Ancient** spellbook) plus your ranged weapon and ammo, antipoison/antivenom, food and
     prayer potions.
   - **Melee** — used for phases 2-3.
2. In the plugin config, select the Range and Melee setups and set your **Eat %**, **Restore prayer %**,
   and **Min loot value** thresholds.
3. Stand in the arena at the original spot and enable the plugin.

## Fight flow

| Phase | Trigger | Behaviour |
|-------|---------|-----------|
| 1 | Fight start | Cast Shadow Barrage on the Sire to stun the tentacles, wait ~3s and for the tentacles to sleep, then range down the four respiratory systems. Re-barrage from the original spot if a tentacle wakes. |
| 2 | Respiratory systems cleared (Sire stands, id 5886) | Melee the Sire under **Protect from Melee** + best offensive melee prayer. Step 2 tiles east off a miasma pool, back when it clears. |
| 3 | Sire walks to the centre (id 5889) | Melee under **Protect from Missiles**. Dodge miasma pools (toggle between two tiles) and run to the escape tile when the explosion knocks you back (player animation 1816), resuming once the Sire recovers (id 5908). |
| Loot | Sire dies (animation 7100) | Pick up the Unsired and any drop at/above the configured value, then reset for the next kill. |

Each tick the plugin also eats, restores prayer, and keeps anti-poison/venom up against the configured
thresholds.

## Notes

- The phase-3 miasma and explosion dodge tiles are hardcoded arena coordinates; if the dodge looks off,
  the tile constants live in `constants/SireConstants.java`.
- World-hop before a fight if another player is present can be toggled in the config.

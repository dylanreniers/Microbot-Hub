# Basilisk Knights (Custom)

A fully-automated Basilisk Knight killer for Jormungand's Prison, using the Lunar Isle travel route.
It handles travel, ranging, prayer, looting and restocking at Ferox Enclave — start it from anywhere.

## Setup

1. Create an [Inventory Setup](https://github.com/chsami/Microbot) for your kill trip. It must include:
   - Your **ranged** weapon, armour and ammunition.
   - **Food** and **prayer restoration** — any mix of prayer potions, caught **moonlight moths**
     (butterfly jars) and **moonlight moth mixes**.
   - A **Teleport to House** method (standard-spellbook runes, so the plugin can cast it).
2. Your POH must contain:
   - A **Lunar Isle Portal** (used to reach Lunar Isle).
   - A **jewellery box** with the **Ferox Enclave** teleport (used to restore and resupply).
3. In the config, select the setup as **Gear & Inventory setup** and set **Minimum Health %**,
   **Minimum Prayer %** and **Only loot my loot**.
4. Enable the plugin.

## Flow

| Phase | Behaviour |
|-------|-----------|
| Travel | Teleport to House → enter the **Lunar Isle Portal** → walk to `(2099, 3914)` → Talk-to **Bouquet Mac Hyacinth** and advance the dialogue → **Travel** on the Fremennik Boat → **Enter** the Cave → wait until underground at `(2461, 10417)`, then walk to the stand tile `(2454, 10395)`. |
| Fighting | Range the nearest Basilisk Knight from the fixed stand tile, walking back to it if pushed off. **Protect from Magic** is kept on only while a knight is attacking you, and dropped otherwise. |
| Looting | After each kill, pick up every drop within range **except big bones**, eating food to free a slot when the inventory is full. |
| Restock | When you run out of all prayer restoration items, the current kill is finished first, then: Teleport to House → jewellery box to **Ferox Enclave** → drink the pool to full → deposit and reload the setup at the bank → start the cycle again. |

Each tick during a fight the plugin also eats at your health threshold and restores prayer at your
prayer threshold, preferring a free moonlight moth release, then a moth mix, then a prayer potion.

## Notes

- The NPC id (`9293`), boat (`37407`), cave (`37410`), arrival and stand-tile coordinates are hardcoded
  route constants in `BasiliskKnightsScript.java`; adjust them there if the route changes.
- On startup, if your gear/inventory doesn't already match the selected setup, the plugin runs the Ferox
  restock trip first before heading out.

package net.runelite.client.plugins.microbot.jewelleryenchant.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.magic.Spell;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.HashMap;

@Getter
@RequiredArgsConstructor
public enum Jewellery implements Spell {

    // --- Level 1 Enchant (Lvl 7 Magic) ---
    SAPPHIRE_RING("Sapphire ring", ItemID.SAPPHIRE_RING, ItemID.GOLD_BAR, ItemID.SAPPHIRE, ItemID.RING_MOULD, 7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),
    SAPPHIRE_AMULET("Sapphire amulet", ItemID.SAPPHIRE_AMULET, ItemID.GOLD_BAR, ItemID.SAPPHIRE, ItemID.AMULET_MOULD, 7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),
    SAPPHIRE_NECKLACE("Sapphire necklace", ItemID.SAPPHIRE_NECKLACE, ItemID.GOLD_BAR, ItemID.SAPPHIRE, ItemID.NECKLACE_MOULD, 7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),
    SAPPHIRE_BRACELET("Sapphire bracelet", ItemID.SAPPHIRE_BRACELET, ItemID.GOLD_BAR, ItemID.SAPPHIRE, ItemID.BRACELET_MOULD, 7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),

    // NEW: Lvl-1 Enchant Additions
    OPAL_RING("Opal ring", ItemID.OPAL_RING, ItemID.SILVER_BAR, ItemID.OPAL, ItemID.RING_MOULD, 7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),
    OPAL_AMULET("Opal amulet", ItemID.OPAL_AMULET, ItemID.SILVER_BAR, ItemID.OPAL, ItemID.AMULET_MOULD,  7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),
    OPAL_NECKLACE("Opal necklace", ItemID.OPAL_NECKLACE, ItemID.SILVER_BAR, ItemID.OPAL, ItemID.NECKLACE_MOULD,  7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),
    OPAL_BRACELET("Opal bracelet", ItemID.OPAL_BRACELET, ItemID.SILVER_BAR, ItemID.OPAL, ItemID.BRACELET_MOULD, 7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Runes.WATER,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 1); }}),

    // --- Level 2 Enchant (Lvl 27 Magic) ---
    EMERALD_RING("Emerald ring", ItemID.EMERALD_RING, ItemID.GOLD_BAR, ItemID.EMERALD, ItemID.RING_MOULD, 27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),
    EMERALD_AMULET("Emerald amulet", ItemID.EMERALD_AMULET, ItemID.GOLD_BAR, ItemID.EMERALD, ItemID.AMULET_MOULD,  27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),
    EMERALD_NECKLACE("Emerald necklace", ItemID.EMERALD_NECKLACE, ItemID.GOLD_BAR, ItemID.EMERALD, ItemID.NECKLACE_MOULD,  27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),
    EMERALD_BRACELET("Emerald bracelet", ItemID.EMERALD_BRACELET, ItemID.GOLD_BAR, ItemID.EMERALD, ItemID.BRACELET_MOULD, 27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),

    // NEW: Lvl-2 Enchant Additions
    JADE_RING("Jade ring", ItemID.JADE_RING, ItemID.SILVER_BAR, ItemID.JADE, ItemID.RING_MOULD, 27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),
    JADE_AMULET("Jade amulet", ItemID.JADE_AMULET, ItemID.SILVER_BAR, ItemID.JADE, ItemID.AMULET_MOULD,  27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),
    JADE_NECKLACE("Jade necklace", ItemID.JADE_NECKLACE, ItemID.SILVER_BAR, ItemID.JADE, ItemID.NECKLACE_MOULD,  27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),
    JADE_BRACELET("Jade bracelet", ItemID.JADE_BRACELET, ItemID.SILVER_BAR, ItemID.JADE, ItemID.BRACELET_MOULD, 27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Runes.AIR,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.AIR, 3); }}),

    // --- Level 3 Enchant (Lvl 49 Magic) ---
    RUBY_RING("Ruby ring", ItemID.RUBY_RING, ItemID.GOLD_BAR, ItemID.RUBY, ItemID.RING_MOULD,  49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),
    RUBY_AMULET("Ruby amulet", ItemID.RUBY_AMULET, ItemID.GOLD_BAR, ItemID.RUBY, ItemID.AMULET_MOULD,  49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),
    RUBY_NECKLACE("Ruby necklace", ItemID.RUBY_NECKLACE, ItemID.GOLD_BAR, ItemID.RUBY, ItemID.NECKLACE_MOULD,  49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),
    RUBY_BRACELET("Ruby bracelet", ItemID.RUBY_BRACELET, ItemID.GOLD_BAR, ItemID.RUBY, ItemID.BRACELET_MOULD,  49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),

    // NEW: Lvl-3 Enchant Additions
    TOPAZ_RING("Topaz ring", ItemID.TOPAZ_RING, ItemID.SILVER_BAR, ItemID.RED_TOPAZ, ItemID.RING_MOULD, 49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),
    TOPAZ_AMULET("Topaz amulet", ItemID.TOPAZ_AMULET, ItemID.SILVER_BAR, ItemID.RED_TOPAZ, ItemID.AMULET_MOULD,  49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),
    TOPAZ_NECKLACE("Topaz necklace", ItemID.TOPAZ_NECKLACE, ItemID.SILVER_BAR, ItemID.RED_TOPAZ, ItemID.NECKLACE_MOULD,  49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),
    TOPAZ_BRACELET("Topaz bracelet", ItemID.TOPAZ_BRACELET, ItemID.SILVER_BAR, ItemID.RED_TOPAZ, ItemID.BRACELET_MOULD,  49, MagicAction.ENCHANT_RUBY_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 5); }}),

    // --- Level 4 Enchant (Lvl 57 Magic) ---
    DIAMOND_RING("Diamond ring", ItemID.DIAMOND_RING, ItemID.GOLD_BAR, ItemID.DIAMOND, ItemID.RING_MOULD, 57, MagicAction.ENCHANT_DIAMOND_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.EARTH, 10); }}),
    DIAMOND_AMULET("Diamond amulet", ItemID.DIAMOND_AMULET, ItemID.GOLD_BAR, ItemID.DIAMOND, ItemID.AMULET_MOULD,  57, MagicAction.ENCHANT_DIAMOND_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.EARTH, 10); }}),
    DIAMOND_NECKLACE("Diamond necklace", ItemID.DIAMOND_NECKLACE, ItemID.GOLD_BAR, ItemID.DIAMOND, ItemID.NECKLACE_MOULD,  57, MagicAction.ENCHANT_DIAMOND_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.EARTH, 10); }}),
    DIAMOND_BRACELET("Diamond bracelet", ItemID.DIAMOND_BRACELET, ItemID.GOLD_BAR, ItemID.DIAMOND, ItemID.BRACELET_MOULD, 57, MagicAction.ENCHANT_DIAMOND_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.EARTH, 10); }}),

    // --- Level 5 Enchant (Lvl 68 Magic) ---
    DRAGONSTONE_RING("Dragonstone ring", ItemID.DRAGONSTONE_RING, ItemID.GOLD_BAR, ItemID.DRAGONSTONE, ItemID.RING_MOULD, 68, MagicAction.ENCHANT_DRAGONSTONE_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 15); put(Runes.EARTH, 15); }}),
    DRAGONSTONE_AMULET("Dragonstone amulet", ItemID.DRAGONSTONE_AMULET, ItemID.GOLD_BAR, ItemID.DRAGONSTONE, ItemID.AMULET_MOULD,  68, MagicAction.ENCHANT_DRAGONSTONE_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 15); put(Runes.EARTH, 15); }}),
    DRAGON_NECKLACE("Dragon necklace", ItemID.DRAGON_NECKLACE, ItemID.GOLD_BAR, ItemID.DRAGONSTONE, ItemID.NECKLACE_MOULD,  68, MagicAction.ENCHANT_DRAGONSTONE_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 15); put(Runes.EARTH, 15); }}),
    DRAGONSTONE_BRACELET("Dragonstone bracelet", ItemID.DRAGONSTONE_BRACELET, ItemID.GOLD_BAR, ItemID.DRAGONSTONE, ItemID.BRACELET_MOULD, 68, MagicAction.ENCHANT_DRAGONSTONE_JEWELLERY, Runes.EARTH,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.WATER, 15); put(Runes.EARTH, 15); }}),

    // --- Level 6 Enchant (Lvl 87 Magic) ---
    ONYX_RING("Onyx ring", ItemID.ONYX_RING, ItemID.GOLD_BAR, ItemID.ONYX, ItemID.RING_MOULD, 87, MagicAction.ENCHANT_ONYX_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 20); put(Runes.EARTH, 20); }}),
    ONYX_AMULET("Onyx amulet", ItemID.ONYX_AMULET,  ItemID.GOLD_BAR, ItemID.ONYX, 87, ItemID.AMULET_MOULD,  MagicAction.ENCHANT_ONYX_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 20); put(Runes.EARTH, 20); }}),
    ONYX_NECKLACE("Onyx necklace", ItemID.ONYX_NECKLACE,  ItemID.GOLD_BAR, ItemID.ONYX, ItemID.NECKLACE_MOULD,  87, MagicAction.ENCHANT_ONYX_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 20); put(Runes.EARTH, 20); }}),
    ONYX_BRACELET("Onyx bracelet", ItemID.ONYX_BRACELET,  ItemID.GOLD_BAR, ItemID.ONYX, ItemID.BRACELET_MOULD, 87, MagicAction.ENCHANT_ONYX_JEWELLERY, Runes.FIRE,
            new HashMap<>() {{ put(Runes.COSMIC, 1); put(Runes.FIRE, 20); put(Runes.EARTH, 20); }});

    // --- Fields for our custom data ---
    private final String name;
    private final int unenchantedId;
    private final int barId;
    private final int gemId;
    private final int mouldId;

    // --- Fields required by the Spell interface ---
    private final int requiredLevel;
    private final MagicAction magicAction;
    private final Runes elementalRune;
    private final HashMap<Runes, Integer> requiredRunes;

    // --- Methods we MUST implement to satisfy the Spell contract ---
    @Override
    public Rs2Spellbook getSpellbook() {
        return Rs2Spellbook.MODERN;
    }

    // --- Helper method for the UI ---
    @Override
    public String toString() {
        return name;
    }
}

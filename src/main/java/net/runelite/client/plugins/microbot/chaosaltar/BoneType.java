package net.runelite.client.plugins.microbot.chaosaltar;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import static net.runelite.api.ItemID.BABYDRAGON_BONES;
import static net.runelite.api.ItemID.BIG_BONES;
import static net.runelite.api.ItemID.DRAGON_BONES;
import static net.runelite.api.ItemID.DRAKE_BONES;
import static net.runelite.api.ItemID.FROST_DRAGON_BONES;
import static net.runelite.api.ItemID.WYRMLING_BONES;
import static net.runelite.api.ItemID.WYRM_BONES;
import static net.runelite.api.ItemID.WYVERN_BONES;

@Getter
@RequiredArgsConstructor
public enum BoneType {
    DRAGON_BONES_TYPE("Dragon bones", DRAGON_BONES),
    BIG_BONES_TYPE("Big bones", BIG_BONES),
    BABYDRAGON_BONES_TYPE("Babydragon bones", BABYDRAGON_BONES),
    WYVERN_BONES_TYPE("Wyvern bones", WYVERN_BONES),
    WYRM_BONES_TYPE("Wyrm bones", WYRM_BONES),
    WYRMLING_BONES_TYPE("Wyrmling bones", WYRMLING_BONES),
    DRAKE_BONES_TYPE("Drake bones", DRAKE_BONES),
    FROST_DRAGON_BONES_TYPE("Frost dragon bones", FROST_DRAGON_BONES);

    private final String itemName;
    private final int itemId;

    @Override
    public String toString() {
        return itemName;
    }
}

package net.runelite.client.plugins.custom.custommixology;

import net.runelite.client.plugins.custom.custommixology.enums.OrderStrategy;
import net.runelite.client.plugins.custom.custommixology.enums.PotionComponent;
import net.runelite.client.plugins.custom.custommixology.enums.PotionModifier;
import net.runelite.client.plugins.custom.custommixology.enums.PotionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomMixologyOrderSelectorTest {

    private static List<CustomPotionOrder> orders(PotionType a, PotionType b, PotionType c) {
        List<CustomPotionOrder> list = new ArrayList<>();
        list.add(new CustomPotionOrder(0, a, PotionModifier.CRYSTALISED));
        list.add(new CustomPotionOrder(1, b, PotionModifier.CONCENTRATED));
        list.add(new CustomPotionOrder(2, c, PotionModifier.HOMOGENOUS));
        return list;
    }

    private static String selected(List<CustomPotionOrder> orders) {
        StringBuilder sb = new StringBuilder();
        orders.forEach(o -> sb.append(o.selected() ? '1' : '0'));
        return sb.toString();
    }

    @Test
    void allOrdersSelectsEverything() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.ALCO_AUGMENTATOR, PotionType.LIPLACK_LIQUOR);
        CustomMixologyOrderSelector.select(o, OrderStrategy.ALL_ORDERS, PotionComponent.LYE);
        assertEquals("111", selected(o));
    }

    @Test
    void experienceSkipsSingles() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.AZURE_AURA_MIX, PotionType.MIXALOT);
        CustomMixologyOrderSelector.select(o, OrderStrategy.EXPERIENCE, PotionComponent.LYE);
        assertEquals("011", selected(o));
    }

    @Test
    void experienceOnlySinglesMakesFastestOne() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.ALCO_AUGMENTATOR, PotionType.LIPLACK_LIQUOR);
        CustomMixologyOrderSelector.select(o, OrderStrategy.EXPERIENCE, PotionComponent.LYE);
        // Retort (concentrated) is the fastest station for the bot.
        assertEquals("010", selected(o));
    }

    @Test
    void totalPointsMakesEverythingWhenMalOffered() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.ALCO_AUGMENTATOR, PotionType.MIXALOT);
        CustomMixologyOrderSelector.select(o, OrderStrategy.TOTAL_POINTS, PotionComponent.LYE);
        assertEquals("111", selected(o));
    }

    @Test
    void totalPointsSkipsSinglesWithoutMal() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.MEGALITE_LIQUID, PotionType.ANTI_LEECH_LOTION);
        CustomMixologyOrderSelector.select(o, OrderStrategy.TOTAL_POINTS, PotionComponent.LYE);
        assertEquals("011", selected(o));
    }

    @Test
    void totalPointsOnlySinglesPrefersResin() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.ALCO_AUGMENTATOR, PotionType.LIPLACK_LIQUOR);
        CustomMixologyOrderSelector.select(o, OrderStrategy.TOTAL_POINTS, PotionComponent.LYE);
        assertEquals("001", selected(o));
    }

    @Test
    void singleResinMakesWholeBoardWhenNonSingleContainsResin() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.MEGALITE_LIQUID, PotionType.ALCO_AUGMENTATOR);
        CustomMixologyOrderSelector.select(o, OrderStrategy.SINGLE_RESIN, PotionComponent.LYE);
        assertEquals("111", selected(o));
    }

    @Test
    void singleResinTwoSinglesPlusOneOnlyMakesTheMix() {
        // LLL + LLL + MML: only the MML gives the same ratio for less paste.
        List<CustomPotionOrder> o = orders(PotionType.LIPLACK_LIQUOR, PotionType.LIPLACK_LIQUOR, PotionType.MARLEYS_MOONLIGHT);
        CustomMixologyOrderSelector.select(o, OrderStrategy.SINGLE_RESIN, PotionComponent.LYE);
        assertEquals("001", selected(o));
    }

    @Test
    void singleResinWithoutResinMakesOneNonSingle() {
        List<CustomPotionOrder> o = orders(PotionType.MAMMOTH_MIGHT_MIX, PotionType.AZURE_AURA_MIX, PotionType.MYSTIC_MANA_AMALGAM);
        CustomMixologyOrderSelector.select(o, OrderStrategy.SINGLE_RESIN, PotionComponent.LYE);
        assertEquals("010", selected(o));
    }
}

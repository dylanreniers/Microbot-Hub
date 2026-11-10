package net.runelite.client.plugins.custom.custommixology;

import net.runelite.client.plugins.custom.custommixology.enums.OrderStrategy;
import net.runelite.client.plugins.custom.custommixology.enums.PotionComponent;
import net.runelite.client.plugins.custom.custommixology.enums.PotionModifier;
import net.runelite.client.plugins.custom.custommixology.enums.PotionType;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decides which of the current orders are worth making, based on
 * https://oldschool.runescape.wiki/w/Mastering_Mixology/Strategies.
 * Always selects at least one order so the order board keeps refreshing.
 */
public final class CustomMixologyOrderSelector {

    // Fastest station for the bot first: the retort needs no timing, the agitator cue can be missed,
    // and the alembic is the slowest without perfect input.
    private static final List<PotionModifier> SPEED_ORDER = Arrays.asList(
            PotionModifier.CONCENTRATED,
            PotionModifier.HOMOGENOUS,
            PotionModifier.CRYSTALISED
    );

    private CustomMixologyOrderSelector() {
    }

    public static void select(List<CustomPotionOrder> orders, OrderStrategy strategy, PotionComponent resin) {
        if (orders.isEmpty()) return;
        orders.forEach(o -> o.setSelected(false));

        switch (strategy) {
            case EXPERIENCE:
                selectExperience(orders);
                break;
            case TOTAL_POINTS:
                selectTotalPoints(orders, resin);
                break;
            case SINGLE_RESIN:
                selectSingleResin(orders, resin);
                break;
            case ALL_ORDERS:
            default:
                orders.forEach(o -> o.setSelected(true));
                break;
        }

        if (orders.stream().noneMatch(CustomPotionOrder::selected)) {
            fastest(orders).ifPresent(o -> o.setSelected(true));
        }
    }

    public static boolean isSingle(PotionType type) {
        PotionComponent[] c = type.components();
        return c[0] == c[1] && c[1] == c[2];
    }

    public static boolean isTriple(PotionType type) {
        return type == PotionType.MIXALOT;
    }

    public static int count(PotionType type, PotionComponent component) {
        return (int) Arrays.stream(type.components()).filter(c -> c == component).count();
    }

    private static void selectExperience(List<CustomPotionOrder> orders) {
        orders.stream().filter(o -> !isSingle(o.potionType())).forEach(o -> o.setSelected(true));
    }

    private static void selectTotalPoints(List<CustomPotionOrder> orders, PotionComponent resin) {
        if (orders.stream().anyMatch(o -> isTriple(o.potionType()))) {
            orders.forEach(o -> o.setSelected(true));
            return;
        }
        List<CustomPotionOrder> nonSingles = orders.stream()
                .filter(o -> !isSingle(o.potionType()))
                .collect(Collectors.toList());
        if (!nonSingles.isEmpty()) {
            nonSingles.forEach(o -> o.setSelected(true));
            return;
        }
        // Only singles: make the one of the preferred resin, otherwise the fastest one.
        Optional<CustomPotionOrder> preferred = orders.stream()
                .filter(o -> count(o.potionType(), resin) == 3)
                .min(speedComparator());
        (preferred.isPresent() ? preferred : fastest(orders)).ifPresent(o -> o.setSelected(true));
    }

    private static void selectSingleResin(List<CustomPotionOrder> orders, PotionComponent resin) {
        List<CustomPotionOrder> nonSinglesWithResin = orders.stream()
                .filter(o -> !isSingle(o.potionType()) && count(o.potionType(), resin) > 0)
                .collect(Collectors.toList());
        long singlesOfResin = orders.stream()
                .filter(o -> isSingle(o.potionType()) && count(o.potionType(), resin) == 3)
                .count();

        if (!nonSinglesWithResin.isEmpty()) {
            // XXX + XXX + 00X: making only the 00X potion gives the same ratio for far less paste.
            if (orders.size() == 3 && singlesOfResin == 2 && nonSinglesWithResin.size() == 1
                    && count(nonSinglesWithResin.get(0).potionType(), resin) == 1) {
                nonSinglesWithResin.get(0).setSelected(true);
                return;
            }
            orders.forEach(o -> o.setSelected(true));
            return;
        }
        if (singlesOfResin > 0) {
            orders.stream()
                    .filter(o -> count(o.potionType(), resin) == 3)
                    .forEach(o -> o.setSelected(true));
            return;
        }
        // Nothing earns the target resin: refresh the board as cheaply as possible.
        Optional<CustomPotionOrder> cheapest = orders.stream()
                .filter(o -> !isSingle(o.potionType()))
                .min(speedComparator());
        (cheapest.isPresent() ? cheapest : fastest(orders)).ifPresent(o -> o.setSelected(true));
    }

    private static Optional<CustomPotionOrder> fastest(List<CustomPotionOrder> orders) {
        return orders.stream().min(speedComparator());
    }

    private static Comparator<CustomPotionOrder> speedComparator() {
        return Comparator.<CustomPotionOrder>comparingInt(o -> SPEED_ORDER.indexOf(o.potionModifier()))
                .thenComparing(Comparator.comparingInt((CustomPotionOrder o) -> o.potionType().experience()).reversed());
    }
}

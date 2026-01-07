package net.runelite.client.plugins.custom.barrows;

import lombok.Getter;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldArea;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import java.util.Arrays;

@Getter
public enum BarrowsBrother {
    //Note: this is the order in which the brothers are killed.
    DHAROK("Dharok the Wretched", new Rs2WorldArea(3573, 3296, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_DHAROK, true),
    KARIL("Karil the Tainted", new Rs2WorldArea(3564, 3274, 3, 3, 0), Rs2PrayerEnum.PROTECT_RANGE, VarbitID.BARROWS_KILLED_KARIL, true),
    AHRIM("Ahrim the Blighted", new Rs2WorldArea(3563, 3288, 3, 3, 0), Rs2PrayerEnum.PROTECT_MAGIC, VarbitID.BARROWS_KILLED_AHRIM, false),
    GUTHAN("Guthan the Infested", new Rs2WorldArea(3575, 3280, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_GUTHAN, true),
    TORAG("Torag the Corrupted", new Rs2WorldArea(3552, 3282, 2, 2, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_TORAG, true),
    VERAC("Verac the Defiled", new Rs2WorldArea(3556, 3297, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE, VarbitID.BARROWS_KILLED_VERAC, true);

    private final String name;
    private final Rs2WorldArea moundArea;
    private final Rs2PrayerEnum whatToPray;
    private final int varbit;
    private final boolean needsMeleeGear;

    BarrowsBrother(String name, Rs2WorldArea moundArea, Rs2PrayerEnum whatToPray, int varbit, boolean needsMeleeGear) {
        this.name = name;
        this.moundArea = moundArea;
        this.whatToPray = whatToPray;
        this.varbit = varbit;
        this.needsMeleeGear = needsMeleeGear;
    }

    public static boolean allBarrowsBrothersAreKilled() {
        return Arrays.stream(BarrowsBrother.values()).filter(BarrowsBrother::hasBeenKilled).count() == 6;
    }

    public static boolean noBarrowsBrothersAreKilled() {
        return Arrays.stream(BarrowsBrother.values()).noneMatch(BarrowsBrother::hasBeenKilled);
    }

    public static int getNumberOfBarrowsBrothersKilled() {
        return (int) Arrays.stream(BarrowsBrother.values()).filter(BarrowsBrother::hasBeenKilled).count();
    }

    public static BarrowsBrother getFinalBarrowsBrother() {
        return Arrays.stream(BarrowsBrother.values()).filter((brother) -> !brother.hasBeenKilled()).findFirst().orElse(null);
    }

    public boolean hasBeenKilled() {
        return Microbot.getVarbitValue(this.varbit) == 1;
    }

    public boolean isDharok() {
        return this.name.startsWith("Dharok");
    }

    public boolean isAhrim() {
        return this.name.startsWith("Ahrim");
    }

    public boolean isKaril() {
        return this.name.startsWith("Karil");
    }

    public boolean isWeakerBrother() {
        return !isDharok() && !isAhrim() && !isKaril();
    }
}

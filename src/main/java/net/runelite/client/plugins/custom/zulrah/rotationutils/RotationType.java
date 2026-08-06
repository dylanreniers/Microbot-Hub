package net.runelite.client.plugins.custom.zulrah.rotationutils;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.client.plugins.custom.zulrah.constants.StandLocation;
import net.runelite.client.plugins.custom.zulrah.constants.ZulrahType;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public enum RotationType {
    ROT_A("Rotation A",
            ImmutableList.of(
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, null),
                    add(ZulrahType.MELEE, StandLocation.NORTHEAST_NORTH, null),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.RANGE, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MELEE, StandLocation.WEST_PILLAR_N, null),
                    add(ZulrahType.MAGIC, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.RANGE, StandLocation.EAST_PILLAR_N, null),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    addJad(ZulrahType.RANGE, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MELEE, StandLocation.NORTHEAST_NORTH, null),
                    // Final phase (wiki Rotation 1 phase 11): green in the middle, ranged x5.
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, Rs2PrayerEnum.PROTECT_RANGE))),
    ROT_B("Rotation B",
            ImmutableList.of(
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, null),
                    add(ZulrahType.MELEE, StandLocation.NORTHEAST_NORTH, null),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.RANGE, StandLocation.WEST_PILLAR_N   , null),
                    add(ZulrahType.MAGIC, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.MELEE, StandLocation.WEST_PILLAR_N, null),
                    add(ZulrahType.RANGE, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    addJad(ZulrahType.RANGE, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MELEE, StandLocation.NORTHEAST_NORTH, null),
                    // Final phase (wiki Rotation 2 phase 11): green in the middle, ranged x5.
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, Rs2PrayerEnum.PROTECT_RANGE))),
    ROT_C("Rotation C",
            ImmutableList.of(
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, null),
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MELEE, StandLocation.EAST_PILLAR_N, null),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.RANGE, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.RANGE, StandLocation.WEST_PILLAR_N, null),
                    add(ZulrahType.RANGE, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    addJad(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.MAGIC, StandLocation.NORTHEAST_NORTH, null),
                    // Final phase (wiki Rotation 3 phase 12): green in the middle, ranged x5.
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, Rs2PrayerEnum.PROTECT_RANGE))),
    ROT_D("Rotation D",
            ImmutableList.of(
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, null),
                    add(ZulrahType.MAGIC, StandLocation.NORTHEAST_NORTH, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.RANGE, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MAGIC, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.MELEE, StandLocation.EAST_PILLAR_N, null),
                    add(ZulrahType.RANGE, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.RANGE, StandLocation.EAST_PILLAR_N, null),
                    add(ZulrahType.MAGIC, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.RANGE, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_RANGE),
                    add(ZulrahType.MAGIC, StandLocation.EAST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    addJad(ZulrahType.MAGIC, StandLocation.WEST_PILLAR_N, Rs2PrayerEnum.PROTECT_MAGIC),
                    add(ZulrahType.MAGIC, StandLocation.NORTHEAST_NORTH, null),
                    // Final phase (wiki Rotation 4 phase 13): green in the middle, ranged x5.
                    add(ZulrahType.RANGE, StandLocation.NORTHEAST_NORTH, Rs2PrayerEnum.PROTECT_RANGE)));

    private static final List<RotationType> lookup = new ArrayList<>();

    static {
        lookup.addAll(EnumSet.allOf(RotationType.class));
    }

    @Getter
    private final String rotationName;
    @Getter
    private final List<ZulrahPhase> zulrahPhases;

    RotationType(String rotationName, List<ZulrahPhase> zulrahPhases) {
        this.rotationName = rotationName;
        this.zulrahPhases = zulrahPhases;
    }

    public static List<RotationType> findPotentialRotations(NPC npc, int stage) {
        log.info("Finding potential rotations");
        ZulrahNpc observed = ZulrahNpc.valueOf(npc, false);
        if (observed == null || stage < 0) {
            log.warn("Cannot match rotations: observed={} stage={}", observed, stage);
            return new ArrayList<>();
        }
        return lookup.stream()
                .filter(type -> stage < type.getZulrahPhases().size())
                .filter(type -> type.getZulrahPhases().get(stage).getZulrahNpc().equals(observed))
                .collect(Collectors.toList());
    }

    private static ZulrahPhase add(ZulrahType type, StandLocation standLocation, Rs2PrayerEnum prayer) {
        return new ZulrahPhase(new ZulrahNpc(type, false), new ZulrahAttributes(standLocation, prayer));
    }

    private static ZulrahPhase addJad(ZulrahType type, StandLocation standLocation, Rs2PrayerEnum prayer) {
        return new ZulrahPhase(new ZulrahNpc(type, true), new ZulrahAttributes(standLocation, prayer));
    }
}

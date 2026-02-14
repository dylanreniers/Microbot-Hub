package net.runelite.client.plugins.custom.zulrah.rotationutils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Prayer;
import net.runelite.client.plugins.custom.zulrah.constants.StandLocation;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@RequiredArgsConstructor
@Getter
public final class ZulrahAttributes {
    @Nonnull
    private final StandLocation standLocation;
    @Nullable
    private final Rs2PrayerEnum prayer;
}

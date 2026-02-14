package net.runelite.client.plugins.custom.zulrah.rotationutils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.NPC;
import net.runelite.client.plugins.custom.zulrah.constants.ZulrahType;

import java.util.Objects;

@RequiredArgsConstructor
@Getter
public final class ZulrahNpc {
    private final ZulrahType type;
    private final boolean jad;

    public static ZulrahNpc valueOf(NPC zulrah, boolean jad) {
        return new ZulrahNpc(Objects.requireNonNull(ZulrahType.valueOf(zulrah.getId())), jad);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ZulrahNpc zulrahNpc = (ZulrahNpc) o;
        return jad == zulrahNpc.jad && type == zulrahNpc.type;
    }
}

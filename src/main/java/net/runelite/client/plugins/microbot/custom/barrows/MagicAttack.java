package net.runelite.client.plugins.microbot.custom.barrows;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;

@Getter
@RequiredArgsConstructor
public enum MagicAttack {
    WIND_BLAST("Death rune", Rs2CombatSpells.WIND_BLAST),
    WIND_WAVE("Blood rune", Rs2CombatSpells.WIND_WAVE),
    WIND_SURGE("Wrath rune", Rs2CombatSpells.WIND_SURGE),
    POWERED_STAFF("Powered staff", null);

    final String rune;
    final Rs2CombatSpells autocast;

    public boolean isPoweredStaff() {
        return this == POWERED_STAFF;
    }
}

package net.runelite.client.plugins.custom.custommixology;

import net.runelite.client.plugins.custom.custommixology.enums.PotionModifier;
import net.runelite.client.plugins.custom.custommixology.enums.PotionType;

public class CustomPotionOrder {
    private final int idx;
    private final PotionType potionType;
    private final PotionModifier potionModifier;
    private boolean fulfilled;
    // Whether the active order strategy wants this order made; unselected orders are skipped.
    private boolean selected = true;

    public CustomPotionOrder(int idx, PotionType potionType, PotionModifier potionModifier) {
        this.idx = idx;
        this.potionType = potionType;
        this.potionModifier = potionModifier;
    }

    public int idx() {
        return this.idx;
    }

    public PotionType potionType() {
        return this.potionType;
    }

    public PotionModifier potionModifier() {
        return this.potionModifier;
    }

    public void setFulfilled(boolean fulfilled) {
        this.fulfilled = fulfilled;
    }

    public boolean fulfilled() {
        return this.fulfilled;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean selected() {
        return this.selected;
    }

    public String toString() {
        return "CustomPotionOrder{idx=" + this.idx + ", potionType=" + this.potionType + ", potionModifier=" + this.potionModifier + ", selected=" + this.selected + "}";
    }
}

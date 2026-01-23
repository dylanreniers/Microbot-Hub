package net.runelite.client.plugins.microbot.moonsofperil;

import lombok.Getter;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.moonsofperil.enums.State;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.BaseHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.BloodMoonHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.BlueMoonHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.DeathHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.EclipseMoonHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.IdleHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.LogoutHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.ResupplyHandler;
import net.runelite.client.plugins.microbot.moonsofperil.handlers.RewardHandler;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;

import javax.inject.Inject;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MoonsOfPerilScript extends Script {

    public static boolean test = false;
    public static volatile State CURRENT_STATE = State.IDLE;
    private final MoonsOfPerilConfig config;
    private final Map<State, BaseHandler> handlers = new EnumMap<>(State.class);
    private Rs2InventorySetup bloodEquipment;
    private Rs2InventorySetup blueEquipment;
    private Rs2InventorySetup eclipseEquipment;
    private Rs2InventorySetup eclipseClones;
    @Getter
    private State state = State.IDLE;

    @Inject
    public MoonsOfPerilScript(MoonsOfPerilConfig config) {
        this.config = config;
    }

    public boolean run() {

        this.bloodEquipment = new Rs2InventorySetup(config.bloodEquipmentNormal(), mainScheduledFuture);
        this.blueEquipment = new Rs2InventorySetup(config.blueEquipmentNormal(), mainScheduledFuture);
        this.eclipseEquipment = new Rs2InventorySetup(config.eclipseEquipmentNormal(), mainScheduledFuture);
        this.eclipseClones = new Rs2InventorySetup(config.eclipseEquipmentClones(), mainScheduledFuture);

        initHandlers();

        Microbot.enableAutoRunOn = false;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                long start = System.currentTimeMillis();

                state = determineState();
                CURRENT_STATE = state;
                BaseHandler h = handlers.get(state);
                if (h != null && h.validate()) {
                    h.execute();
                }

                Microbot.log("Loop " + (System.currentTimeMillis() - start) + " ms");
            } catch (Exception ex) {
                Microbot.log("MoonsOfPerilScript error: " + ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    private void initHandlers() {
        handlers.put(State.LOGOUT, new LogoutHandler(config));
        handlers.put(State.IDLE, new IdleHandler(config));
        handlers.put(State.RESUPPLY, new ResupplyHandler(config));
        handlers.put(State.ECLIPSE_MOON, new EclipseMoonHandler(config, eclipseEquipment, eclipseClones));
        handlers.put(State.BLUE_MOON, new BlueMoonHandler(config, blueEquipment));
        handlers.put(State.BLOOD_MOON, new BloodMoonHandler(config, bloodEquipment));
        handlers.put(State.REWARDS, new RewardHandler(config));
        handlers.put(State.DEATH, new DeathHandler(config));
    }

    private State determineState() {
        if (isPlayerDead()) {
            return State.DEATH;
        } if (needsToStop()) {
            return State.LOGOUT;
        } else if (readyToLootChest()) {
            return State.REWARDS;
        } else if (needsResupply()) {
            return State.RESUPPLY;
        } else if (eclipseMoonSequence()) {
            return State.ECLIPSE_MOON;
        } else if (blueMoonSequence()) {
            return State.BLUE_MOON;
        } else if (bloodMoonSequence()) {
            return State.BLOOD_MOON;
        }

        return State.IDLE;
    }

    private boolean needsToStop() {
        BaseHandler resupply = handlers.get(State.LOGOUT);
        return resupply != null && resupply.validate();
    }

    private boolean needsResupply() {
        if (state == State.RESUPPLY) {
            return false;
        }
        BaseHandler resupply = handlers.get(State.RESUPPLY);
        return resupply != null && resupply.validate();
    }

    private boolean eclipseMoonSequence() {
        BaseHandler eclipse = handlers.get(State.ECLIPSE_MOON);
        return eclipse != null && eclipse.validate();
    }

    private boolean blueMoonSequence() {
        BaseHandler blue = handlers.get(State.BLUE_MOON);
        return blue != null && blue.validate();
    }

    private boolean bloodMoonSequence() {
        BaseHandler blood = handlers.get(State.BLOOD_MOON);
        return blood != null && blood.validate();
    }

    private boolean readyToLootChest() {
        BaseHandler reward = handlers.get(State.REWARDS);
        return reward != null && reward.validate();
    }

    private boolean isPlayerDead() {
        BaseHandler reward = handlers.get(State.DEATH);
        return reward != null && reward.validate();
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}

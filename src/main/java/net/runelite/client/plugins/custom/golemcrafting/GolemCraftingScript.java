package net.runelite.client.plugins.custom.golemcrafting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.actions.Action;
import net.runelite.client.plugins.custom.actions.ActionScript;
import net.runelite.client.plugins.custom.golemcrafting.actions.GolemAction;
import net.runelite.client.plugins.custom.golemcrafting.actions.GolemContext;
import net.runelite.client.plugins.custom.golemcrafting.actions.GolemState;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;

import javax.inject.Inject;

/**
 * Golem crafting action-driven script. Generic pipeline wiring lives in {@link ActionScript}; here we
 * build the {@link GolemContext} from config and run the {@link GolemAction} pipeline each tick.
 */
@Slf4j
public class GolemCraftingScript extends ActionScript<GolemState> {

    @Inject
    private GolemCraftingConfig config;

    private final GolemContext context = new GolemContext();

    @Override
    protected Class<? extends Action<GolemState>> actionType() {
        return GolemAction.class;
    }

    @Override
    protected GolemState createState() {
        return new GolemState(context);
    }

    @Override
    protected void onInitialize() {
        context.setGolemsPerTrip(config.golemsPerTrip());
        String fur = config.furType() == null || config.furType().isBlank()
                ? "Dashing kebbit fur" : config.furType().trim();
        context.setFurName(fur);
        context.setFurItemId(GolemConstants.DEFAULT_FUR);
        context.setBankForFurs(config.bankForFurs());
        context.setUseMonolith(config.useMonolith());
        context.setUseGemBag(config.useGemBag());
        context.setCraftingMode(config.craftingMode());
        log.info("[golem] init — {} golems/trip, fur '{}', bankForFurs {}, monolith {}",
                context.getGolemsPerTrip(), fur, context.isBankForFurs(), context.isUseMonolith());
    }

    public GolemContext context() {
        return context;
    }

    /**
     * Tighter than the default 100 ms so momentum mining hops to the next rock the moment an ore drops
     * (a slow poll leaves a gap that lets momentum decay), and so side-to-side repositioning while
     * crafting is snappy.
     */
    @Override
    public int getTickDelay() {
        return 40;
    }
}

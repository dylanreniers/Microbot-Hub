package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.agility.AgilityPlugin;
import net.runelite.client.plugins.microbot.RoyalTitans.RoyalTitansPlugin;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.cannonballsmelter.CannonballSmelterPlugin;
import net.runelite.client.plugins.microbot.nmz.NmzPlugin;
import net.runelite.client.plugins.microbot.plankrunner.PlankRunnerPlugin;
import net.runelite.client.plugins.microbot.sulphurnaguafigther.SulphurNaguaPlugin;

public class Microbot {

	private static final Class<?>[] debugPlugins = {
            AIOFighterPlugin.class, AgilityPlugin.class, PlankRunnerPlugin.class, SulphurNaguaPlugin.class, NmzPlugin.class, CannonballSmelterPlugin.class
	};

    public static void main(String[] args) throws Exception {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}

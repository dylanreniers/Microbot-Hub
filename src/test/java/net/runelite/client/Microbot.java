package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.agility.AgilityPlugin;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.moonsofperil.MoonsOfPerilPlugin;
import net.runelite.client.plugins.microbot.plankrunner.PlankRunnerPlugin;

public class Microbot
{

	private static final Class<?>[] debugPlugins = {
            AIOFighterPlugin.class, AgilityPlugin.class, PlankRunnerPlugin.class, MoonsOfPerilPlugin.class
	};

    public static void main(String[] args) throws Exception
    {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}

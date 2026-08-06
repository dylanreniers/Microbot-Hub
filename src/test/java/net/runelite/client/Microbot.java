package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.microbot.agility.MicroAgilityPlugin;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.autogauntletprayer.AutoGauntletPrayerPlugin;
import net.runelite.client.plugins.microbot.cannonballsmelter.CannonballSmelterPlugin;
import net.runelite.client.plugins.microbot.herbrun.HerbrunPlugin;
import net.runelite.client.plugins.microbot.mmcaves.MmCavesPlugin;
import net.runelite.client.plugins.microbot.pestcontrol.PestControlPlugin;
import net.runelite.client.plugins.microbot.plankrunner.PlankRunnerPlugin;
import net.runelite.client.plugins.microbot.sulphurnaguafigther.SulphurNaguaPlugin;

public class Microbot {

	private static final Class<?>[] debugPlugins = {
            AIOFighterPlugin.class,
            PlankRunnerPlugin.class,
            SulphurNaguaPlugin.class,
            CannonballSmelterPlugin.class,
            MicroAgilityPlugin.class,
            AutoGauntletPrayerPlugin.class,
            MmCavesPlugin.class,
            HerbrunPlugin.class,
            PestControlPlugin.class,
	};

    public static void main(String[] args) throws Exception {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}

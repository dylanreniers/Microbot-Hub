package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.custom.woodcutting.AutoWoodcuttingPlugin;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaPlugin;
import net.runelite.client.plugins.custom.madangel.MadAngelPlugin;
import net.runelite.client.plugins.kourendlibrary.KourendLibraryPlugin;
import net.runelite.client.plugins.microbot.GiantSeaweedFarmer.GiantSeaweedFarmerPlugin;
import net.runelite.client.plugins.microbot.agentserver.AgentServerPlugin;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.aiomagic.AIOMagicPlugin;
import net.runelite.client.plugins.microbot.arceuuslibrary.ArceuusLibraryPlugin;
import net.runelite.client.plugins.microbot.birdhouseruns.FornBirdhouseRunsPlugin;
import net.runelite.client.plugins.microbot.pitfallhunter.PitfallHunterPlugin;
import net.runelite.client.plugins.microbot.sailing.MSailingPlugin;
import net.runelite.client.plugins.microbot.motherloadmine.MotherloadMinePlugin;

import net.runelite.client.plugins.agility.AgilityPlugin;
import net.runelite.client.plugins.microbot.agility.MicroAgilityPlugin;
import net.runelite.client.plugins.microbot.autogauntletprayer.AutoGauntletPrayerPlugin;
import net.runelite.client.plugins.microbot.cannonballsmelter.CannonballSmelterPlugin;
import net.runelite.client.plugins.microbot.herbrun.HerbrunPlugin;
import net.runelite.client.plugins.microbot.mmcaves.MmCavesPlugin;
import net.runelite.client.plugins.microbot.plankrunner.PlankRunnerPlugin;
import net.runelite.client.plugins.microbot.sulphurnaguafigther.SulphurNaguaPlugin;
import net.runelite.client.plugins.microbot.tempoross.TemporossPlugin;
import net.runelite.client.plugins.microbot.varrockanvil.VarrockAnvilPlugin;

public class Microbot
{

	private static final Class<?>[] debugPlugins = {
		AgentServerPlugin.class,
			AgilityPlugin.class,
			PlankRunnerPlugin.class,
			SulphurNaguaPlugin.class,
			CannonballSmelterPlugin.class,
			MicroAgilityPlugin.class,
			AutoGauntletPrayerPlugin.class,
			MmCavesPlugin.class,
			HerbrunPlugin.class,
			TemporossPlugin.class,
			AutoWoodcuttingPlugin.class,
			VarrockAnvilPlugin.class,
			AIOMagicPlugin.class,
		FornBirdhouseRunsPlugin.class,
		GiantSeaweedFarmerPlugin.class,
		PitfallHunterPlugin.class,
		MotherloadMinePlugin.class,
		KourendLibraryPlugin.class,
		ArceuusLibraryPlugin.class,
		AIOFighterPlugin.class,
		MSailingPlugin.class,
		CustomDemonicGorillaPlugin.class,
		MadAngelPlugin.class
	};

    public static void main(String[] args) throws Exception
    {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}

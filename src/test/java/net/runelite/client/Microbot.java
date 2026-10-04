package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.microbot.GiantSeaweedFarmer.GiantSeaweedFarmerPlugin;
import net.runelite.client.plugins.microbot.agentserver.AgentServerPlugin;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.aiomagic.AIOMagicPlugin;
import net.runelite.client.plugins.microbot.banksshopper.BanksShopperPlugin;
import net.runelite.client.plugins.microbot.birdhouseruns.FornBirdhouseRunsPlugin;
import net.runelite.client.plugins.microbot.farmtreerun.FarmTreeRunPlugin;
import net.runelite.client.plugins.microbot.housetab.HouseTabPlugin;
import net.runelite.client.plugins.microbot.sailing.MSailingPlugin;

import net.runelite.client.plugins.agility.AgilityPlugin;
import net.runelite.client.plugins.microbot.agility.MicroAgilityPlugin;
import net.runelite.client.plugins.microbot.autogauntletprayer.AutoGauntletPrayerPlugin;
import net.runelite.client.plugins.microbot.cannonballsmelter.CannonballSmelterPlugin;
import net.runelite.client.plugins.microbot.herbrun.HerbrunPlugin;
import net.runelite.client.plugins.microbot.mmcaves.MmCavesPlugin;
import net.runelite.client.plugins.microbot.sulphurnaguafigther.SulphurNaguaPlugin;
import net.runelite.client.plugins.microbot.tempoross.TemporossPlugin;
import net.runelite.client.plugins.microbot.varrockanvil.VarrockAnvilPlugin;
import net.runelite.client.plugins.microbot.woodcutting.AutoWoodcuttingPlugin;

public class Microbot
{

	private static final Class<?>[] debugPlugins = {
			AgentServerPlugin.class,
			AgilityPlugin.class,
			SulphurNaguaPlugin.class,
			CannonballSmelterPlugin.class,
			MicroAgilityPlugin.class,
			AutoGauntletPrayerPlugin.class,
			MmCavesPlugin.class,
			HerbrunPlugin.class,
			TemporossPlugin.class,
			VarrockAnvilPlugin.class,
			AIOMagicPlugin.class,
			FornBirdhouseRunsPlugin.class,
			GiantSeaweedFarmerPlugin.class,
			AIOFighterPlugin.class,
			MSailingPlugin.class,
			AutoWoodcuttingPlugin.class,
			BanksShopperPlugin.class,
			HouseTabPlugin.class,
			FarmTreeRunPlugin.class
	};

	public static void main(String[] args) throws Exception
	{
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
		RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);

		// Hot-reload: deploy every @UnderDevelopment plugin once the agent server
		// is up, so they come up reloadable without any per-restart step.
		UnderDevelopmentAutoDeployer.launch();

		RuneLiteDebug.main(args);
	}
}

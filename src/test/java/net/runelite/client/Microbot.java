package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.microbot.custom.arrowmaker.ArrowPlugin;
import net.runelite.client.plugins.microbot.banksbankstander.BanksBankStanderPlugin;
import net.runelite.client.plugins.microbot.custom.barrows.BarrowsPlugin;
import net.runelite.client.plugins.microbot.custom.blastoisefurnace.BlastoiseFurnacePlugin;
import net.runelite.client.plugins.microbot.fletching.FletchingPlugin;
import net.runelite.client.plugins.microbot.custom.jewelleryenchant.JewelleryEnchantPlugin;

public class Microbot
{

	private static final Class<?>[] debugPlugins = {
            net.runelite.client.plugins.microbot.jewelleryenchant.JewelleryEnchantPlugin.class, JewelleryEnchantPlugin.class, BarrowsPlugin.class, BanksBankStanderPlugin.class, BlastoiseFurnacePlugin.class, ArrowPlugin.class, FletchingPlugin.class
	};

    public static void main(String[] args) throws Exception
    {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}

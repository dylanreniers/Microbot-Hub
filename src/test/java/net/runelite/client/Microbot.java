package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.custom.microhunter.AutoHunterPlugin;
import net.runelite.client.plugins.custom.arrowmaker.ArrowPlugin;
import net.runelite.client.plugins.custom.moonlightmoth.MoonlightMothPlugin;
import net.runelite.client.plugins.microbot.banksbankstander.BanksBankStanderPlugin;
import net.runelite.client.plugins.custom.barrows.BarrowsPlugin;
import net.runelite.client.plugins.custom.blastoisefurnace.BlastoiseFurnacePlugin;
import net.runelite.client.plugins.custom.fletching.FletchingPlugin;
import net.runelite.client.plugins.custom.jewelleryenchant.JewelleryEnchantPlugin;
import net.runelite.client.plugins.custom.woodcutting.AutoWoodcuttingPlugin;

public class Microbot
{

	private static final Class<?>[] debugPlugins = {
            //MoonlightMothPlugin.class, AutoHunterPlugin.class, AutoWoodcuttingPlugin.class, JewelleryEnchantPlugin.class, BarrowsPlugin.class, BanksBankStanderPlugin.class, BlastoiseFurnacePlugin.class, ArrowPlugin.class, FletchingPlugin.class
            MoonlightMothPlugin.class
	};

    public static void main(String[] args) throws Exception
    {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}

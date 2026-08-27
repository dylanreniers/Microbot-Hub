package net.runelite.client.plugins.custom.motherloadmine;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.custom.motherloadmine.enums.MLMMiningSpotList;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;

@ConfigGroup(MotherloadMineConfig.configGroup)
@ConfigInformation(
	"• This plugin will automate mining in motherload mine <br />" +
	"• If using deposit all feature, <b>ensure you lock the slots you wish to keep in inventory</b> <br />" +
	"• Start near the bank chest in motherload mine <br />" +
	"• <b>For logout breaks, enable the Break Handler plugin</b> — this script pauses automatically during its breaks <br />"
)
public interface MotherloadMineConfig extends Config
{
	String configGroup = "micro-motherloadmine";

	String useInventorySetup = "useInventorySetup";
	String inventorySetup = "inventory-setup";
	String useDepositAll = "useDepositAll";
	String antiCrash = "antiCrash";
	String dropGems = "dropGems";
	String useUpstairsMine = "useUpstairsMine";
	String useUpstairsHopper = "useUpstairsHopper";
	String miningArea = "miningArea";

	String useSpecialAttack = "useSpecialAttack";
	String specChance = "specChance";
	String useMicroBreaks = "useMicroBreaks";
	String microBreakChance = "microBreakChance";
	String microBreakDurationLow = "microBreakDurationLow";
	String microBreakDurationHigh = "microBreakDurationHigh";
	String randomizeVeinSelection = "randomizeVeinSelection";

	@ConfigSection(
		name = "General",
		description = "General Plugin Settings",
		position = 0
	)
	String generalSection = "general";

	@ConfigSection(
		name = "Features",
		description = "Feature Settings",
		position = 1
	)
	String featureSection = "features";

	@ConfigSection(
		name = "Anti-Ban",
		description = "Humanization settings to make play look less automated",
		position = 2
	)
	String antibanSection = "antiban";

	@ConfigItem(
		keyName = useInventorySetup,
		name = "Enable Inventory Setup",
		description = "Enable this option to use an inventory setup with the plugin",
		position = 0,
		section = generalSection
	)
	default boolean useInventorySetup()
	{
		return false;
	}

	@ConfigItem(
		keyName = inventorySetup,
		name = "Inventory Setup",
		description = "Select the inventory setup to use with the plugin",
		position = 1,
		section = generalSection
	)
	default InventorySetup getInventorySetup()
	{
		return null;
	}

	@ConfigItem(
		keyName = useDepositAll,
		name = "Use Deposit All",
		description = "Uses deposit all button in the deposit box<br>" +
			"Note: ensure you enable locked slots enabled for the items you want to keep in your inventory",
		position = 2,
		section = generalSection
	)
	default boolean useDepositAll()
	{
		return false;
	}

	@ConfigItem(
		keyName = antiCrash,
		name = "Anti Crash",
		description = "Avoids other players when mining in the lower level",
		position = 3,
		section = generalSection
	)
	default boolean useAntiCrash()
	{
		return false;
	}

	@ConfigItem(
		keyName = dropGems,
		name = "Drop Gems",
		description = "Automatically drop gems while mining",
		position = 4,
		section = generalSection
	)
	default boolean dropGems()
	{
		return false;
	}

	// Mine upstairs
	@ConfigItem(
		keyName = useUpstairsMine,
		name = "Use Mine Upstairs",
		description = "Should the plugin use the upstairs mining area",
		position = 0,
		section = featureSection
	)
	default boolean mineUpstairs()
	{
		return false;
	}

	// Upstairs hopper unlocked
	@ConfigItem(
		keyName = useUpstairsHopper,
		name = "Use Upstairs Hopper",
		description = "Should the plugin use the upstairs hopper",
		position = 1,
		section = featureSection
	)
	default boolean upstairsHopperUnlocked()
	{
		return false;
	}

	// Mining Area Selection
	@ConfigItem(
		keyName = miningArea,
		name = "Mining Area",
		description = "Choose the specific area to mine in Motherload Mine",
		position = 2,
		section = featureSection
	)
	default MLMMiningSpotList miningArea()
	{
		return MLMMiningSpotList.ANY;
	}

	@ConfigItem(
		keyName = useMicroBreaks,
		name = "Micro Breaks",
		description = "Take short random breaks and add idle mouse movement while mining.<br>" +
			"For full logout breaks, also enable the Break Handler plugin.",
		position = 0,
		section = antibanSection
	)
	default boolean useMicroBreaks()
	{
		return true;
	}

	@ConfigItem(
		keyName = microBreakChance,
		name = "Micro Break Chance (%)",
		description = "Chance to start a micro break at each natural pause between veins.",
		position = 1,
		section = antibanSection
	)
	default int microBreakChance()
	{
		return 15;
	}

	@ConfigItem(
		keyName = microBreakDurationLow,
		name = "Micro Break Min (min)",
		description = "Shortest micro break length, in minutes.",
		position = 2,
		section = antibanSection
	)
	default int microBreakDurationLow()
	{
		return 1;
	}

	@ConfigItem(
		keyName = microBreakDurationHigh,
		name = "Micro Break Max (min)",
		description = "Longest micro break length, in minutes.",
		position = 3,
		section = antibanSection
	)
	default int microBreakDurationHigh()
	{
		return 4;
	}

	@ConfigItem(
		keyName = useSpecialAttack,
		name = "Use Pickaxe Special",
		description = "Use the special attack of a dragon/crystal/infernal pickaxe.<br>" +
			"When off, the pickaxe special is never used.",
		position = 4,
		section = antibanSection
	)
	default boolean useSpecialAttack()
	{
		return true;
	}

	@ConfigItem(
		keyName = specChance,
		name = "Special Attack Chance (%)",
		description = "Chance per vein to use the pickaxe special when available.<br>" +
			"Lower values look more human than specialing on cooldown every time.",
		position = 5,
		section = antibanSection
	)
	default int specChance()
	{
		return 25;
	}

	@ConfigItem(
		keyName = randomizeVeinSelection,
		name = "Randomize Vein Choice",
		description = "Occasionally mine a nearby vein instead of always the closest one.",
		position = 6,
		section = antibanSection
	)
	default boolean randomizeVeinSelection()
	{
		return true;
	}
}

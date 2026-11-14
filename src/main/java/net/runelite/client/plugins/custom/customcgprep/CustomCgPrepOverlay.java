package net.runelite.client.plugins.custom.customcgprep;

import net.runelite.client.plugins.custom.customcgprep.boss.BossFight;
import net.runelite.client.plugins.custom.customcgprep.boss.FightPlan;
import net.runelite.client.plugins.custom.customcgprep.plan.Ledger;
import net.runelite.client.plugins.custom.customcgprep.plan.Needs;
import net.runelite.client.plugins.custom.customcgprep.plan.PrepGoal;
import net.runelite.client.plugins.custom.customcgprep.plan.PrepPlanner;
import net.runelite.client.plugins.custom.customcgprep.stats.RunStats;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class CustomCgPrepOverlay extends OverlayPanel
{
	private final CustomCgPrepPlugin plugin;
	private final CustomCgPrepConfig config;

	@Inject
	CustomCgPrepOverlay(CustomCgPrepPlugin plugin, CustomCgPrepConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showOverlay())
		{
			return null; // setting "Show overlay" off (user, 1.8.2)
		}
		CustomCgPrepScript s = plugin.script();
		panelComponent.setPreferredSize(new Dimension(280, 0));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Auto Gauntlet v" + CustomCgPrepPlugin.version + (config.dryRun() ? "  [DRY RUN]" : ""))
			.color(config.dryRun() ? Color.YELLOW : Color.GREEN)
			.build());
		if (config.dryRun())
		{
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("DRY RUN: plans only, sends NO clicks").color(Color.RED).build());
		}

		if (s.handover.isActive())
		{
			panelComponent.getChildren().add(TitleComponent.builder().text(s.handover.banner()).color(Color.ORANGE).build());
			for (String line : s.handover.lines())
			{
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftColor(
					line.startsWith("WARNING") ? Color.RED : Color.WHITE).build());
			}
			panelComponent.getChildren().add(LineComponent.builder().left(config.autoLoop() ? "After the run: the bot loots the chest and goes again" : "After the run: " + config.continueKey() + " to go again").build());
			return super.render(g);
		}

		String state = s.getCurrentState() == null ? "-" : s.getCurrentState().name()
			+ (s.paused && !"PAUSED".equals(s.getCurrentState().name()) ? " (PAUSED)" : "");
		long reenter = s.reenterAtMs;
		String blocked = s.enterBlocked != null ? s.enterBlocked : s.chestBlocked ? "the Reward Chest won't open (see chat)" : null;
		if (s.getCurrentState() == CgState.LOBBY_SETUP && blocked != null)
		{
			state += " (stopped: " + blocked + ")";
		}
		else if (s.getCurrentState() == CgState.LOBBY_SETUP && reenter > 0)
		{
			long left = Math.max(0, reenter - System.currentTimeMillis());
			state += " (going back in: " + ((left + 999) / 1000) + "s)";
		}
		else if (s.waitingReason != null)
		{
			state += " (waiting: " + s.waitingReason + ")";
		}
		boolean boss = s.getCurrentState() == CgState.BOSS;
		BossFight bf = s.boss;
		row("State", state + (boss ? "  fight " + bf.mode + (bf.active ? "" : " (no boss seen)") : ""));
		row("Mode", String.valueOf(config.mode()));
		row("Loadout", String.valueOf(config.profile()));
		int t = s.secondsLeft;
		row("Prep timer", t < 0 ? "-" : (t / 60) + ":" + String.format("%02d", t % 60));
		row("Room", s.maze.roomX() + "," + s.maze.roomZ() + "  found " + s.maze.foundRoomCount() + "/49");
		if (boss)
		{
			FightPlan fp = bf.lastPlan;
			row("Goal", "Fight the Hunllef");
			row("Last", fp == null ? "-" : shorten(fp + " " + bf.lastClicks));
			row("Missing", "-");
		}
		else
		{
			PrepGoal goal = s.goal;
			boolean youPrep = config.autoMode() == CustomCgPrepConfig.AutoMode.BOSS_ONLY && s.getCurrentState() != CgState.LOBBY_SETUP;
			row("Goal", youPrep ? "You prep (Boss Only)" : goal == null ? "-" : goal.headline());
			row("Last", shorten(s.lastAction));
			// Recomputed here from the last bag reading, so a changed setting shows at once (also
			// while paused or in the lobby).
			Ledger l = s.ledger;
			Needs n = l == null ? s.needs : Needs.goal(l,
				PrepPlanner.profileNow(l, config.profile()),
				config.fishTarget(), config.shardBuffer(), config.potionTarget(), s.vialsSmashSetting());
			row("Missing", n == null ? "-" : n.toString());
		}

		panelComponent.getChildren().add(LineComponent.builder().left(" ").build());
		RunStats st = s.stats;
		row("Runs", st == null ? "-" : String.valueOf(st.runs()));
		row("Setup deaths", st == null ? "-" : String.valueOf(st.setupDeaths()));
		row("Boss deaths", st == null ? "-" : String.valueOf(st.bossDeaths()));
		row("GP", st == null ? "-" : st.gp());
		return super.render(g);
	}

	/** One line: long action notes are cut, the full text is in the decision log. */
	private static String shorten(String s)
	{
		return s == null ? "-" : s.length() > 40 ? s.substring(0, 39) + "…" : s;
	}

	private void row(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
	}
}

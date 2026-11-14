package net.runelite.client.plugins.custom.customcgprep.boss;

/**
 * The fight decision for the coming tick, from the {@link FightModel}. Pure: no game calls, so the
 * simulator (tools/sim) runs exactly this.
 *
 *  1. Protection: the style of the next boss hit to land (in flight first, else the predicted style).
 *  2. Weapon: the one the boss's overhead doesn't block (staff vs Protect from Missiles, bow vs
 *     Protect from Magic); reactive to the overhead change on our 6th off-prayer swing.
 *  3. Movement: {@link MovePlanner} (floor, tornadoes, stomp vs. lost attack ticks).
 *  4. Food/potion thresholds.
 *  5. Offensive prayer: on for the tick of each swing only (lazy flick) by default.
 */
public final class HunllefBrain
{
	private final BrainConfig c;
	private final MovePlanner planner;
	private int lastEatTick = -100;
	private int lastDrinkTick = -100;
	private int lastEatSent = -100;
	private int lastDrinkSent = -100;
	public MovePlanner.Result lastMove;
	private final java.util.Random rnd;
	/** 1.8.1 long run: the end tile being run to (-1 = none), where the click went out from, our tile last tick. */
	private int runDest = -1;
	private int runFrom = -1;
	private int runLast = -1;
	/** 5:1: the cycle (overhead switch count) the sceptre hit is tried in, since when, and the one given up on. */
	private int meleeCycle = -1;
	private int meleeStart = -1;
	private int meleeGaveUp = -1;
	/** The cycle whose sceptre route was started: kept with twice the slack, so it doesn't flip each tick. */
	private int meleeGoing = -1;
	/** This tick's plan is the sceptre hit (no soft eats: the click is needed for the wield). */
	private boolean meleeActive;
	/** A run click this tick's plan wants (confirmed in {@link #sent}). */
	private int runWanted = -1;
	private int runWantedFrom = -1;

	public HunllefBrain(BrainConfig c)
	{
		this(c, new java.util.Random());
	}

	public HunllefBrain(BrainConfig c, java.util.Random rnd)
	{
		this.c = c;
		this.rnd = rnd;
		this.planner = new MovePlanner(c, rnd);
	}

	public MovePlanner planner()
	{
		return planner;
	}

	/** A click really went out (the executor/sim reports it): eats and drinks are spaced from these. */
	public void sent(Click k, int tick)
	{
		if (k.type == Click.Type.MOVE)
		{
			int q = Arena.t(k.x, k.y);
			if (q == runWanted)
			{
				runDest = q;
				runFrom = runWantedFrom;
				runLast = runWantedFrom;
			}
			else
			{
				runDest = -1;
			}
		}
		else if (k.type == Click.Type.ATTACK)
		{
			runDest = -1; // attacking stops the run
		}
		if (k.type == Click.Type.EAT)
		{
			lastEatSent = tick;
		}
		else if (k.type == Click.Type.DRINK)
		{
			lastDrinkSent = tick;
		}
	}

	/**
	 * Ticks a protection click for {@code want} can wait and still be up in time: it must be on by
	 * the next attack's animation tick, or by the earliest landing of a hit already in flight that
	 * it covers. A click at tick t is on from t + 1.
	 */
	static int protectSlack(FightModel m, Style want)
	{
		FightObs o = m.o;
		int t = m.tick;
		if (want == null || want == o.activeProtection())
		{
			return 99;
		}
		int deadline = Integer.MAX_VALUE;
		for (FightModel.Pending h : m.pending)
		{
			if (h.style == want && h.holdUntil() >= t + 1)
			{
				deadline = Math.min(deadline, Math.max(t + 1, h.animTick + 1));
			}
		}
		int next = m.nextAttackTick();
		if (next < 0)
		{
			return 0;
		}
		while (next < t + 1)
		{
			next += 5;
		}
		if (m.nextStyle == want)
		{
			deadline = Math.min(deadline, next);
		}
		if (deadline == Integer.MAX_VALUE)
		{
			deadline = next;
		}
		return Math.max(0, deadline - (t + 1));
	}

	/** Would standing still on our tile risk damage in the next 2 ticks? */
	static boolean dangerHere(FightModel m, int t)
	{
		FightObs o = m.o;
		for (int k = 1; k <= 2; k++)
		{
			if (m.floorHit(o.px, o.py, t + k))
			{
				return true;
			}
			if (Arena.underBoss(o.px, o.py, o.bx, o.by) && m.attackTickAt(t + k))
			{
				return true;
			}
		}
		for (int i = 0; i < o.torN; i++)
		{
			if (Arena.cheb(o.px, o.py, o.torX[i], o.torY[i]) <= 2)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * 5:1 (experimental, user 1.9.14). The overhead switches to the style of our 6th off-prayer hit,
	 * so with a T3 weapon and a weaker other one the 6th hit is picked to leave the T3 unblocked:
	 * while the boss prays melee, the weak weapon (it then prays that); while it prays the weak
	 * style, a sceptre hit (it then prays melee). About 10 of 12 hits with the T3 instead of 6. The
	 * sceptre hit needs a tile next to the boss: only taken when the way to one costs about
	 * what the planner's own plan does and no tornadoes are out, else the T3 takes the 6th hit (the
	 * plain switching) and it's tried again next cycle.
	 */
	private void fiveToOne(FightModel m, FightPlan p, MovePlanner.Result r, int here)
	{
		Style main = c.fiveToOne ? m.mainStyle() : null;
		if (main == null || m.protects == null || m.protects == main)
		{
			return;
		}
		// No extra swaps while tornadoes are out (user, 1.9.26): 5:1's swap to the weak weapon and
		// back took the clicks the dodges needed, and a stack of three hit for 54 (log 11-14 20:15).
		// Plain switching then: the weapon in hand while it isn't blocked (the T3 if it's the
		// sceptre), so the only swap is the one the boss's overhead forces.
		if (m.o.torN > 0)
		{
			Style held = m.o.weapon;
			boolean unblocked = (held == Style.RANGED || held == Style.MAGIC) && held != m.protects;
			p.weapon = unblocked ? held : main;
			return;
		}
		if (m.protects == Style.MELEE)
		{
			p.weapon = m.offHits >= 5 ? main.flip() : main;
			if (m.offHits >= 5)
			{
				p.why = p.why + " 5:1 weak hit";
				holdForSwitch(m, p);
			}
			return;
		}
		FightObs o = m.o;
		int t = m.tick;
		if (m.offHits < 5 || !o.sceptre || meleeGaveUp == m.protectChanges)
		{
			return;
		}
		if (meleeCycle != m.protectChanges)
		{
			meleeCycle = m.protectChanges;
			meleeStart = t;
		}
		int ready = m.readyTick < 0 ? t : m.readyTick;
		boolean inTime = t - meleeStart <= c.meleeWait;
		// The planner routes to a tile next to the boss itself (1.9.22): with the swing only counted
		// there it finds a way round the boss and the damaging floor. The old straight-run check saw
		// only the floor side (log 11-14 19:34: 13 of 20 tiles were safe, every straight run crossed
		// the floor or went under the boss).
		// Tornadoes too (1.9.23): the planner prices them like any dodge; holding off until they were
		// gone outlasted the wait (a phase is ~20 ticks, log 11-14 19:45).
		MovePlanner.Result mr = null;
		MovePlanner.Result hold = null;
		double goCost = Double.MAX_VALUE;
		if (inTime)
		{
			try
			{
				m.meleeOnly = true;
				m.meleeGoal = true;
				mr = planner.plan(m);
				m.meleeGoal = false;
				// Priced without the distance-to-go term, like the hold plan it's compared with.
				goCost = planner.evalPath(m, mr.path, MovePlanner.stepClicks(here, mr.path));
				m.meleeOnly = false;
				// The other choice is holding the swing (no T3 hit either), not the usual plan with a
				// T3 swing in it (1.9.23: compared with that, routes 0.2-0.4 dearer were refused all
				// through a tornado phase and the T3 took the 6th hit).
				m.noSwing = true;
				hold = planner.plan(m);
			}
			finally
			{
				m.meleeOnly = false;
				m.meleeGoal = false;
				m.noSwing = false;
			}
		}
		int target = mr == null ? -1 : firstMeleeTile(mr.path, o);
		double slack = meleeGoing == m.protectChanges ? 2 * c.meleeTolerance : c.meleeTolerance;
		boolean onTile = meleeTile(o.px, o.py, o.bx, o.by);
		// Going: the route reaches a tile next to the boss, or heads for one (longer than the horizon).
		boolean ok = mr != null && (target >= 0 || mr.x >= 0 || onTile) && goCost <= hold.cost + slack;
		if (!ok)
		{
			if (inTime)
			{
				// No safe way to the boss right now: hold the 6th swing a few ticks rather than give it
				// to the T3 (user, 1.9.22), moving as the no-swing plan says. A sceptre already in hand
				// stays there (no swap back and forth while the route comes and goes).
				p.weapon = o.weapon == Style.MELEE ? Style.MELEE : main;
				p.attack = false;
				if (!p.continueRun)
				{
					p.moveX = hold.x;
					p.moveY = hold.y;
					p.urgent = hold.x >= 0 && dangerHere(m, t);
					runWanted = -1;
				}
				// Any time while waiting: with the T3 the next swing would be the 6th, and with the
				// sceptre the game walks us to the boss by itself.
				if (o.attacking && !p.moving())
				{
					p.stopAttack = true;
				}
				p.why = p.why + " 5:1 waiting (" + (mr == null || (target < 0 && mr.x < 0 && !onTile) ? "no route to the boss"
					: String.format("cost %.1f > %.1f", goCost, hold.cost + slack)) + ")";
				return;
			}
			meleeGaveUp = m.protectChanges;
			p.why = p.why + " 5:1 gave up (waited " + c.meleeWait + " ticks): T3 takes the 6th";
			return;
		}
		meleeActive = true;
		meleeGoing = m.protectChanges;
		runWanted = -1;
		runDest = -1;
		p.continueRun = false;
		p.weapon = Style.MELEE;
		p.cost = goCost;
		if (mr.x < 0 && onTile)
		{
			p.moveX = -1;
			p.moveY = -1;
			p.urgent = false;
			// Only with the sceptre in hand: an attack click with the T3 is the 6th hit (log 11-14 18:32).
			p.attack = !o.attacking && o.weapon == Style.MELEE;
			p.why = "5:1 sceptre hit" + (o.meleeSwing ? " (swung)" : "");
		}
		else if (mr.x >= 0)
		{
			p.moveX = mr.x;
			p.moveY = mr.y;
			p.attack = false;
			p.urgent = dangerHere(m, t);
			p.why = "5:1 to " + (target >= 0 ? Arena.x(target) + "," + Arena.y(target) : "the boss (long way round)") + " for the sceptre hit";
		}
		else
		{
			// The route starts by standing a tick (floor about to clear): wait with the sceptre ready.
			p.moveX = -1;
			p.moveY = -1;
			p.attack = false;
			p.why = "5:1 waiting a tick, then to " + (target >= 0 ? Arena.x(target) + "," + Arena.y(target) : "the boss");
		}
		holdForSwitch(m, p);
	}

	/**
	 * 5:1's 6th hit (user, 1.9.21): the switch goes out first and no attack is clicked until the new
	 * weapon is in hand. If the wrong one would swing before that (we're attacking and the next
	 * swing is due), stop attacking first: the T3 kept taking the 6th hit while the wield click
	 * waited behind others (log 11-14 18:32).
	 */
	private static void holdForSwitch(FightModel m, FightPlan p)
	{
		FightObs o = m.o;
		if (p.weapon == null || o.weapon == p.weapon)
		{
			return;
		}
		p.switchFirst = true;
		p.attack = false;
		int ready = m.readyTick < 0 ? m.tick : m.readyTick;
		if (o.attacking && !p.moving() && ready <= m.tick + 1)
		{
			p.stopAttack = true;
			p.why = p.why + " (stop: switch first)";
		}
	}

	/** The first tile of the planned route that's next to a side of the boss, or -1. */
	private static int firstMeleeTile(int[] path, FightObs o)
	{
		for (int q : path)
		{
			if (meleeTile(Arena.x(q), Arena.y(q), o.bx, o.by))
			{
				return q;
			}
		}
		return -1;
	}

	/** A tile a melee weapon hits the 5x5 boss from: next to one of its sides (not a corner, not under it). */
	static boolean meleeTile(int x, int y, int bx, int by)
	{
		boolean alongX = x >= bx && x < bx + Arena.BOSS_SIZE && (y == by - 1 || y == by + Arena.BOSS_SIZE);
		boolean alongY = y >= by && y < by + Arena.BOSS_SIZE && (x == bx - 1 || x == bx + Arena.BOSS_SIZE);
		return alongX || alongY;
	}

	public FightPlan decide(FightModel m)
	{
		FightObs o = m.o;
		int t = m.tick;
		FightPlan p = new FightPlan();

		p.protect = m.protectionFor(t + 1);
		p.weapon = m.weaponToUse();

		MovePlanner.Result r = planner.plan(m);
		lastMove = r;
		p.cost = r.cost;
		boolean under = Arena.underBoss(o.px, o.py, o.bx, o.by);
		int here = Arena.t(o.px, o.py);
		runWanted = -1;
		boolean keepRunning = false;
		if (c.longRuns && runDest >= 0 && r.path.length > 0)
		{
			// Still running to the last long click's tile? Off its line (a misclick), not moving (a
			// lost click) or there already: the run is over. Otherwise keep running while it costs
			// about what the new plan does: no walk click this tick.
			int dx = Arena.x(runDest);
			int dy = Arena.y(runDest);
			boolean ok = here != runDest && here != runLast
				&& MovePlanner.onWalk(Arena.x(runFrom), Arena.y(runFrom), dx, dy, here);
			if (ok)
			{
				double plan = planner.evalPath(m, r.path, MovePlanner.stepClicks(here, r.path));
				int[] rest = MovePlanner.walk(o.px, o.py, dx, dy, r.path.length);
				double run = planner.evalPath(m, rest, new boolean[r.path.length]);
				ok = run <= plan + c.runTolerance && !MovePlanner.landsUnderBoss(rest, o.bx, o.by);
			}
			if (ok)
			{
				keepRunning = true;
				p.moveX = dx;
				p.moveY = dy;
				p.continueRun = true;
				p.why = "running";
				p.urgent = dangerHere(m, t);
			}
			else
			{
				runDest = -1;
			}
		}
		runLast = here;
		if (keepRunning)
		{
			// the move is set
		}
		else if (r.x >= 0)
		{
			p.moveX = r.x;
			p.moveY = r.y;
			p.why = "planner";
			p.urgent = dangerHere(m, t);
			if (c.longRuns && rnd.nextDouble() < c.runChance)
			{
				int run = planner.runTarget(m, r, planner.evalPath(m, r.path, MovePlanner.stepClicks(here, r.path)));
				if (run >= 0)
				{
					p.moveX = Arena.x(run);
					p.moveY = Arena.y(run);
					p.why = "run";
					runWanted = run;
					runWantedFrom = here;
				}
			}
		}
		else
		{
			p.attack = !o.attacking && !under;
		}
		meleeActive = false;
		fiveToOne(m, p, r, here);
		p.protectSlack = protectSlack(m, p.protect);
		p.moveFirst = c.dodgeFirst && p.moving() && p.urgent && p.protectSlack >= 1;

		int ready = m.readyTick < 0 ? t : m.readyTick;
		boolean swingNext = !p.moving() && !under && ready <= t + 1;

		// Food: paddlefish heal 20 and push our next swing back 3 ticks.
		int sinceEat = t - (c.eatOnSend ? lastEatSent : lastEatTick);
		int sinceDrink = t - (c.eatOnSend ? lastDrinkSent : lastDrinkTick);
		if (o.fish > 0 && sinceEat >= 3 && o.hp <= o.hpMax - 18)
		{
			boolean hard = o.hp <= c.eatBelow;
			boolean soft = o.hp <= c.eatBelowSoft && ready >= t + 3 && !meleeActive;
			if (hard || soft)
			{
				p.eat = true;
				lastEatTick = t;
			}
		}
		// Egniol dose: about 7 + level / 4 prayer.
		if (o.doses > 0 && sinceDrink >= 3 && o.prayer <= c.drinkBelow)
		{
			p.drink = true;
			lastDrinkTick = t;
		}

		switch (c.offense)
		{
			case ALWAYS:
				p.offense = o.prayer > 1 || p.drink;
				break;
			case FLICK:
				p.offense = swingNext && (o.prayer > 1 || p.drink);
				break;
			default:
				p.offense = false;
		}
		return p;
	}
}

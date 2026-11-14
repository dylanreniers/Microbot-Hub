package net.runelite.client.plugins.custom.customcgprep.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * The prep route, as room cells (user, 2026-11-12: the old custom Gauntlet plugin's route). Pure
 * geometry, no game state.
 *
 * The maze is centred on the Hunllef room (3,3); the start room is always one of its 4 neighbours.
 * Let d = the step from the boss to the start ("away from the boss") and p = the sideways step.
 *
 *  PHASE 1 (circle): the 7 other rooms of ring 1 around the Hunllef room, starting next to the
 *  start on the chosen side, all the way round; then ring 2 the same way round, entered outwards
 *  from where ring 1 ended. Never the outer rim (ring 3, the demi-boss rooms).
 *
 *  PHASE 2 (out): straight away from the Hunllef room to the outer rim: start + d, start + 2d.
 *
 *  RIM lap: the 24 outer-ring rooms in order, from the out path's rim room, first along +-p.
 */
public final class Route
{
	public static final int N = 7;
	public static final int BOSS = 3;

	private Route()
	{
	}

	/** {dx, dz}: from the boss room towards the start room. */
	public static int[] away(int sx, int sz)
	{
		return new int[]{Integer.signum(sx - BOSS), Integer.signum(sz - BOSS)};
	}

	/** {px, pz}: sideways, perpendicular to away(). */
	public static int[] side(int sx, int sz)
	{
		int[] d = away(sx, sz);
		return new int[]{Math.abs(d[1]), Math.abs(d[0])};
	}

	/**
	 * Phase 1: ring 1 around the Hunllef room from the start (first step {@code sign} * p, +1 / -1),
	 * then ring 2 the same way round, entered outwards from ring 1's last room. Start room excluded.
	 */
	public static List<int[]> circle(int sx, int sz, int sign)
	{
		int[] p = side(sx, sz);
		int s = sign < 0 ? -1 : 1;
		List<int[]> ring1 = ring(new int[]{sx, sz}, new int[]{s * p[0], s * p[1]}, 1);
		boolean forward = forward(perimeter(1), sx, sz, s * p[0], s * p[1]);
		List<int[]> out = new ArrayList<>(ring1.subList(1, ring1.size()));
		int[] last = ring1.get(ring1.size() - 1);
		int[] entry2 = {BOSS + 2 * (last[0] - BOSS), BOSS + 2 * (last[1] - BOSS)};
		List<int[]> c2 = perimeter(2);
		int i = indexOf(c2, entry2[0], entry2[1]);
		for (int k = 0; k < c2.size(); k++)
		{
			out.add(c2.get(Math.floorMod(forward ? i + k : i - k, c2.size())));
		}
		return out;
	}

	/** Phase 2: straight away from the Hunllef room, from the start to the outer rim. */
	public static List<int[]> out(int sx, int sz)
	{
		int[] d = away(sx, sz);
		List<int[]> out = new ArrayList<>();
		for (int k = 1; inGrid(sx + k * d[0], sz + k * d[1]); k++)
		{
			out.add(new int[]{sx + k * d[0], sz + k * d[1]});
		}
		return out;
	}

	/** The rim lap after {@link #out}: all 24 rim rooms from the out path's rim room, first along {@code sign} * p. */
	public static List<int[]> rim(int sx, int sz, int sign)
	{
		List<int[]> o = out(sx, sz);
		int[] p = side(sx, sz);
		int s = sign < 0 ? -1 : 1;
		return ring(o.get(o.size() - 1), new int[]{s * p[0], s * p[1]}, BOSS);
	}

	/** The rooms of ring {@code radius} around the boss, from {@code entry}, going first in direction {@code first} {dx,dz}. */
	static List<int[]> ring(int[] entry, int[] first, int radius)
	{
		List<int[]> cycle = perimeter(radius);
		int i = indexOf(cycle, entry[0], entry[1]);
		if (i < 0)
		{
			return cycle;
		}
		boolean forward = forward(cycle, entry[0], entry[1], first[0], first[1]);
		List<int[]> out = new ArrayList<>();
		for (int k = 0; k < cycle.size(); k++)
		{
			out.add(cycle.get(Math.floorMod(forward ? i + k : i - k, cycle.size())));
		}
		return out;
	}

	/** Is the step from (x, z) in direction {dx, dz} the cycle's own order? */
	private static boolean forward(List<int[]> cycle, int x, int z, int dx, int dz)
	{
		int i = indexOf(cycle, x, z);
		int[] next = cycle.get((i + 1) % cycle.size());
		return next[0] - x == dx && next[1] - z == dz;
	}

	/**
	 * Ring {@code radius} around the boss, one way round, from its low corner: along the low z edge,
	 * up the high x edge, back along the high z edge, down the low x edge (radius 3 = the outer rim:
	 * (0,0)..(6,0), (6,1)..(6,6), (5,6)..(0,6), (0,5)..(0,1)).
	 */
	static List<int[]> perimeter(int radius)
	{
		int lo = BOSS - radius;
		int hi = BOSS + radius;
		List<int[]> c = new ArrayList<>();
		for (int x = lo; x <= hi; x++)
		{
			c.add(new int[]{x, lo});
		}
		for (int z = lo + 1; z <= hi; z++)
		{
			c.add(new int[]{hi, z});
		}
		for (int x = hi - 1; x >= lo; x--)
		{
			c.add(new int[]{x, hi});
		}
		for (int z = hi - 1; z > lo; z--)
		{
			c.add(new int[]{lo, z});
		}
		return c;
	}

	public static boolean onRing(int x, int z)
	{
		return x == 0 || z == 0 || x == N - 1 || z == N - 1;
	}

	/** Side of a room relative to the start: +1 / -1 along p, 0 if in line with the start. */
	public static int sideOf(int sx, int sz, int x, int z)
	{
		int[] p = side(sx, sz);
		return Integer.signum((x - sx) * p[0] + (z - sz) * p[1]);
	}

	/** In a straight line from the start, at most 2 rooms away (the user's "close to home"). */
	public static boolean nearStartStraight(int sx, int sz, int x, int z)
	{
		int dx = Math.abs(x - sx);
		int dz = Math.abs(z - sz);
		return (dx == 0 || dz == 0) && dx + dz >= 1 && dx + dz <= 2 && !(x == BOSS && z == BOSS);
	}

	/** Within 2 rooms of the start (straight or one diagonal step), not the boss room. */
	public static boolean nearStart(int sx, int sz, int x, int z)
	{
		int d = Math.abs(x - sx) + Math.abs(z - sz);
		return d >= 1 && d <= 2 && !(x == BOSS && z == BOSS);
	}

	static boolean inGrid(int x, int z)
	{
		return x >= 0 && z >= 0 && x < N && z < N;
	}

	private static int indexOf(List<int[]> c, int x, int z)
	{
		for (int i = 0; i < c.size(); i++)
		{
			if (c.get(i)[0] == x && c.get(i)[1] == z)
			{
				return i;
			}
		}
		return -1;
	}
}

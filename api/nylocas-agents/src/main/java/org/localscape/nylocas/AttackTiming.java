package org.localscape.nylocas;

/** Short approach estimates; adapters remain responsible for collision and line of sight. */
public final class AttackTiming
{
	private AttackTiming() { }

	public static Tile position(Tile point, int lane, int age, int ticks)
	{
		int steps = Math.max(0, Math.min(ticks, 9 - age));
		return lane == 0 ? point.dx(steps) : lane == 2 ? point.dx(-steps) : lane == 1 ? point.dy(steps) : point;
	}

	public static Tile attackTile(Tile from, Tile target, boolean big, int range)
	{
		int size = big ? 2 : 1;
		int x = Math.max(target.getX() - range, Math.min(from.getX(), target.getX() + size - 1 + range));
		int y = Math.max(target.getY() - range, Math.min(from.getY(), target.getY() + size - 1 + range));
		return new Tile(Math.max(3290, Math.min(3301, x)), Math.max(4243, Math.min(4254, y)), from.getPlane());
	}

	public static int distance(Tile from, Tile target, boolean big)
	{
		int size = big ? 2 : 1;
		return Math.max(Math.max(target.getX() - from.getX(), from.getX() - target.getX() - size + 1),
			Math.max(target.getY() - from.getY(), from.getY() - target.getY() - size + 1));
	}
}

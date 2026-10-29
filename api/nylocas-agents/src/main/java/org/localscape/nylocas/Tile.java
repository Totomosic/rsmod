package org.localscape.nylocas;

/** Immutable source-room tile. Y is the north/south axis; adapters normalize instances. */
public final class Tile
{
	private final int x, y, plane;
	public Tile(int x, int y, int plane) { this.x = x; this.y = y; this.plane = plane; }
	public int getX() { return x; }
	public int getY() { return y; }
	public int getPlane() { return plane; }
	public Tile dx(int amount) { return new Tile(x + amount, y, plane); }
	public Tile dy(int amount) { return new Tile(x, y + amount, plane); }
	public int distanceTo(Tile other)
	{
		return plane == other.plane ? Math.max(Math.abs(x - other.x), Math.abs(y - other.y)) : Integer.MAX_VALUE;
	}
	@Override public boolean equals(Object value)
	{
		if (!(value instanceof Tile)) return false;
		Tile other = (Tile) value;
		return x == other.x && y == other.y && plane == other.plane;
	}
	@Override public int hashCode() { return (x * 31 + y) * 31 + plane; }
	@Override public String toString() { return x + "," + y + "," + plane; }
}

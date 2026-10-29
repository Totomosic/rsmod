package org.localscape.nylocas;

/** Commit the next attack, not a speculative kill. Candidates still validate it on every observation. */
public final class AttackIntent<W>
{
	private int key = -1, until;
	private W weapon;

	public boolean matches(int targetKey, W targetWeapon, int tick)
	{
		return key == targetKey && weapon == targetWeapon && tick <= until;
	}

	public void select(int targetKey, W targetWeapon, int tick)
	{
		// A stalled approach must eventually compete with all other work again.
		if (!matches(targetKey, targetWeapon, tick)) until = tick + 12;
		key = targetKey;
		weapon = targetWeapon;
	}

	public void clear() { key = -1; weapon = null; }
}

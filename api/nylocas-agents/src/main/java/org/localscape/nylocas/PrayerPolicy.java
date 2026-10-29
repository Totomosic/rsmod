package org.localscape.nylocas;

/** Shared protection priorities and potion timing; adapters supply visible, in-range threats. */
public final class PrayerPolicy
{
	private PrayerPolicy() {}

	/** Styles are melee=0, range=1, magic=2. Keep the current overhead when no threat exists. */
	public static int protection(int[] bigs, int[] smalls, int current)
	{
		int best = -1;
		for (int style = 0; style < 3; style++)
		{
			if (bigs[style] == 0 && smalls[style] == 0) continue;
			if (best < 0 || bigs[style] > bigs[best]
				|| bigs[style] == bigs[best] && smalls[style] > smalls[best]
				|| bigs[style] == bigs[best] && smalls[style] == smalls[best] && style == current)
				best = style;
		}
		return best < 0 ? current : best;
	}

	public static boolean restore(int current, int maximum, int threshold, int tick, int nextDrink)
	{
		int restored = Math.min(maximum, 7 + maximum / 4);
		return maximum > 0 && current <= threshold && maximum - current >= restored && tick >= nextDrink;
	}
}

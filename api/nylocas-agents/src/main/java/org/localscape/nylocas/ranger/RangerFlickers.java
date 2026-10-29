package org.localscape.nylocas.ranger;

import org.localscape.nylocas.NylocasFlickers;
import org.localscape.nylocas.ranger.RangerStrategy.Crab;
import org.localscape.nylocas.ranger.RangerStrategy.Style;

public final class RangerFlickers
{
	public static Style at(Crab crab, int age)
	{
		return Style.values()[NylocasFlickers.at(crab.wave, crab.lane.ordinal(), crab.slot, crab.style.ordinal(), age)];
	}

	public static boolean passiveMelee(Crab crab, int tick)
	{
		return NylocasFlickers.passiveMelee(crab.wave, crab.lane.ordinal(), crab.slot, crab.style.ordinal(), tick - crab.born);
	}

	public static boolean matchesNext(Crab crab, Style style, int tick)
	{
		int age = tick - crab.born;
		return crab.style == style && (at(crab, age) != crab.style || at(crab, age + 1) == style);
	}
}

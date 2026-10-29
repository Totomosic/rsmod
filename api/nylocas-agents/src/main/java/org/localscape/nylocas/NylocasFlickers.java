package org.localscape.nylocas;

/** Recording-verified rotations. Styles: melee=0, range=1, magic=2; lanes: west=0, south=1, east=2, split=3. */
public final class NylocasFlickers
{
	private static final String[][] ROTATIONS = {
		{"AMRA", null, "MARA", null, null, "RARA"}, // 16
		{"AMAM", null, "AMAM", null, "AMAM", null},
		{"RARA", null, "RARA", null, "RARA", null},
		{"AMAM", null, "AMAM", null, "AMAM", null},
		{"MRMR", null, "ARMR", null, "MRMR", null},
		{"RMRA", "RMRM", "MRMR", "MAMR", "AMRA", "ARAM"},
		{"ARMR", null, "ARMA", "RAMA", "MRMR", null},
		{"ARMR", null, "RAMA", null, "RARM", "ARMR"},
		{"M", null, "A", null, "RAMA", null},
		{"AMAM", null, "R", null, "M", null},
		{"A", null, "MAMR", null, "A", null},
		{"AMAM", null, "MARA", null, "A", null},
		{"RAMR", "AMAM", "AMRA", "MRMA", "RMAM", "MAMR"},
		{"RMAM", "ARMA", "M", null, "RMRM", "MRAR"},
		{"A", null, "ARMA", "MRMA", "RMRM", null},
		{"RMRM", "ARAR", "AMRM", "MARM", "RMRA", "MRAR"}
	};

	private NylocasFlickers() {}

	public static int at(int wave, int lane, char slot, int liveStyle, int age)
	{
		String rotation = rotation(wave, lane, slot);
		if (rotation == null) return liveStyle;
		int phase = age >= 26 ? 3 : age >= 7 ? 2 : age >= 5 ? 1 : 0;
		char style = rotation.charAt(Math.min(phase, rotation.length() - 1));
		return style == 'M' ? 0 : style == 'R' ? 1 : 2;
	}

	public static boolean passiveMelee(int wave, int lane, char slot, int liveStyle, int age)
	{
		String rotation = rotation(wave, lane, slot);
		return age >= 26 && liveStyle == 0 && rotation != null && rotation.length() == 4 && rotation.charAt(3) == 'M';
	}

	private static String rotation(int wave, int lane, char slot)
	{
		if (wave < 16 || wave > 31 || lane == 3) return null;
		int index = lane == 2 ? (slot == 'N' ? 1 : 0) : lane == 1 ? (slot == 'E' ? 3 : 2) : (slot == 'N' ? 5 : 4);
		return ROTATIONS[wave - 16][index];
	}
}

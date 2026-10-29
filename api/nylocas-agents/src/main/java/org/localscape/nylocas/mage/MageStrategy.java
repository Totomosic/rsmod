package org.localscape.nylocas.mage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.localscape.nylocas.Tile;
import org.localscape.nylocas.NylocasFlickers;
import org.localscape.nylocas.AttackIntent;
import org.localscape.nylocas.AttackTiming;

/** Mage-only policy. Offsets are request ticks, one before the Blert animation observations. */
public final class MageStrategy
{
	public static final int MAX_WAVE_AGE = 4;
	public enum Style { MELEE, RANGE, MAGIC }
	public enum Lane { WEST, SOUTH, EAST, SPLIT }
	public enum Weapon
	{
		EYE(31113, Style.MAGIC, 3, 8, 3),
		SHADOW(27275, Style.MAGIC, 5, 10, 3),
		// Spell casts already reach ten tiles; an ordinary staff has no longrange stance.
		BARRAGE(27624, Style.MAGIC, 5, 10, 0),
		SCYTHE(22325, Style.MELEE, 5, 1, 1),
		SULPHUR(29084, Style.MELEE, 4, 1, 1),
		// Pummel is Dinh's only attacking stance; Block cannot launch the special.
		DINH(21015, Style.MELEE, 5, 1, 0),
		DARTS(11230, Style.RANGE, 2, 3, 1);

		public final int itemId;
		public final Style style;
		public final int cooldown;
		public final int range;
		public final int stance;
		Weapon(int itemId, Style style, int cooldown, int range, int stance)
		{
			this.itemId = itemId; this.style = style; this.cooldown = cooldown; this.range = range; this.stance = stance;
		}
	}

	public static final class Crab
	{
		public final int key, index, born, wave;
		public final Lane lane;
		public final char slot;
		public final boolean big;
		public Tile point;
		public Style style;
		public boolean fighting, dying;
		public int hp, claimedUntil, ownHitUntil, ownHitHp;
		public Crab(int key, int index, int born, int wave, Lane lane, char slot, boolean big, Style style, Tile point)
		{
			this.key = key; this.index = index; this.born = born; this.wave = wave;
			this.lane = lane; this.slot = slot; this.big = big; this.style = style; this.point = point;
			hp = big ? 16 : 8;
		}
		public int distance(Tile from)
		{
			int size = big ? 2 : 1;
			return Math.max(Math.max(point.getX() - from.getX(), from.getX() - point.getX() - size + 1),
				Math.max(point.getY() - from.getY(), from.getY() - point.getY() - size + 1));
		}
		public boolean awaitingHit(int tick) { return ownHitUntil > 0 && tick <= ownHitUntil && hp >= ownHitHp; }
	}

	public static final class Room
	{
		public final List<Crab> crabs = new ArrayList<>();
		public final Set<Integer> obstructed = new HashSet<>();
		public final int[] spawns = new int[32];
		public int wave, ready, specialEnergy;
		public Tile player = tile(3296, 4244);
		public boolean shadowOpening = true;
		public Room() { Arrays.fill(spawns, -1); }
	}

	public static final class Decision
	{
		public final Crab target;
		public final Weapon weapon;
		public final Tile tile;
		public final boolean special;
		public final String rule, reason;
		public Crab approach;
		public boolean pathToTarget;
		public Decision(Crab target, Weapon weapon, Tile tile, boolean special, String rule, String reason)
		{
			this.target = target; this.weapon = weapon; this.tile = tile; this.special = special;
			this.rule = rule; this.reason = reason;
		}
	}

	public static final class Assignment
	{
		public final String name;
		public final int wave, first, last;
		public final Lane lane;
		public final char slot;
		public final boolean big;
		public final Weapon weapon;
		public final Tile stage;
		public Assignment(String name, int wave, Lane lane, char slot, boolean big, Weapon weapon, int first, int last, int x, int y)
		{
			this.name = name; this.wave = wave; this.lane = lane; this.slot = slot; this.big = big;
			this.weapon = weapon; this.first = first; this.last = last; stage = tile(x, y);
		}
		boolean matches(Crab c) { return c.wave == wave && c.lane == lane && c.big == big && (slot == 0 || slot == c.slot); }
	}

	public static final List<Assignment> ASSIGNMENTS = Arrays.asList(
		new Assignment("Opening south", 1, Lane.SOUTH, 'E', false, Weapon.SHADOW, 1, 10, 3296, 4244),
		new Assignment("West magic 2", 2, Lane.WEST, 'N', false, Weapon.EYE, 3, 15, 3292, 4249),
		new Assignment("East magic 3", 3, Lane.EAST, 'N', false, Weapon.EYE, 2, 14, 3298, 4249),
		new Assignment("South big 4", 4, Lane.SOUTH, (char) 0, true, Weapon.EYE, 1, 25, 3295, 4243),
		new Assignment("East magic 5", 5, Lane.EAST, 'N', false, Weapon.EYE, 1, 19, 3301, 4249),
		new Assignment("East melee 4", 4, Lane.EAST, 'S', false, Weapon.SULPHUR, 8, 24, 3300, 4248),
		new Assignment("South melee 5", 5, Lane.SOUTH, 'E', false, Weapon.SULPHUR, 8, 24, 3296, 4243),
		new Assignment("West magic 6", 6, Lane.WEST, 'N', false, Weapon.EYE, 1, 19, 3290, 4249),
		new Assignment("South magic 7", 7, Lane.SOUTH, 'E', false, Weapon.EYE, 2, 19, 3295, 4244),
		new Assignment("West big 8", 8, Lane.WEST, (char) 0, true, Weapon.EYE, 0, 30, 3290, 4249),
		new Assignment("South melee 8", 8, Lane.SOUTH, 'W', false, Weapon.SULPHUR, 8, 24, 3295, 4243),
		new Assignment("West melee 9", 9, Lane.WEST, 'S', false, Weapon.SULPHUR, 10, 27, 3293, 4249),
		new Assignment("East barrage 11", 11, Lane.EAST, (char) 0, false, Weapon.BARRAGE, 0, 2, 3301, 4249),
		new Assignment("South magic 11", 11, Lane.SOUTH, 'W', false, Weapon.EYE, 5, 20, 3297, 4245),
		new Assignment("West big 11", 11, Lane.WEST, (char) 0, true, Weapon.EYE, 8, 25, 3298, 4247),
		new Assignment("West magic 14", 14, Lane.WEST, 'N', false, Weapon.EYE, 0, 19, 3290, 4249),
		new Assignment("South melee 13", 13, Lane.SOUTH, 'W', false, Weapon.SULPHUR, 11, 29, 3292, 4245),
		new Assignment("West melee 14", 14, Lane.WEST, 'S', false, Weapon.SULPHUR, 8, 24, 3291, 4248),
		new Assignment("West melee 15", 15, Lane.WEST, 'N', false, Weapon.SULPHUR, 7, 24, 3290, 4249),
		new Assignment("West ranged 15", 15, Lane.WEST, 'S', false, Weapon.DARTS, 6, 24, 3291, 4248),
		new Assignment("Deferred east big 13", 13, Lane.EAST, (char) 0, true, Weapon.SCYTHE, 25, 40, 3294, 4249),
		new Assignment("East big 19", 19, Lane.EAST, (char) 0, true, Weapon.EYE, 3, 25, 3296, 4248),
		new Assignment("South big 19", 19, Lane.SOUTH, (char) 0, true, Weapon.EYE, 8, 25, 3296, 4246),
		new Assignment("South opener 20", 20, Lane.SOUTH, (char) 0, true, Weapon.EYE, 1, 3, 3296, 4243),
		new Assignment("South big 24", 24, Lane.SOUTH, (char) 0, true, Weapon.EYE, 0, 18, 3295, 4243),
		new Assignment("East melee big 24", 24, Lane.EAST, (char) 0, true, Weapon.SCYTHE, 6, 20, 3299, 4248),
		new Assignment("West melee big 25", 25, Lane.WEST, (char) 0, true, Weapon.SCYTHE, 6, 20, 3291, 4248),
		new Assignment("West big 26", 26, Lane.WEST, (char) 0, true, Weapon.EYE, 2, 25, 3291, 4248),
		new Assignment("West big 27", 27, Lane.WEST, (char) 0, true, Weapon.EYE, 1, 25, 3292, 4248),
		new Assignment("South big 29", 29, Lane.SOUTH, (char) 0, true, Weapon.SCYTHE, 0, 7, 3295, 4243),
		new Assignment("East big 30", 30, Lane.EAST, (char) 0, true, Weapon.EYE, 1, 25, 3297, 4249),
		new Assignment("East magic 31", 31, Lane.EAST, 'N', false, Weapon.EYE, 1, 25, 3300, 4249)
	);

	private static final int[] DELAYS = {0, 4, 4, 4, 4, 16, 4, 12, 4, 12, 8, 8, 8, 8, 8, 8, 4, 12, 8, 12, 16, 8, 12, 8, 8, 8, 4, 8, 4, 4, 4, 0};
	private final Set<String> completed = new HashSet<>();
	private final Set<Integer> opened = new HashSet<>();
	private final AttackIntent<Weapon> intent = new AttackIntent<>();
	private int scythe12 = -1, barrage21 = -1;

	public void reset() { completed.clear(); opened.clear(); intent.clear(); scythe12 = barrage21 = -1; }

	public void attacked(Decision decision, int observationTick)
	{
		attacked(decision, observationTick, observationTick - 1);
	}

	public void attacked(Decision decision, int observationTick, int requestTick)
	{
		intent.clear();
		if (decision.rule != null) completed.add(decision.rule);
		opened.add(decision.target.key);
		// AnimationChanged can precede the next GameTick counter increment.
		// Never move a follow-up earlier than five ticks after the real request.
		int anchor = Math.max(requestTick, observationTick - 1);
		if ("12 east scythe".equals(decision.rule)) scythe12 = anchor;
		if ("21 west barrage".equals(decision.rule)) barrage21 = anchor;
		decision.target.ownHitUntil = observationTick + (decision.weapon.style == Style.MELEE ? 1 : 2);
		decision.target.ownHitHp = decision.target.hp;
	}

	static boolean eligible(Room room, Crab c, int tick)
	{
		return !c.dying && c.claimedUntil <= tick && !c.awaitingHit(tick) && tick - c.born < 49
			&& room.wave - c.wave <= MAX_WAVE_AGE && !rangerOwned(c)
			&& (room.wave < 31 || c.wave != 29);
	}

	public static boolean matchesNext(Crab c, Style style, int tick)
	{
		int age = tick - c.born;
		int now = NylocasFlickers.at(c.wave, c.lane.ordinal(), c.slot, c.style.ordinal(), age);
		int next = NylocasFlickers.at(c.wave, c.lane.ordinal(), c.slot, c.style.ordinal(), age + 1);
		return c.style == style && (now != c.style.ordinal() || next == style.ordinal());
	}

	public static boolean passiveMelee(Crab c, int tick)
	{
		return NylocasFlickers.passiveMelee(c.wave, c.lane.ordinal(), c.slot, c.style.ordinal(), tick - c.born);
	}

	private static final class Window
	{
		final String name;
		final int due, last;
		final Weapon weapon;
		final Tile stage;
		Window(String name, int due, int last, Weapon weapon, int x, int y)
		{
			this.name = name; this.due = due; this.last = last; this.weapon = weapon; stage = tile(x, y);
		}
	}

	private int spawn(Room room, int wave, int tick)
	{
		if (room.spawns[wave] >= 0) return room.spawns[wave];
		if (room.wave != wave - 1 || room.spawns[room.wave] < 0) return -1;
		int due = room.spawns[room.wave] + DELAYS[room.wave];
		// Predictions reserve cooldown, but never fabricate a spawned wave during a retry.
		return due >= tick ? due : due + ((tick - due + 3) / 4) * 4;
	}

	private List<Window> windows(Room room, int tick)
	{
		List<Window> result = new ArrayList<>();
		int s12 = spawn(room, 12, tick), s21 = spawn(room, 21, tick), s22 = spawn(room, 22, tick), s28 = spawn(room, 28, tick);
		if (s12 >= 0 && room.wave < 29)
		{
			result.add(new Window("12 east scythe", s12 + 7, s12 + 9, Weapon.SCYTHE, 3301, 4249));
			int due = scythe12 < 0 ? s12 + 12 : scythe12 + 5;
			result.add(new Window("12 west Dinh", due, due + 1, Weapon.DINH, 3293, 4249));
		}
		if (s21 >= 0 && room.wave < 29)
		{
			result.add(new Window("21 west barrage", s21, s21 + 1, Weapon.BARRAGE, 3290, 4249));
			int due = barrage21 < 0 ? s21 + 5 : barrage21 + 5;
			result.add(new Window("21 north sulphur", due, due + 1, Weapon.SULPHUR, 3291, 4251));
		}
		if (s22 >= 0 && room.wave < 29) result.add(new Window("22 south Dinh", s22 + 9, s22 + 19, Weapon.DINH, 3295, 4244));
		if (s28 >= 0 && room.wave < 29) result.add(new Window("26 Dinh on 28", s28 + 8, s28 + 10, Weapon.DINH, 3293, 4245));
		result.removeIf(w -> completed.contains(w.name) || tick > w.last || w.weapon == Weapon.DINH && room.specialEnergy < 500);
		result.sort(Comparator.comparingInt(w -> w.due));
		return result;
	}

	private Crab packetTarget(Room room, Window window, int tick)
	{
		return room.crabs.stream().filter(c -> eligible(room, c, tick) && matchesNext(c, window.weapon.style, tick))
			.filter(c ->
			{
				switch (window.name)
				{
					case "12 east scythe": return c.wave == 12 && c.lane == Lane.EAST && !c.big;
					case "12 west Dinh": return c.wave == 12 && c.lane == Lane.WEST && c.slot == 'S' && !c.big;
					case "21 west barrage": return c.wave == 21 && c.lane == Lane.WEST && !c.big;
					case "21 north sulphur": return !c.big && c.point.getY() >= 4250;
					case "22 south Dinh":
						// Prefer the south-west small in its entry window; only the EAST big fights players.
						return c.wave == 22 && (c.lane == Lane.SOUTH && c.slot == 'W' && !c.big && tick - c.born <= 10
							|| c.lane == Lane.EAST && c.big && c.fighting && c.distance(room.player) <= 1);
					case "26 Dinh on 28": return c.wave == 26 && c.lane == Lane.SOUTH && c.big && lateGeometry(room, c, tick);
					default: return false;
				}
			})
			.min(Comparator.comparingInt((Crab c) -> window.name.equals("12 east scythe") ? (c.slot == 'S' ? 0 : 100)
				: window.name.equals("22 south Dinh") ? (c.big ? 100 : 0) : 0)
				.thenComparingInt(c -> c.distance(room.player)).thenComparingInt(c -> -c.born).thenComparingInt(c -> c.key)).orElse(null);
	}

	private static boolean lateGeometry(Room room, Crab big, int tick)
	{
		// Match the recorded doorway timing: next tick west is inside while south
		// remains in its lane. This is not a complete AoE victim prediction.
		if (big.point.getX() > 3294 || big.point.getY() > 4245 || big.distance(room.player) > 1) return false;
		boolean west = false;
		for (Crab c : room.crabs)
		{
			if (c.wave != 28 || c.big || c.dying) continue;
			if (c.lane == Lane.WEST && c.point.getX() >= 3289) west = true;
			if (c.lane == Lane.SOUTH && c.point.getY() >= 4242) return false;
		}
		return west;
	}

	public Decision decide(Room room, int tick)
	{
		if (room.wave == 0) return stage(room.shadowOpening ? Weapon.SHADOW : Weapon.EYE, tile(3296, 4244), "Opening south setup");
		if (room.wave == 1 && room.spawns[1] >= 0 && tick < room.spawns[1] + 1)
			return stage(room.shadowOpening ? Weapon.SHADOW : Weapon.EYE, tile(3296, 4244), "Opening south setup");
		List<Window> windows = windows(room, tick);
		Window next = windows.isEmpty() ? null : windows.get(0);
		if (tick >= room.ready)
		{
			for (Window w : windows)
			{
				if (tick < w.due) continue;
				if (w.weapon == Weapon.DINH && room.specialEnergy < 500) continue;
				Crab target = packetTarget(room, w, tick);
				if (target != null)
				{
					intent.clear();
					return new Decision(target, w.weapon, w.stage, w.weapon == Weapon.DINH, w.name, w.name);
				}
			}
		}
		List<Candidate> candidates = new ArrayList<>();
		for (Crab c : room.crabs)
		{
			if (!eligible(room, c, tick) || protectedTarget(room, c, tick)) continue;
			int age = tick - c.born;
			for (Assignment a : ASSIGNMENTS)
			{
				if (!a.matches(c) || age > a.last) continue;
				Weapon weapon = a.weapon == Weapon.SHADOW && !room.shadowOpening ? Weapon.EYE : a.weapon;
				// One cast covers a lane group; surviving magic is reconsidered with Ayak.
				if (weapon == Weapon.BARRAGE && completed.contains(a.name)) weapon = Weapon.EYE;
				if (!matchesNext(c, weapon.style, tick)) continue;
				int score = 12000 - age * 65 + (opened.contains(c.key) && c.big ? 500 : 0);
				if (a.name.equals("South opener 20")) score += 5000;
				Decision d = new Decision(c, weapon, a.stage, false, a.name, a.name);
				add(candidates, room, next, d, score, tick, c.born + a.first, c.born + a.last);
			}
			Weapon weapon = c.style == Style.MAGIC ? Weapon.EYE : c.style == Style.MELEE ? Weapon.SULPHUR : Weapon.DARTS;
			if (!matchesNext(c, weapon.style, tick)) continue;
			if (c.big && !finishBig(c, room, tick) || c.style == Style.RANGE) continue;
			// Lane assignments own their opening offset and weapon. Generic cleanup
			// must not bypass the rotation while that assignment is still pending.
			if (ASSIGNMENTS.stream().anyMatch(a -> a.matches(c) && !completed.contains(a.name) && age <= a.last)) continue;
			int score = (c.style == Style.MAGIC ? 6500 : c.style == Style.MELEE ? 5000 : 500) - age * 100;
			if (c.big) score = 10000 - age * 70;
			if (c.style == Style.MAGIC && !c.big && room.wave >= 20 && room.wave <= 22 && c.point.getY() <= 4247) score += 5000;
			if (c.style == Style.MELEE && c.big) weapon = Weapon.SCYTHE;
			if (!c.big && c.style == Style.MELEE && cluster(room, c, tick) >= 2) { weapon = Weapon.SCYTHE; score += 2200; }
			if (!c.big) score += Math.max(0, 8 - c.hp) * 40;
			Decision d = new Decision(c, weapon, c.point, false, null, "Fresh " + c.style + (c.big ? " assigned big" : " small"));
			add(candidates, room, next, d, score, tick, tick, c.born + 48);
		}
		Comparator<Candidate> priority = Comparator.comparingInt((Candidate c) -> deferred(c, tick) ? 0 : 1)
			.thenComparingInt(c -> -c.decision.target.wave)
			.thenComparingInt(c -> c.decision.rule == null ? 1 : 0)
			.thenComparingInt(c -> c.first)
			.thenComparingInt(c -> c.at).thenComparingInt(c -> -c.score)
			.thenComparingInt(c -> c.decision.target.key);
		Candidate best = candidates.stream().min(priority).orElse(null);
		if (best != null)
		{
			Candidate selected = best;
			// Commit within the same rotation step; a newer birth wave or a due
			// packet can replace an older approach.
			best = candidates.stream().filter(c -> c.at <= selected.at
				&& c.decision.target.wave == selected.decision.target.wave
				&& (c.decision.rule == null) == (selected.decision.rule == null)
				&& c.first == selected.first && deferred(c, tick) == deferred(selected, tick)
				&& intent.matches(c.decision.target.key, c.decision.weapon, tick))
				.min(priority).orElse(best);
			Decision action = best.decision;
			action.pathToTarget = best.at > tick && tick >= best.first && tick >= room.ready && !room.obstructed.contains(action.target.key);
			intent.select(action.target.key, action.weapon, tick);
			if (tick < best.first) return stage(action.weapon, action.tile, "Prepare " + action.reason);
			if (best.at > tick && !action.pathToTarget)
			{
				Decision d = stage(action.weapon, best.position, "Plan " + action.weapon + " #" + action.target.index
					+ " @" + best.at + ": " + action.reason);
				d.approach = action.target;
				return d;
			}
			return action;
		}
		intent.clear();
		if (next != null) return stage(next.weapon, next.stage, "Reserve " + next.name);
		Assignment upcoming = ASSIGNMENTS.stream().filter(a -> a.wave == room.wave + 1)
			.min(Comparator.comparingInt(a -> a.first)).orElse(null);
		if (upcoming != null) return stage(upcoming.weapon, upcoming.stage, "Prepare " + upcoming.name);
		return stage(Weapon.EYE, room.wave >= 29 ? tile(3297, 4249) : tile(3295, 4248),
			room.wave == 31 ? "Clear survivors except wave 29" : "Clear complete; stage");
	}

	private static final class Candidate
	{
		final Decision decision;
		final int score, first, at;
		final Tile position;
		Candidate(Decision decision, int score, int first, int at, Tile position)
		{
			this.decision = decision; this.score = score; this.first = first; this.at = at; this.position = position;
		}
	}

	private static boolean deferred(Candidate candidate, int tick)
	{
		return "Deferred east big 13".equals(candidate.decision.rule) && tick >= candidate.first;
	}

	private void add(List<Candidate> candidates, Room room, Window next, Decision d, int score, int tick, int first, int last)
	{
		Crab c = d.target;
		for (int at = Math.max(first, Math.max(tick, room.ready)); at <= Math.min(tick + 12, Math.min(last, c.born + 48)); at++)
		{
			int age = tick - c.born;
			int model = NylocasFlickers.at(c.wave, c.lane.ordinal(), c.slot, c.style.ordinal(), age);
			int color = model != c.style.ordinal() ? c.style.ordinal()
				: NylocasFlickers.at(c.wave, c.lane.ordinal(), c.slot, c.style.ordinal(), at + 1 - c.born);
			if (color != d.weapon.style.ordinal()) continue;
			Tile target = AttackTiming.position(c.point, c.lane.ordinal(), age, at + 1 - tick);
			Tile position = AttackTiming.attackTile(room.player, target, c.big, d.weapon.range);
			int travel = (room.player.distanceTo(position) + 1) / 2;
			if (room.obstructed.contains(c.key)) travel = Math.max(1, travel);
			if (tick + travel > at || AttackTiming.distance(position, target, c.big) > d.weapon.range) continue;
			// Travel during cooldown overlaps it, both on approach and on return to a packet.
			if (next != null && !(next.name.equals("22 south Dinh") && tick > next.due + 1)
				&& at + Math.max(d.weapon.cooldown, (position.distanceTo(next.stage) + 1) / 2) > next.due) return;
			candidates.add(new Candidate(d, score, first, at, position));
			return;
		}
	}

	private boolean protectedTarget(Room room, Crab c, int tick)
	{
		if (c.wave == 26 && c.lane == Lane.SOUTH && c.big && room.wave < 29) return true;
		if (c.wave == 12 && !c.big && (c.lane == Lane.EAST && !completed.contains("12 east scythe")
			|| c.lane == Lane.WEST && c.slot == 'S' && !completed.contains("12 west Dinh"))) return true;
		if (c.wave == 21 && c.lane == Lane.WEST && !c.big && !completed.contains("21 west barrage") && tick - c.born < 3) return true;
		if (c.wave == 22 && c.lane == Lane.SOUTH && c.slot == 'W' && !c.big && !completed.contains("22 south Dinh") && tick - c.born <= 10) return true;
		return false;
	}

	private static boolean rangerMagic(Crab c)
	{
		return c.lane != Lane.SPLIT && (c.wave == 16 || c.wave == 9 && c.lane == Lane.EAST
			|| c.wave == 11 && c.lane == Lane.SOUTH && c.slot == 'E'
			|| c.wave == 13 && c.lane == Lane.WEST || c.wave == 14 && c.lane == Lane.SOUTH
			|| c.wave == 15 && c.lane == Lane.EAST);
	}

	private static boolean rangerOwned(Crab c)
	{
		if (c.big || c.lane == Lane.SPLIT) return false;
		return c.wave == 16 || c.wave == 2 && c.lane == Lane.SOUTH && c.slot == 'W'
			|| c.style == Style.MAGIC && rangerMagic(c)
			|| c.style == Style.RANGE && !(c.wave == 15 && c.lane == Lane.WEST && c.slot == 'S');
	}

	private boolean finishBig(Crab c, Room room, int tick)
	{
		int age = tick - c.born;
		if (opened.contains(c.key) && ASSIGNMENTS.stream().anyMatch(a -> a.big && a.matches(c))
			&& (room.wave < 21 || c.wave >= 20 || age < 26)) return true;
		// Post-Dinh recovery: the east wave-22 fighting big, or west melee big,
		// and a damaged south wave-23 survivor. Never open generic magic bigs.
		return room.wave >= 23 && room.wave <= 24 && c.wave == 22 && c.style == Style.MELEE
			|| c.wave == 23 && c.lane == Lane.SOUTH && c.style == Style.MELEE && c.hp < 16;
	}

	private static int cluster(Room room, Crab target, int tick)
	{
		return (int) room.crabs.stream().filter(c -> !c.big && eligible(room, c, tick) && matchesNext(c, Style.MELEE, tick)
			&& c.distance(room.player) <= 1 && c.point.distanceTo(target.point) <= 1).count();
	}

	private static Decision stage(Weapon weapon, Tile tile, String reason) { return new Decision(null, weapon, tile, false, null, reason); }
	public static Tile tile(int x, int y) { return new Tile(x, y, 0); }
}

package org.localscape.nylocas.ranger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.localscape.nylocas.Tile;
import org.localscape.nylocas.AttackIntent;
import org.localscape.nylocas.AttackTiming;

/** Client-independent decision policy. Times are request ticks, one before Blert attack observations. */
public final class RangerStrategy
{
	public enum Style { MELEE, RANGE, MAGIC }
	public enum Lane { WEST, SOUTH, EAST, SPLIT }
	public enum Weapon
	{
		BOW(20997, Style.RANGE, 5, 10, 1),
		PIPE(12926, Style.RANGE, 2, 5, 1),
		CHIN(11959, Style.RANGE, 3, 9, 1),
		EYE(31113, Style.MAGIC, 3, 8, 3),
		CLAWS(13652, Style.MELEE, 4, 1, 1);

		public final int itemId;
		public final Style style;
		public final int cooldown;
		public final int range;
		public final int stance;

		Weapon(int itemId, Style style, int cooldown, int range, int stance)
		{
			this.itemId = itemId;
			this.style = style;
			this.cooldown = cooldown;
			this.range = range;
			this.stance = stance;
		}
	}

	public static final class Crab
	{
		public final int key;
		public final int index;
		public final int born;
		public final int wave;
		public final Lane lane;
		public final char slot;
		public final boolean big;
		public Tile point;
		public Style style;
		public boolean dying;
		public int claimedUntil;
		public int ownHitUntil;
		public int ownHitHp;
		public int hp;

		public Crab(int key, int index, int born, int wave, Lane lane, char slot, boolean big, Style style, Tile point)
		{
			this.key = key;
			this.index = index;
			this.born = born;
			this.wave = wave;
			this.lane = lane;
			this.slot = slot;
			this.big = big;
			this.style = style;
			this.point = point;
			this.hp = big ? 16 : 8;
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
		public final int[] spawns = new int[32];
		public int wave;
		public int ready;
		public int specialEnergy;
		public boolean cleanup;
		public Tile player;
		public final Set<Integer> obstructed = new HashSet<>();

		public Room() { Arrays.fill(spawns, -1); }
	}

	public static final class Decision
	{
		public final Crab target;
		public final Weapon weapon;
		public final Tile tile;
		public final boolean special;
		public final String rule;
		public final String reason;
		public Crab approach;
		public boolean pathToTarget;

		public Decision(Crab target, Weapon weapon, Tile tile, boolean special, String rule, String reason)
		{
			this.target = target;
			this.weapon = weapon;
			this.tile = tile;
			this.special = special;
			this.rule = rule;
			this.reason = reason;
		}
	}

	private static final Tile CENTER = tile(3295, 4248);
	// Delay after the given wave, independently of cap retries.
	private static final int[] DELAYS = {0, 4, 4, 4, 4, 16, 4, 12, 4, 12, 8, 8, 8, 8, 8, 8, 4,
		12, 8, 12, 16, 8, 12, 8, 8, 8, 4, 8, 4, 4, 4, 0};

	// Service windows are policy goals, not hard prefires or proof of a successful kill.
	private enum BigAssignment
	{
		WEST_5(5, Lane.WEST, 2, 8),
		EAST_6(6, Lane.EAST, 9, 15),
		SOUTH_7(7, Lane.SOUTH, 0, 6),
		WEST_9(9, Lane.WEST, 5, 13),
		EAST_14(14, Lane.EAST, 8, 16);

		final int wave;
		final Lane lane;
		final int first;
		final int due;

		BigAssignment(int wave, Lane lane, int first, int due)
		{
			this.wave = wave;
			this.lane = lane;
			this.first = first;
			this.due = due;
		}

		static BigAssignment of(Crab crab)
		{
			if (crab.big)
				for (BigAssignment assignment : values())
					if (assignment.wave == crab.wave && assignment.lane == crab.lane) return assignment;
			return null;
		}
	}

	public static final class Rule
	{
		public final String name;
		public final int wave;
		public final Lane lane;
		public final char slot;
		public final boolean big;
		public final Weapon weapon;
		public final int first;
		public final int last;
		public final int priority;
		public final Tile stage;
		public final boolean special;

		public Rule(String name, int wave, Lane lane, char slot, boolean big, Weapon weapon,
			int first, int last, int priority, int x, int y, boolean special)
		{
			this.name = name;
			this.wave = wave;
			this.lane = lane;
			this.slot = slot;
			this.big = big;
			this.weapon = weapon;
			this.first = first;
			this.last = last;
			this.priority = priority;
			this.stage = tile(x, y);
			this.special = special;
		}

		boolean matches(Crab crab)
		{
			return crab.wave == wave && crab.lane == lane && crab.big == big && (slot == 0 || crab.slot == slot);
		}
	}

	private static Rule rule(String name, int wave, Lane lane, char slot, boolean big, Weapon weapon,
		int first, int last, int priority, int x, int y)
	{
		return new Rule(name, wave, lane, slot, big, weapon, first, last, priority, x, y, false);
	}

	// Priority 0: reserved windows; 1: paired lane assignments; 2: conditional/deferred work.
	public static final List<Rule> RULES = Arrays.asList(
		rule("Opening west", 1, Lane.WEST, 'S', false, Weapon.BOW, 0, 2, 0, 3292, 4249),
		rule("Opening east", 2, Lane.EAST, 'S', false, Weapon.BOW, 2, 4, 0, 3298, 4249),
		rule("South chin 3", 3, Lane.SOUTH, 'E', false, Weapon.CHIN, 3, 5, 0, 3296, 4244),
		rule("Opening scratch", 2, Lane.SOUTH, 'W', false, Weapon.CLAWS, 10, 13, 0, 3295, 4244),
		rule("West big 5", 5, Lane.WEST, (char) 0, true, Weapon.PIPE, 2, 7, 0, 3290, 4248),
		rule("West small 4", 4, Lane.WEST, 'N', false, Weapon.PIPE, 8, 18, 1, 3293, 4248),
		rule("South chin 6", 6, Lane.SOUTH, 'W', false, Weapon.CHIN, 0, 5, 1, 3295, 4243),
		rule("South bow 7", 7, Lane.SOUTH, (char) 0, true, Weapon.BOW, 0, 4, 0, 3295, 4243),
		new Rule("East big claw special", 6, Lane.EAST, (char) 0, true, Weapon.CLAWS, 9, 15, 0, 3300, 4245, true),
		rule("East chin 8", 8, Lane.EAST, 'S', false, Weapon.CHIN, 0, 4, 1, 3301, 4248),
		rule("East magic 9", 9, Lane.EAST, 'N', false, Weapon.EYE, 0, 7, 2, 3299, 4248),
		rule("West big 9", 9, Lane.WEST, (char) 0, true, Weapon.PIPE, 5, 13, 0, 3293, 4248),
		rule("West chin 10", 10, Lane.WEST, (char) 0, false, Weapon.CHIN, 0, 4, 0, 3291, 4248),
		rule("East chin 10", 10, Lane.EAST, (char) 0, false, Weapon.CHIN, 3, 7, 0, 3297, 4248),
		rule("South chin 10", 10, Lane.SOUTH, (char) 0, false, Weapon.CHIN, 6, 11, 0, 3295, 4247),
		rule("South magic 11", 11, Lane.SOUTH, 'E', false, Weapon.EYE, 6, 20, 1, 3295, 4248),
		rule("West magic 13", 13, Lane.WEST, 'S', false, Weapon.EYE, 2, 9, 1, 3292, 4249),
		rule("West range 13", 13, Lane.WEST, 'N', false, Weapon.PIPE, 4, 15, 1, 3293, 4249),
		rule("South range 13", 13, Lane.SOUTH, 'E', false, Weapon.PIPE, 8, 20, 1, 3296, 4245),
		rule("South range 14", 14, Lane.SOUTH, 'W', false, Weapon.PIPE, 4, 17, 1, 3295, 4244),
		rule("South magic 14", 14, Lane.SOUTH, 'E', false, Weapon.EYE, 2, 16, 1, 3295, 4245),
		rule("East magic 15", 15, Lane.EAST, 'N', false, Weapon.EYE, 1, 13, 1, 3300, 4248),
		rule("East range 15", 15, Lane.EAST, 'S', false, Weapon.PIPE, 3, 14, 1, 3300, 4248),
		rule("West range 15", 15, Lane.WEST, 'S', false, Weapon.PIPE, 8, 24, 2, 3295, 4248),
		rule("Deferred east big 14", 14, Lane.EAST, (char) 0, true, Weapon.PIPE, 15, 25, 2, 3297, 4248),
		rule("East range flicker 16", 16, Lane.EAST, 'S', false, Weapon.PIPE, 6, 24, 1, 3298, 4248),
		rule("South range flicker 16", 16, Lane.SOUTH, 'W', false, Weapon.PIPE, 6, 24, 1, 3295, 4248),
		rule("West range flicker 16", 16, Lane.WEST, 'N', false, Weapon.PIPE, 6, 24, 1, 3293, 4248),
		rule("West big 18", 18, Lane.WEST, (char) 0, true, Weapon.PIPE, 7, 25, 2, 3293, 4248),
		rule("South big 18", 18, Lane.SOUTH, (char) 0, true, Weapon.PIPE, 11, 25, 2, 3293, 4248),
		rule("East big 18", 18, Lane.EAST, (char) 0, true, Weapon.PIPE, 13, 25, 2, 3293, 4248),
		rule("West magic big 19", 19, Lane.WEST, (char) 0, true, Weapon.EYE, 3, 20, 2, 3293, 4248),
		rule("West bow flicker 20", 20, Lane.WEST, (char) 0, true, Weapon.BOW, 5, 6, 0, 3293, 4248),
		rule("East chin 21", 21, Lane.EAST, (char) 0, false, Weapon.CHIN, 0, 2, 0, 3301, 4248),
		rule("South bow 23", 23, Lane.SOUTH, (char) 0, true, Weapon.BOW, 0, 3, 0, 3295, 4244),
		rule("West north flicker 23", 23, Lane.WEST, 'N', false, Weapon.PIPE, 5, 6, 0, 3291, 4248),
		rule("West south flicker 23", 23, Lane.WEST, 'S', false, Weapon.PIPE, 7, 10, 0, 3291, 4248),
		rule("West big 24", 24, Lane.WEST, (char) 0, true, Weapon.PIPE, 3, 4, 0, 3291, 4248),
		rule("West big magic fallback 24", 24, Lane.WEST, (char) 0, true, Weapon.EYE, 5, 6, 1, 3291, 4248),
		rule("South big 25", 25, Lane.SOUTH, (char) 0, true, Weapon.PIPE, 5, 25, 2, 3295, 4247),
		rule("South big flicker 27", 27, Lane.SOUTH, (char) 0, true, Weapon.PIPE, 7, 12, 0, 3296, 4246),
		rule("South east flicker 28", 28, Lane.SOUTH, 'E', false, Weapon.PIPE, 5, 6, 0, 3296, 4244),
		rule("South west flicker 28", 28, Lane.SOUTH, 'W', false, Weapon.PIPE, 7, 9, 0, 3296, 4246),
		rule("West south early 29", 29, Lane.WEST, 'S', false, Weapon.PIPE, 3, 4, 0, 3291, 4248),
		rule("West north flicker 29", 29, Lane.WEST, 'N', false, Weapon.PIPE, 5, 6, 0, 3291, 4248),
		rule("West south late 29", 29, Lane.WEST, 'S', false, Weapon.PIPE, 7, 10, 1, 3291, 4248),
		rule("West big 30", 30, Lane.WEST, (char) 0, true, Weapon.PIPE, 3, 14, 0, 3291, 4248),
		rule("South chin flicker 30", 30, Lane.SOUTH, 'E', false, Weapon.CHIN, 5, 6, 0, 3293, 4248),
		rule("West south early 31", 31, Lane.WEST, 'S', false, Weapon.PIPE, 3, 4, 0, 3291, 4248),
		rule("West north flicker 31", 31, Lane.WEST, 'N', false, Weapon.PIPE, 5, 6, 0, 3291, 4248),
		rule("West south late 31", 31, Lane.WEST, 'S', false, Weapon.PIPE, 7, 10, 0, 3291, 4248),
		rule("South chin 31", 31, Lane.SOUTH, (char) 0, false, Weapon.CHIN, 7, 11, 0, 3295, 4248),
		rule("East range 31", 31, Lane.EAST, 'S', false, Weapon.PIPE, 10, 20, 1, 3295, 4248)
	);

	private final Set<String> completed = new HashSet<>();
	private final Set<Integer> opened = new HashSet<>();
	private final RangerPreCapPlanner preCap = new RangerPreCapPlanner(this);
	private final AttackIntent<Weapon> intent = new AttackIntent<>();

	public void reset()
	{
		completed.clear();
		opened.clear();
		intent.clear();
	}

	public void attacked(Decision decision, int tick)
	{
		intent.clear();
		if (decision.rule != null) completed.add(decision.rule);
		opened.add(decision.target.key);
		// Wait for a small's projectile/HP update before deciding whether another hit is useful.
		decision.target.ownHitUntil = tick + (decision.weapon == Weapon.CLAWS ? 1 : decision.weapon == Weapon.BOW ? 3 : 2);
		decision.target.ownHitHp = decision.target.hp;
	}

	public Decision decide(Room room, int tick)
	{
		Decision decision = choose(room, tick);
		Crab target = decision.target != null ? decision.target : decision.approach;
		if (target == null) intent.clear();
		else intent.select(target.key, decision.weapon, tick);
		return decision;
	}

	boolean committed(Decision decision, int tick) { return intent.matches(decision.target.key, decision.weapon, tick); }

	private Decision choose(Room room, int tick)
	{
		if (room.wave == 0) return prepare(Weapon.BOW, tile(3292, 4249), "Opening setup");
		if (room.wave == 31 && finalPacketFinished(room, tick)) room.cleanup = true;
		Reservation next = reservation(room, tick);
		if (room.wave < 20) return preCap.decide(room, tick, next);
		List<Candidate> candidates = new ArrayList<>();
		for (Crab crab : room.crabs)
		{
			int age = tick - crab.born;
			if (crab.dying || crab.claimedUntil > tick || crab.awaitingHit(tick)
				|| age >= 49 || crab.big && crab.wave < 20 && room.wave < 29 || room.wave == 31 && crab.wave == 29) continue;
			for (Rule rule : RULES)
			{
				if (completed.contains(rule.name) || !rule.matches(crab) || age < rule.first || age > rule.last
					|| !RangerFlickers.matchesNext(crab, rule.weapon.style, tick)) continue;
				// Optional/deferred packets do not outrank fresh ranged smalls.
				int score = (rule.priority == 0 ? 20000 : rule.priority == 1 ? 13000 : 7000)
					- (crab.born + rule.last - tick) * 25 - age;
				Decision action = new Decision(crab, rule.weapon, rule.stage,
					rule.special && room.specialEnergy >= 500, rule.name, rule.name);
				add(candidates, room, next, action, score, tick, crab.born + rule.last);
			}
			// A scheduled identity must not be killed prematurely as generic filler.
			if (protectedPacket(crab, tick)) continue;
			Weapon weapon = null;
			int score = 0;
			if (!crab.big && crab.style == Style.RANGE)
			{
				weapon = Weapon.PIPE;
				score = 9000 - age * 90 + (8 - Math.min(8, crab.hp)) * 8;
				long cluster = room.crabs.stream().filter(c -> !c.big && !c.dying && c.style == Style.RANGE
					&& c.claimedUntil <= tick && (!room.cleanup || c.wave >= 30)
					&& c.point.distanceTo(crab.point) <= 1).count();
				if (cluster >= 2 && age < 18) { weapon = Weapon.CHIN; score += (int) cluster * 180; }
			}
			else if (crab.big && assignedBig(crab, room, age))
			{
				weapon = crab.style == Style.RANGE ? Weapon.PIPE : crab.style == Style.MAGIC ? Weapon.EYE
					: crab.wave == 6 ? Weapon.CLAWS : null;
				score = (room.cleanup && crab.wave >= 30 ? 16000 : opened.contains(crab.key) && age < 14 ? 14000 : 6800) - age * 40;
			}
			else if (!crab.big && crab.style == Style.MAGIC && !magePacket(crab, tick))
			{
				weapon = Weapon.EYE;
				score = 5500 - age * 85;
			}
			else if (!crab.big && crab.style == Style.MELEE && crab.distance(room.player) <= 1)
			{
				weapon = Weapon.CLAWS;
				score = 1500 - age * 80;
			}
			if (weapon == null || !RangerFlickers.matchesNext(crab, weapon.style, tick)) continue;
			Decision filler = new Decision(crab, weapon, CENTER, false, null, "Recent " + crab.style + (crab.big ? " big" : " small"));
			add(candidates, room, next, filler, score, tick, crab.born + 48);
		}
		if (candidates.isEmpty() && room.wave >= 20 && room.wave < 29)
		{
			// Keep the lane assignments first; spare cooldown can finish an older big.
			for (Crab crab : room.crabs)
			{
				if (!crab.big || crab.wave >= 20 || crab.dying || crab.claimedUntil > tick
					|| crab.awaitingHit(tick) || tick - crab.born >= 49 || crab.style == Style.MELEE) continue;
				Weapon weapon = crab.style == Style.RANGE ? Weapon.PIPE : Weapon.EYE;
				if (!RangerFlickers.matchesNext(crab, weapon.style, tick)) continue;
				Decision action = new Decision(crab, weapon, CENTER, false, null, "Spare time on old big");
				add(candidates, room, next, action, 100, tick, crab.born + 48);
			}
		}
		Candidate best = candidates.stream().min(Comparator.comparingInt((Candidate c) -> c.at)
			.thenComparingInt(c -> -c.score).thenComparingInt(c -> c.decision.target.key)).orElse(null);
		if (best == null) return staging(room, next, "Reserve next packet");
		int earliest = best.at;
		best = candidates.stream().filter(c -> c.at == earliest && committed(c.decision, tick))
			.min(Comparator.comparingInt((Candidate c) -> -c.score)).orElse(best);
		if (best.at <= tick || tick >= room.ready && !room.obstructed.contains(best.decision.target.key))
		{
			best.decision.pathToTarget = best.at > tick;
			return best.decision;
		}
		Decision action = best.decision;
		Decision approach = prepare(action.weapon, best.position,
			"Plan " + action.weapon + " #" + action.target.index + " @" + best.at + ": " + action.reason);
		approach.approach = action.target;
		return approach;
	}

	private boolean protectedPacket(Crab crab, int tick)
	{
		return RULES.stream().anyMatch(r -> r.priority <= 1 && !completed.contains(r.name)
			&& r.matches(crab) && tick - crab.born <= r.last);
	}

	private boolean finalPacketFinished(Room room, int tick)
	{
		return RULES.stream().filter(r -> r.wave >= 30).allMatch(r -> completed.contains(r.name)
			|| tick > room.spawns[r.wave] + r.last || room.crabs.stream().noneMatch(c -> r.matches(c) && !c.dying));
	}

	boolean assignedBig(Crab c, Room room, int age)
	{
		if (room.wave >= 29 && c.style == Style.RANGE) return true;
		if (room.wave >= 20 && c.wave < 20) return false;
		BigAssignment assignment = BigAssignment.of(c);
		if (assignment != null) return age >= assignment.first && (c.wave != 19 || c.style == Style.MAGIC);
		if (c.wave == 18) return wave18Available(c, room, c.born + age);
		if (c.wave == 19) return c.lane == Lane.WEST && c.style == Style.MAGIC && enteredRoom(c);
		if (c.wave == 24 || c.wave == 30) return c.lane == Lane.WEST;
		if (c.wave == 25 || c.wave == 27) return c.lane == Lane.SOUTH;
		if (c.wave == 20) return c.lane == Lane.WEST && (opened.contains(c.key) || age >= 7);
		return c.wave == 23 && c.lane == Lane.SOUTH;
	}

	int bigServiceDeadline(Crab crab, Room room, int tick, Weapon weapon)
	{
		if (room.wave >= 20) return Integer.MAX_VALUE;
		if (crab.big && crab.wave == 18 && wave18Available(crab, room, tick)) return crab.born + 26;
		BigAssignment assignment = BigAssignment.of(crab);
		if (assignment == null || tick - crab.born < assignment.first || crab.claimedUntil > tick)
			return Integer.MAX_VALUE;
		int attacks = Math.max(1, (crab.hp + 7) / 8);
		int cooldown = weapon.style == Style.RANGE ? Weapon.PIPE.cooldown : weapon.cooldown;
		int deadline = crab.born + assignment.due;
		return opened.contains(crab.key) || tick + attacks * cooldown + 2 >= deadline
			? deadline : Integer.MAX_VALUE;
	}

	int bigCompletionDeadline(Crab crab)
	{
		if (crab.big && crab.wave == 18) return crab.born + 26;
		BigAssignment assignment = BigAssignment.of(crab);
		return assignment == null ? Integer.MAX_VALUE : crab.born + assignment.due;
	}

	static boolean enteredRoom(Crab crab)
	{
		// Use the observed southwest anchor, never a projected incoming step or model centre.
		return crab.point.getX() >= 3290 && crab.point.getX() <= 3301
			&& crab.point.getY() >= 4243 && crab.point.getY() <= 4254;
	}

	static boolean wave18Released(Room room, int tick)
	{
		// Actual wave-19 birth includes stalls. Its age-five melee flicker is the timing guide.
		return room.wave == 19 && room.spawns[19] >= 0 && tick >= room.spawns[19] + 5;
	}

	static boolean wave18Available(Crab crab, Room room, int tick)
	{
		if (room.wave >= 20 || !enteredRoom(crab) || tick - crab.born >= 26) return false;
		return wave18Released(room, tick) || crab.lane == Lane.WEST && room.crabs.stream().noneMatch(c -> !c.big);
	}

	static boolean assignedMagic(Crab c)
	{
		return c.wave == 5 && c.lane == Lane.EAST || c.wave == 9 && c.lane == Lane.EAST
			|| c.wave == 11 && c.lane == Lane.SOUTH && c.slot == 'E'
			|| c.wave == 13 && c.lane == Lane.WEST || c.wave == 14 && c.lane == Lane.SOUTH
			|| c.wave == 15 && c.lane == Lane.EAST;
	}

	static boolean magePacket(Crab c, int tick)
	{
		return !c.big && (c.wave == 11 && c.lane == Lane.EAST && tick - c.born <= 2
			|| c.wave == 21 && c.lane == Lane.WEST && tick - c.born <= 2);
	}

	public static final class Reservation
	{
		final Rule rule;
		final int due;
		Reservation(Rule rule, int due) { this.rule = rule; this.due = due; }
	}

	private Reservation reservation(Room room, int tick)
	{
		List<Reservation> reservations = new ArrayList<>();
		for (Rule rule : RULES)
		{
			if (room.wave == 31 && rule.wave == 29) continue;
			if (rule.priority != 0 || completed.contains(rule.name)
				|| room.wave < 20 && rule.wave != 10 && rule.wave != 20) continue;
			if (rule.wave <= room.wave)
			{
				Crab crab = room.crabs.stream().filter(c -> rule.matches(c) && !c.dying).findFirst().orElse(null);
				if (crab == null || tick > crab.born + rule.last) continue;
				reservations.add(new Reservation(rule, Math.max(tick, crab.born + rule.first)));
			}
			else if (rule.wave == room.wave + 1 && room.wave < 31)
			{
				int spawn = room.spawns[room.wave] + DELAYS[room.wave];
				if (spawn <= tick) spawn += ((tick - spawn) / 4 + 1) * 4;
				reservations.add(new Reservation(rule, spawn + rule.first));
			}
		}
		return reservations.stream().min(Comparator.comparingInt(r -> r.due)).orElse(null);
	}

	private static final class Candidate
	{
		final Decision decision;
		final int score, at;
		final Tile position;
		Candidate(Decision decision, int score, int at, Tile position)
		{
			this.decision = decision; this.score = score; this.at = at; this.position = position;
		}
	}

	private static void add(List<Candidate> candidates, Room room, Reservation next, Decision action, int score, int tick, int last)
	{
		Crab crab = action.target;
		for (int at = Math.max(tick, room.ready); at <= Math.min(tick + 12, Math.min(last, crab.born + 48)); at++)
		{
			Style color = RangerFlickers.at(crab, tick - crab.born) != crab.style ? crab.style : RangerFlickers.at(crab, at + 1 - crab.born);
			if (color != action.weapon.style) continue;
			Tile target = AttackTiming.position(crab.point, crab.lane.ordinal(), tick - crab.born, at + 1 - tick);
			Tile position = AttackTiming.attackTile(room.player, target, crab.big, action.weapon.range);
			int travel = (room.player.distanceTo(position) + 1) / 2;
			if (room.obstructed.contains(crab.key)) travel = Math.max(1, travel);
			if (tick + travel > at || AttackTiming.distance(position, target, crab.big) > action.weapon.range) continue;
			if (next != null && !next.rule.name.equals(action.rule)
				&& at + Math.max(action.weapon.cooldown, (position.distanceTo(next.rule.stage) + 1) / 2) > next.due) return;
			candidates.add(new Candidate(action, score, at, position));
			return;
		}
	}

	static Decision staging(Room room, Reservation next, String reason)
	{
		if (next != null) return prepare(next.rule.weapon, next.rule.stage, reason + ": " + next.rule.name);
		if (room.wave < 20)
		{
			Rule upcoming = RULES.stream().filter(r -> r.wave == room.wave + 1)
				.min(Comparator.comparingInt(r -> r.first)).orElse(null);
			if (upcoming != null) return prepare(upcoming.weapon, upcoming.stage, reason + ": prepare " + upcoming.name);
		}
		Tile stage = room.wave >= 18 && room.wave <= 20 || room.wave >= 29 ? tile(3293, 4248) : CENTER;
		return prepare(Weapon.PIPE, stage, reason);
	}

	private static Decision prepare(Weapon weapon, Tile tile, String reason)
	{
		return new Decision(null, weapon, tile, false, null, reason);
	}

	boolean completed(Rule rule) { return completed.contains(rule.name); }
	boolean opened(Crab crab) { return opened.contains(crab.key); }

	public static Tile tile(int x, int y) { return new Tile(x, y, 0); }
}

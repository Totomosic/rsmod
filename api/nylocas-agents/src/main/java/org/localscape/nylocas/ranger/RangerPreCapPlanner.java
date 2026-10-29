package org.localscape.nylocas.ranger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.localscape.nylocas.Tile;
import org.localscape.nylocas.ranger.RangerStrategy.Crab;
import org.localscape.nylocas.ranger.RangerStrategy.Decision;
import org.localscape.nylocas.ranger.RangerStrategy.Lane;
import org.localscape.nylocas.ranger.RangerStrategy.Reservation;
import org.localscape.nylocas.ranger.RangerStrategy.Room;
import org.localscape.nylocas.ranger.RangerStrategy.Rule;
import org.localscape.nylocas.ranger.RangerStrategy.Style;
import org.localscape.nylocas.ranger.RangerStrategy.Weapon;

/** A short, receding plan: spend ready ticks on kills and use cooldown to approach the next hit. */
final class RangerPreCapPlanner
{
	private static final int HORIZON = 12;
	private static final int WIDTH = 6;
	private static final int SHOTS = 3;
	private final RangerStrategy strategy;

	RangerPreCapPlanner(RangerStrategy strategy) { this.strategy = strategy; }

	private static final class Shot
	{
		final Decision decision;
		final int tick;
		final Tile position;

		Shot(Decision decision, int tick, Tile position)
		{
			this.decision = decision;
			this.tick = tick;
			this.position = position;
		}
	}

	private static final class Plan
	{
		final List<Shot> shots = new ArrayList<>();
		final int[] hp;
		double value;

		Plan(int[] hp) { this.hp = hp; }
	}

	Decision decide(Room room, int tick, Reservation reservation)
	{
		int[] hp = room.crabs.stream().mapToInt(c -> c.hp).toArray();
		List<Plan> beam = new ArrayList<>();
		beam.add(new Plan(hp));
		Plan best = null;
		for (int depth = 0; depth < SHOTS; depth++)
		{
			List<Plan> choices = new ArrayList<>();
			for (Plan plan : beam)
			{
				List<Plan> extensions = extend(room, tick, reservation, plan);
				if (!extensions.isEmpty())
				{
					Shot previous = plan.shots.isEmpty() ? null : plan.shots.get(plan.shots.size() - 1);
					prioritize(extensions, room, previous == null ? tick : previous.tick + 1,
						previous == null ? room.ready : previous.tick + previous.decision.weapon.cooldown);
					if (previous == null && extensions.stream().anyMatch(p -> strategy.committed(last(p), tick)))
						extensions.removeIf(p -> !strategy.committed(last(p), tick));
				}
				// A short plan that clears the work must not lose to a longer, less useful one.
				if (extensions.isEmpty() && !plan.shots.isEmpty()) choices.add(plan);
				else choices.addAll(extensions);
			}
			if (choices.isEmpty()) break;
			choices.sort(Comparator.comparingDouble((Plan p) -> p.value).reversed());
			beam = new ArrayList<>(choices.subList(0, Math.min(WIDTH, choices.size())));
			best = beam.get(0);
		}
		if (best == null) return RangerStrategy.staging(room, reservation, "No useful pre-cap attack");
		Shot first = best.shots.get(0);
		String sequence = best.shots.stream().map(s -> s.decision.weapon + " #" + s.decision.target.index
			+ " @" + s.tick).collect(Collectors.joining(" → "));
		Decision action = first.decision;
		boolean hard = action.rule != null && action.target.wave == 10;
		boolean queue = tick >= room.ready && !action.target.awaitingHit(tick) && action.target.claimedUntil <= tick
			&& !room.obstructed.contains(action.target.key)
			&& RangerFlickers.matchesNext(action.target, action.weapon.style, tick)
			&& allowed(room, action.target, action.weapon, tick - action.target.born)
			&& (action.weapon != Weapon.CHIN || hard)
			&& (!hard || rule(action.target, action.weapon, tick - action.target.born) != null);
		Decision decision = new Decision(first.tick <= tick || queue ? action.target : null, action.weapon,
			first.position, action.special, action.rule, "Pre-cap plan: " + sequence);
		decision.pathToTarget = first.tick > tick && queue;
		if (decision.target == null) decision.approach = action.target;
		return decision;
	}

	private static void keepEarliest(List<Plan> choices)
	{
		int earliest = choices.stream().mapToInt(p -> p.shots.get(p.shots.size() - 1).tick).min().getAsInt();
		choices.removeIf(p -> p.shots.get(p.shots.size() - 1).tick != earliest);
	}

	private void prioritize(List<Plan> choices, Room room, int tick, int ready)
	{
		// Keep attacking while ready; use cooldown to reach a big that needs service.
		if (tick >= ready) keepEarliest(choices);
		int serviceTick = Math.max(tick, ready);
		if (RangerStrategy.wave18Released(room, serviceTick) && choices.stream()
			.anyMatch(p -> last(p).target.big && last(p).target.wave == 18))
			choices.removeIf(p -> !last(p).target.big || last(p).target.wave != 18);
		int deadline = choices.stream().mapToInt(p -> serviceDeadline(p, room, serviceTick)).min().getAsInt();
		if (deadline != Integer.MAX_VALUE) choices.removeIf(p -> serviceDeadline(p, room, serviceTick) != deadline);
		if (tick < ready) keepEarliest(choices);
		// This shared mage big is fallback even after an observed hit or a simulated follow-up.
		if (choices.stream().anyMatch(p -> !west19(last(p).target))) choices.removeIf(p -> west19(last(p).target));
	}

	private static Decision last(Plan plan) { return plan.shots.get(plan.shots.size() - 1).decision; }
	private static boolean west19(Crab crab) { return crab.big && crab.wave == 19 && crab.lane == Lane.WEST; }

	private int serviceDeadline(Plan plan, Room room, int tick)
	{
		int last = plan.shots.size() - 1;
		Decision candidate = plan.shots.get(last).decision;
		if (candidate.target.claimedUntil <= tick && plan.shots.subList(0, last).stream()
			.anyMatch(s -> s.decision.target == candidate.target))
			return strategy.bigCompletionDeadline(candidate.target);
		return strategy.bigServiceDeadline(candidate.target, room, tick, candidate.weapon);
	}

	private List<Plan> extend(Room room, int now, Reservation reservation, Plan plan)
	{
		List<Plan> choices = new ArrayList<>();
		Shot previous = plan.shots.isEmpty() ? null : plan.shots.get(plan.shots.size() - 1);
		int moveFrom = previous == null ? now : previous.tick + 1;
		int ready = previous == null ? Math.max(now, room.ready) : previous.tick + previous.decision.weapon.cooldown;
		Tile from = previous == null ? room.player : previous.position;
		for (int i = 0; i < room.crabs.size(); i++)
		{
			Crab crab = room.crabs.get(i);
			if (crab.dying || plan.hp[i] <= 0) continue;
			for (Weapon weapon : Weapon.values())
			{
				for (int at = ready; at <= now + HORIZON; at++)
				{
					int age = at - crab.born;
					if (age < 0 || age >= 49 || crab.claimedUntil > at || crab.awaitingHit(at)) continue;
					Style style = predictedStyle(crab, now, at);
					if (style != weapon.style || predictedStyle(crab, now, at + 1) != style
						|| !allowed(room, crab, weapon, age)) continue;
					Tile target = position(crab, now, at + 1);
					Tile destination = attackTile(from, target, crab.big, weapon.range);
					if (distance(destination, target, crab.big) > weapon.range) continue;
					int travel = (from.distanceTo(destination) + 1) / 2;
					if (moveFrom + travel > at || previous == null && at == now && room.obstructed.contains(crab.key)) continue;
					Rule rule = rule(crab, weapon, age);
					boolean hard = rule != null && rule.wave == 10;
					// Wave-10 primaries/pairs are the one protected early packet.
					if (crab.wave == 10 && crab.lane != Lane.SPLIT && !hard
						&& RangerStrategy.RULES.stream().anyMatch(r -> r.wave == 10 && r.matches(crab)
							&& !strategy.completed(r) && age <= r.last)) continue;
					if (weapon == Weapon.CHIN && !hard && victims(room, plan, crab, weapon, now, at).size() < 2) continue;
					Decision action = new Decision(crab, weapon, destination,
						weapon == Weapon.CLAWS && crab.big && room.specialEnergy >= 500,
						rule == null ? null : rule.name, "Pre-cap clearing");
					if (reservation != null && !reservation.rule.name.equals(action.rule)
						&& at + Math.max(weapon.cooldown, (destination.distanceTo(reservation.rule.stage) + 1) / 2) > reservation.due) continue;
					Plan next = new Plan(plan.hp.clone());
					next.shots.addAll(plan.shots);
					next.shots.add(new Shot(action, at, destination));
					double gain = hard ? 10000 : 0;
					for (int victim : victims(room, plan, crab, weapon, now, at))
					{
						Crab c = room.crabs.get(victim);
						int remaining = plan.hp[victim];
						// Approximate work estimates, not damage rolls or gear max hits.
						int work = weapon == Weapon.BOW || action.special ? 12 : 8;
						// Credit a crab's HP only once across the simulated sequence.
						double progress = (double) Math.min(work, remaining) / Math.max(1, c.hp);
						double value = (c.big ? 500 : 1500) * progress;
						if (style == Style.RANGE) value *= 1.2;
						if (style == Style.MELEE && !c.big) value *= 0.4;
						if (c.hp < (c.big ? 16 : 8)) value *= 1.25 + (c.big ? 16 - c.hp : 8 - c.hp) * 0.03;
						if (strategy.opened(c)) value *= 1.15;
						value *= 1 - Math.max(0, at - c.born) * 0.003;
						if (at - c.born >= 44) value *= 0.25;
						// Big deaths produce two children; they are not immediate cap relief.
						if (c.big && room.crabs.size() >= 10) value *= 0.5;
						gain += value;
						next.hp[victim] = Math.max(0, remaining - work);
					}
					// Equivalent splashes still use the better finish/younger primary target.
					gain += Math.max(0, (crab.big ? 16 : 8) - crab.hp) + (49 - age) * 0.01;
					next.value = plan.value + (gain - weapon.cooldown * 15) * Math.pow(0.8, (at - now) / 2.0);
					choices.add(next);
					break;
				}
			}
		}
		return choices;
	}

	private boolean allowed(Room room, Crab crab, Weapon weapon, int age)
	{
		if (crab.big)
		{
			if (crab.wave == 18) return weapon.style == Style.RANGE && weapon != Weapon.CHIN
				&& RangerStrategy.wave18Available(crab, room, crab.born + age);
			boolean clawAssignment = crab.wave == 6 && crab.lane == Lane.EAST && age >= 9;
			return (clawAssignment || strategy.assignedBig(crab, room, age))
				&& (weapon != Weapon.CLAWS || clawAssignment) && weapon != Weapon.CHIN;
		}
		if (weapon == Weapon.EYE) return !RangerStrategy.magePacket(crab, crab.born + age);
		// No cross-room melee chasing; melee is local fallback work.
		if (weapon == Weapon.CLAWS) return crab.distance(room.player) <= 1;
		return true;
	}

	private Rule rule(Crab crab, Weapon weapon, int age)
	{
		return RangerStrategy.RULES.stream().filter(r -> !strategy.completed(r) && r.matches(crab)
			&& r.weapon == weapon && age >= r.first && age <= r.last).findFirst().orElse(null);
	}

	private static Style predictedStyle(Crab crab, int now, int at)
	{
		// A differing live color overrides the fixture until the next observation/replan.
		return RangerFlickers.at(crab, now - crab.born) != crab.style ? crab.style : RangerFlickers.at(crab, at - crab.born);
	}

	private static Tile position(Crab crab, int now, int at)
	{
		if (crab.lane == Lane.SPLIT) return crab.point;
		int steps = Math.max(0, Math.min(at - now, 9 - (now - crab.born)));
		return crab.lane == Lane.WEST ? crab.point.dx(steps) : crab.lane == Lane.EAST ? crab.point.dx(-steps) : crab.point.dy(steps);
	}

	private static Tile attackTile(Tile from, Tile target, boolean big, int range)
	{
		int size = big ? 2 : 1;
		int x = Math.max(target.getX() - range, Math.min(from.getX(), target.getX() + size - 1 + range));
		int y = Math.max(target.getY() - range, Math.min(from.getY(), target.getY() + size - 1 + range));
		return RangerStrategy.tile(Math.max(3290, Math.min(3301, x)), Math.max(4243, Math.min(4254, y)));
	}

	private static int distance(Tile from, Tile target, boolean big)
	{
		int size = big ? 2 : 1;
		return Math.max(Math.max(target.getX() - from.getX(), from.getX() - target.getX() - size + 1),
			Math.max(target.getY() - from.getY(), from.getY() - target.getY() - size + 1));
	}

	private static List<Integer> victims(Room room, Plan plan, Crab primary, Weapon weapon, int now, int at)
	{
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < room.crabs.size(); i++)
		{
			Crab c = room.crabs.get(i);
			if (c == primary || weapon == Weapon.CHIN && !c.big && !c.dying && plan.hp[i] > 0
				&& c.claimedUntil <= at && c.ownHitUntil <= at + 1 && at - c.born < 49
				&& predictedStyle(c, now, at) == Style.RANGE && predictedStyle(c, now, at + 1) == Style.RANGE
				&& position(c, now, at).distanceTo(position(primary, now, at)) <= 1) result.add(i);
		}
		return result;
	}
}

package org.rsmod.api.combat.commons

import org.rsmod.api.config.refs.npcs
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity

/**
 * Rolls damage uniformly from `0..maxHit`, then raises a zero roll to `1`.
 *
 * Accuracy checks and any zero-damage restrictions must be handled by the caller.
 */
public fun GameRandom.rollDamage(maxHit: Int): Int = of(0..maxHit).coerceAtLeast(1)

/** Combat dummies always receive the upper bound of an attack's damage roll. */
public fun PathingEntity.isCombatDummy(): Boolean =
    this is Npc && visType.id == npcs.combat_dummy.id

public fun GameRandom.rollDamage(maxHit: Int, target: PathingEntity): Int =
    if (target.isCombatDummy()) maxHit.coerceAtLeast(1) else rollDamage(maxHit)

/** Preserves spell and special-attack ranges, including their zero-damage rules. */
public fun GameRandom.rollDamage(range: IntRange, target: PathingEntity): Int =
    if (target.isCombatDummy()) range.last else of(range)

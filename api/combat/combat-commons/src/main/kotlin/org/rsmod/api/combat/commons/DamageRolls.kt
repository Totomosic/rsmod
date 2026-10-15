package org.rsmod.api.combat.commons

import org.rsmod.api.random.GameRandom

/**
 * Rolls damage uniformly from `0..maxHit`, then raises a zero roll to `1`.
 *
 * Accuracy checks and any zero-damage restrictions must be handled by the caller.
 */
public fun GameRandom.rollDamage(maxHit: Int): Int = of(0..maxHit).coerceAtLeast(1)

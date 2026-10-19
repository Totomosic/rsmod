package org.rsmod.game.hit

/** The attack-time distribution, before HP clipping. Accuracy misses are separate. */
public data class DamageRoll(public val range: IntRange, public val raiseZero: Boolean = false) {
    /** Conditional on a damaging splat, matching the room's one-shot definition. */
    public fun chanceAtLeast(hitpoints: Int): Double {
        val first = if (raiseZero) range.first else maxOf(1, range.first)
        if (first > range.last) return 0.0
        if (hitpoints <= 1) return 1.0
        val successes = (range.last.toLong() - maxOf(hitpoints, first) + 1).coerceAtLeast(0)
        return successes.toDouble() / (range.last.toLong() - first + 1)
    }
}

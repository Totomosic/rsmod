package org.rsmod.content.areas.dungeons.nylocas

import org.rsmod.game.hit.DamageRoll

/** All damage rolls against one target from one attack, captured before impact or HP clipping. */
class NylocasAttack(val rolls: MutableList<DamageRoll> = mutableListOf()) {
    var measured = true

    /** Conditional on the attack dealing damage; accuracy misses contribute no roll. */
    fun chanceAtLeast(hitpoints: Int): Double? {
        if (!measured || rolls.isEmpty()) return null
        require(hitpoints > 0)
        var totals = DoubleArray(hitpoints + 1).also { it[0] = 1.0 }
        for (roll in rolls) {
            val outcomes = roll.range.last.toLong() - roll.range.first + 1
            require(roll.range.first >= 0 && outcomes > 0)
            val splat = DoubleArray(hitpoints + 1)
            for (raw in roll.range.first..minOf(roll.range.last, hitpoints - 1)) {
                val damage = if (roll.raiseZero) raw.coerceAtLeast(1) else raw
                splat[damage] += 1.0 / outcomes
            }
            val lethal =
                (roll.range.last.toLong() - maxOf(roll.range.first, hitpoints) + 1).coerceAtLeast(0)
            splat[hitpoints] += lethal.toDouble() / outcomes
            val next = DoubleArray(hitpoints + 1)
            for (before in totals.indices) {
                for (damage in splat.indices) {
                    next[minOf(hitpoints, before + damage)] += totals[before] * splat[damage]
                }
            }
            totals = next
        }
        val damaging = 1.0 - totals[0]
        return if (damaging <= 0.0) 0.0 else (totals[hitpoints] / damaging).coerceIn(0.0, 1.0)
    }
}

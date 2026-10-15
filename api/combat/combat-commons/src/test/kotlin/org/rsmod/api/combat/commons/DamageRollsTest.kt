package org.rsmod.api.combat.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.api.random.GameRandom

class DamageRollsTest {
    @Test
    fun `zero and one rolls both deal one while higher rolls keep their damage`() {
        val damage = (0..4).map { roll -> DamageRandom(roll, maxHit = 4).rollDamage(4) }
        assertEquals(listOf(1, 1, 2, 3, 4), damage)
    }

    @Test
    fun `maximum damage remains inclusive`() {
        assertEquals(45, DamageRandom(45, maxHit = 45).rollDamage(45))
    }

    @Test
    fun `max hit of one always deals one`() {
        assertEquals(1, DamageRandom(0, maxHit = 1).rollDamage(1))
        assertEquals(1, DamageRandom(1, maxHit = 1).rollDamage(1))
    }

    @Test
    fun `zero max hit is clamped to one`() {
        assertEquals(1, DamageRandom(0, maxHit = 0).rollDamage(0))
    }

    private class DamageRandom(private val roll: Int, private val maxHit: Int) : GameRandom {
        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            assertEquals(0, minInclusive)
            assertEquals(maxHit, maxInclusive)
            return roll
        }

        override fun of(maxExclusive: Int): Int = error("Expected an inclusive damage roll")

        override fun randomDouble(): Double = error("Expected an integer damage roll")
    }
}

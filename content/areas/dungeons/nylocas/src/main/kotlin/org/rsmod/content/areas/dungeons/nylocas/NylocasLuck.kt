package org.rsmod.content.areas.dungeons.nylocas

/** Exact Poisson-binomial distribution for the measured attacks' individual success chances. */
internal class NylocasLuck {
    var attempts = 0
        private set

    var actual = 0
    var expected = 0.0
        private set

    private var outcomes = doubleArrayOf(1.0)

    fun attempt(probability: Double) {
        require(probability in 0.0..1.0)
        attempts++
        expected += probability
        val next = DoubleArray(outcomes.size + 1)
        for (successes in outcomes.indices) {
            next[successes] += outcomes[successes] * (1.0 - probability)
            next[successes + 1] += outcomes[successes] * probability
        }
        outcomes = next
    }

    /** Midrank: probability of fewer successes plus half the probability of a tie. */
    fun percentile(): Double =
        (outcomes.take(actual).sum() + outcomes[actual] / 2).coerceIn(0.0, 1.0)
}

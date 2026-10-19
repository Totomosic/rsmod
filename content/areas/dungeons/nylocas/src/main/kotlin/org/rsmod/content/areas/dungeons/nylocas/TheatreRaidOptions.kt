package org.rsmod.content.areas.dungeons.nylocas

data class TheatreRaidOptions(
    val startingWave: Int = 1,
    val nyloMaxHit: NyloMaxHit = NyloMaxHit.Off,
    val startingHitpoints250: Boolean = false,
    val zeal: Boolean = false,
) {
    init {
        require(startingWave in 1..31) { "Starting wave must be between 1 and 31." }
    }
}

enum class NyloMaxHit {
    Off,
    AllWeapons,
    SlowWeapons,
}

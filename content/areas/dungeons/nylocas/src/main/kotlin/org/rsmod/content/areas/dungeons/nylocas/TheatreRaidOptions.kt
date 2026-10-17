package org.rsmod.content.areas.dungeons.nylocas

data class TheatreRaidOptions(val startingWave: Int = 1) {
    init {
        require(startingWave in 1..31) { "Starting wave must be between 1 and 31." }
    }
}

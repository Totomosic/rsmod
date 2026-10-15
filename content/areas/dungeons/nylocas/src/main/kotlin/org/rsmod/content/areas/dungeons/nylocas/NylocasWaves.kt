package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

enum class NylocasSize {
    Small,
    Big;

    fun hitpoints(teamSize: Int): Int =
        when (teamSize) {
            in 1..3 -> if (this == Small) 8 else 16
            4 -> if (this == Small) 9 else 19
            5 -> if (this == Small) 11 else 22
            else -> error("Invalid raid team size: $teamSize")
        }
}

enum class NylocasStyle(val hitType: HitType) {
    Melee(HitType.Melee),
    Ranged(HitType.Ranged),
    Magic(HitType.Magic),
}

enum class NylocasPillar(val source: CoordGrid) {
    SouthWest(CoordGrid(3289, 4242, 0)),
    NorthWest(CoordGrid(3289, 4253, 0)),
    SouthEast(CoordGrid(3300, 4242, 0)),
    NorthEast(CoordGrid(3300, 4253, 0)),
}

enum class NylocasSlot(val source: CoordGrid, val entry: CoordGrid) {
    WestNorth(CoordGrid(3281, 4249, 0), CoordGrid(3292, 4249, 0)),
    WestSouth(CoordGrid(3281, 4248, 0), CoordGrid(3292, 4248, 0)),
    SouthWest(CoordGrid(3295, 4233, 0), CoordGrid(3295, 4245, 0)),
    SouthEast(CoordGrid(3296, 4233, 0), CoordGrid(3296, 4245, 0)),
    EastNorth(CoordGrid(3310, 4249, 0), CoordGrid(3299, 4249, 0)),
    EastSouth(CoordGrid(3310, 4248, 0), CoordGrid(3299, 4248, 0)),
}

data class NylocasSpawn(
    val size: NylocasSize,
    val style: NylocasStyle,
    val pillar: NylocasPillar? = null,
)

data class NylocasWave(
    val ticksAfterPrevious: Int,
    val westNorth: NylocasSpawn? = null,
    val westSouth: NylocasSpawn? = null,
    val southWest: NylocasSpawn? = null,
    val southEast: NylocasSpawn? = null,
    val eastNorth: NylocasSpawn? = null,
    val eastSouth: NylocasSpawn? = null,
) {
    init {
        require(ticksAfterPrevious > 0)
    }

    val spawns: List<Pair<NylocasSlot, NylocasSpawn>>
        get() =
            listOf(
                    NylocasSlot.WestNorth to westNorth,
                    NylocasSlot.WestSouth to westSouth,
                    NylocasSlot.SouthWest to southWest,
                    NylocasSlot.SouthEast to southEast,
                    NylocasSlot.EastNorth to eastNorth,
                    NylocasSlot.EastSouth to eastSouth,
                )
                .mapNotNull { (slot, spawn) -> spawn?.let { slot to it } }
}

class NylocasWaveTable(val waves: List<NylocasWave>) {
    init {
        require(waves.isNotEmpty())
    }

    @Inject
    constructor() :
        this(
            listOf(
                NylocasWave(
                    ticksAfterPrevious = 4,
                    westNorth =
                        NylocasSpawn(
                            NylocasSize.Small,
                            NylocasStyle.Ranged,
                            NylocasPillar.NorthWest,
                        ),
                    southWest =
                        NylocasSpawn(
                            NylocasSize.Small,
                            NylocasStyle.Magic,
                            NylocasPillar.SouthWest,
                        ),
                    eastSouth =
                        NylocasSpawn(NylocasSize.Small, NylocasStyle.Melee, NylocasPillar.NorthEast),
                )
            )
        )
}

package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.content.areas.dungeons.nylocas.NylocasPillar.NorthEast
import org.rsmod.content.areas.dungeons.nylocas.NylocasPillar.NorthWest
import org.rsmod.content.areas.dungeons.nylocas.NylocasPillar.SouthEast
import org.rsmod.content.areas.dungeons.nylocas.NylocasPillar.SouthWest
import org.rsmod.content.areas.dungeons.nylocas.NylocasSize.Big
import org.rsmod.content.areas.dungeons.nylocas.NylocasSize.Small
import org.rsmod.content.areas.dungeons.nylocas.NylocasStyle.Magic
import org.rsmod.content.areas.dungeons.nylocas.NylocasStyle.Melee
import org.rsmod.content.areas.dungeons.nylocas.NylocasStyle.Ranged
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

enum class NylocasSize(val expiryAnimationAge: Int, val expiryRemovalAge: Int) {
    // Zero-based ages: the spawn snapshot is age 0.
    Small(51, 52),
    Big(52, 55);

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
    SouthWest(CoordGrid(3290, 4243, 0)),
    NorthWest(CoordGrid(3290, 4253, 0)),
    SouthEast(CoordGrid(3300, 4243, 0)),
    NorthEast(CoordGrid(3300, 4253, 0)),
}

enum class NylocasSlot(val source: CoordGrid, val entry: CoordGrid) {
    // End the straight approach on the first arena tile, so the very next step
    // uses target pathfinding instead of continuing two more tiles into the room.
    WestNorth(CoordGrid(3281, 4249, 0), CoordGrid(3290, 4249, 0)),
    WestSouth(CoordGrid(3281, 4248, 0), CoordGrid(3290, 4248, 0)),
    SouthWest(CoordGrid(3295, 4233, 0), CoordGrid(3295, 4243, 0)),
    SouthEast(CoordGrid(3296, 4233, 0), CoordGrid(3296, 4243, 0)),
    EastNorth(CoordGrid(3310, 4249, 0), CoordGrid(3301, 4249, 0)),
    EastSouth(CoordGrid(3310, 4248, 0), CoordGrid(3301, 4248, 0)),
}

data class NylocasSpawn(
    val size: NylocasSize,
    val style: NylocasStyle,
    val pillar: NylocasPillar? = null,
    val styleAt5: NylocasStyle? = null,
    val styleAt7: NylocasStyle? = null,
    val styleAt26: NylocasStyle? = null,
    val sourceOverride: CoordGrid? = null,
) {
    // Ages are zero-based: the spawn snapshot is age 0. Null late styles retain the
    // previous style. Every flickering lane spawn has an explicit age-26 style.
    fun styleAtAge(age: Int): NylocasStyle =
        when {
            age >= 26 && styleAt26 != null -> styleAt26
            age >= 7 && styleAt7 != null -> styleAt7
            age >= 5 && styleAt5 != null -> styleAt5
            else -> style
        }

    fun source(slot: NylocasSlot): CoordGrid =
        sourceOverride
            ?: if (
                size == NylocasSize.Big &&
                    slot in listOf(NylocasSlot.EastNorth, NylocasSlot.EastSouth)
            ) {
                CoordGrid(3309, 4248, 0)
            } else {
                slot.source
            }
}

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

    // Verified against the 13 pinned normal-mode replays in docs/research/nylocas.
    // Delays belong to the incoming wave (the reference's naturalStall belongs to
    // the outgoing wave). In particular wave 6 follows wave 5 by 16 ticks.
    // Physical anchors and pillar targets override the guide's display slots.
    // Age-26 styles for wave 18 west, 19 east, and 20 south bigs are user-confirmed.
    @Inject
    constructor() :
        this(
            listOf(
                // Wave 1
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastNorth = NylocasSpawn(Small, Melee, NorthWest),
                    southEast = NylocasSpawn(Small, Magic, NorthEast),
                    westSouth = NylocasSpawn(Small, Ranged, null),
                ),
                // Wave 2
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastSouth = NylocasSpawn(Small, Ranged, SouthEast),
                    southWest = NylocasSpawn(Small, Melee, null),
                    westNorth = NylocasSpawn(Small, Magic, NorthEast),
                ),
                // Wave 3
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastNorth = NylocasSpawn(Small, Magic, null),
                    southEast = NylocasSpawn(Small, Ranged, SouthEast),
                    westSouth = NylocasSpawn(Small, Melee, NorthEast),
                ),
                // Wave 4
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastSouth = NylocasSpawn(Small, Melee, NorthEast),
                    southWest = NylocasSpawn(Big, Magic, SouthWest),
                    westNorth = NylocasSpawn(Small, Ranged, NorthWest),
                ),
                // Wave 5
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastNorth = NylocasSpawn(Small, Magic, NorthEast),
                    southEast = NylocasSpawn(Small, Melee, SouthEast),
                    westSouth = NylocasSpawn(Big, Ranged, SouthWest),
                ),
                // Wave 6
                NylocasWave(
                    ticksAfterPrevious = 16,
                    eastSouth = NylocasSpawn(Big, Melee, SouthEast),
                    southWest = NylocasSpawn(Small, Ranged, NorthWest),
                    westNorth = NylocasSpawn(Small, Magic, NorthEast),
                ),
                // Wave 7
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastNorth = NylocasSpawn(Small, Melee, NorthWest),
                    southEast = NylocasSpawn(Small, Magic, NorthEast),
                    southWest = NylocasSpawn(Big, Ranged, null),
                ),
                // Wave 8
                NylocasWave(
                    ticksAfterPrevious = 12,
                    eastSouth = NylocasSpawn(Small, Ranged, SouthEast),
                    southWest = NylocasSpawn(Small, Melee, NorthWest),
                    westSouth = NylocasSpawn(Big, Magic, null),
                ),
                // Wave 9
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastNorth = NylocasSpawn(Small, Magic, NorthWest),
                    westNorth =
                        NylocasSpawn(
                            Small,
                            Melee,
                            NorthEast,
                            sourceOverride = CoordGrid(3281, 4248, 0),
                        ),
                    westSouth = NylocasSpawn(Big, Ranged, null),
                ),
                // Wave 10
                NylocasWave(
                    ticksAfterPrevious = 12,
                    eastNorth = NylocasSpawn(Small, Ranged, null),
                    eastSouth = NylocasSpawn(Big, Ranged, NorthEast),
                    southWest = NylocasSpawn(Small, Ranged, SouthWest),
                    southEast = NylocasSpawn(Small, Ranged, SouthEast),
                    westSouth = NylocasSpawn(Small, Ranged, null),
                    westNorth = NylocasSpawn(Small, Ranged, NorthWest),
                ),
                // Wave 11
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Small, Magic, NorthEast),
                    eastNorth = NylocasSpawn(Small, Magic, null),
                    southWest = NylocasSpawn(Small, Magic, SouthWest),
                    southEast = NylocasSpawn(Small, Magic, SouthEast),
                    westSouth = NylocasSpawn(Big, Magic, null),
                ),
                // Wave 12
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Small, Melee, NorthEast),
                    eastNorth = NylocasSpawn(Small, Melee, null),
                    southWest = NylocasSpawn(Big, Melee, SouthWest),
                    westSouth = NylocasSpawn(Small, Melee, null),
                    westNorth = NylocasSpawn(Small, Melee, NorthWest),
                ),
                // Wave 13
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Big, Melee, null),
                    southWest = NylocasSpawn(Small, Melee, SouthWest),
                    southEast = NylocasSpawn(Small, Ranged, SouthEast),
                    westSouth = NylocasSpawn(Small, Magic, null),
                    westNorth = NylocasSpawn(Small, Ranged, NorthWest),
                ),
                // Wave 14
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Big, Ranged, null),
                    southEast = NylocasSpawn(Small, Magic, SouthEast),
                    southWest = NylocasSpawn(Small, Ranged, SouthWest),
                    westNorth = NylocasSpawn(Small, Magic, NorthWest),
                    westSouth = NylocasSpawn(Small, Melee, null),
                ),
                // Wave 15
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastNorth = NylocasSpawn(Small, Magic, null),
                    eastSouth = NylocasSpawn(Small, Ranged, NorthEast),
                    southWest = NylocasSpawn(Big, Magic, SouthWest),
                    westNorth = NylocasSpawn(Small, Melee, NorthWest),
                    westSouth = NylocasSpawn(Small, Ranged, null),
                ),
                // Wave 16
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Small, Magic, NorthEast, Melee, Ranged, Magic),
                    southWest = NylocasSpawn(Small, Melee, SouthWest, Magic, Ranged, Magic),
                    westNorth = NylocasSpawn(Small, Ranged, NorthWest, Magic, Ranged, Magic),
                ),
                // Wave 17
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastSouth = NylocasSpawn(Big, Magic, NorthEast, Melee, Magic, Melee),
                    southWest = NylocasSpawn(Big, Magic, SouthEast, Melee, Magic, Melee),
                    westSouth = NylocasSpawn(Big, Magic, SouthWest, Melee, Magic, Melee),
                ),
                // Wave 18
                NylocasWave(
                    ticksAfterPrevious = 12,
                    eastSouth = NylocasSpawn(Big, Ranged, NorthWest, Magic, Ranged, Magic),
                    southWest = NylocasSpawn(Big, Ranged, SouthWest, Magic, Ranged, Magic),
                    westSouth = NylocasSpawn(Big, Ranged, null, Magic, Ranged, Magic),
                ),
                // Wave 19
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Big, Magic, null, Melee, Magic, Melee),
                    southWest = NylocasSpawn(Big, Magic, SouthEast, Melee, Magic, Melee),
                    westSouth = NylocasSpawn(Big, Magic, NorthEast, Melee, Magic, Melee),
                ),
                // Wave 20
                NylocasWave(
                    ticksAfterPrevious = 12,
                    eastSouth = NylocasSpawn(Big, Melee, NorthEast, Ranged, Melee, Ranged),
                    southWest = NylocasSpawn(Big, Magic, null, Ranged, Melee, Ranged),
                    westSouth = NylocasSpawn(Big, Melee, NorthWest, Ranged, Melee, Ranged),
                ),
                // Wave 21
                NylocasWave(
                    ticksAfterPrevious = 16,
                    eastSouth = NylocasSpawn(Small, Ranged, null, Melee, Ranged, Magic),
                    eastNorth = NylocasSpawn(Small, Ranged, NorthEast, Melee, Ranged, Melee),
                    southWest = NylocasSpawn(Small, Melee, null, Ranged, Melee, Ranged),
                    southEast = NylocasSpawn(Small, Melee, SouthEast, Magic, Melee, Ranged),
                    westSouth = NylocasSpawn(Small, Magic, SouthWest, Melee, Ranged, Magic),
                    westNorth = NylocasSpawn(Small, Magic, NorthWest, Ranged, Magic, Melee),
                ),
                // Wave 22
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Big, Magic, null, Ranged, Melee, Ranged),
                    southWest = NylocasSpawn(Small, Magic, SouthWest, Ranged, Melee, Magic),
                    southEast = NylocasSpawn(Small, Ranged, SouthEast, Magic, Melee, Magic),
                    westSouth = NylocasSpawn(Big, Melee, SouthWest, Ranged, Melee, Ranged),
                ),
                // Wave 23
                NylocasWave(
                    ticksAfterPrevious = 12,
                    eastSouth = NylocasSpawn(Big, Magic, NorthEast, Ranged, Melee, Ranged),
                    southWest = NylocasSpawn(Big, Ranged, null, Magic, Melee, Magic),
                    westNorth = NylocasSpawn(Small, Magic, NorthWest, Ranged, Melee, Ranged),
                    westSouth = NylocasSpawn(Small, Ranged, SouthWest, Magic, Ranged, Melee),
                ),
                // Wave 24
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Big, Melee, null),
                    southWest = NylocasSpawn(Big, Magic, null),
                    westSouth = NylocasSpawn(Big, Ranged, null, Magic, Melee, Magic),
                ),
                // Wave 25
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Big, Magic, SouthEast, Melee, Magic, Melee),
                    southWest = NylocasSpawn(Big, Ranged, SouthWest),
                    westSouth = NylocasSpawn(Big, Melee, null),
                ),
                // Wave 26
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastSouth = NylocasSpawn(Big, Magic, NorthEast),
                    southWest = NylocasSpawn(Big, Melee, SouthWest, Magic, Melee, Ranged),
                    westSouth = NylocasSpawn(Big, Magic, null),
                ),
                // Wave 27
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastSouth = NylocasSpawn(Big, Magic, SouthEast, Melee, Magic, Melee),
                    southWest = NylocasSpawn(Big, Melee, null, Magic, Ranged, Magic),
                    westSouth = NylocasSpawn(Big, Magic, NorthWest),
                ),
                // Wave 28
                NylocasWave(
                    ticksAfterPrevious = 8,
                    eastNorth = NylocasSpawn(Small, Magic, NorthEast, Melee, Magic, Melee),
                    eastSouth = NylocasSpawn(Small, Ranged, SouthEast, Magic, Melee, Ranged),
                    southWest = NylocasSpawn(Small, Magic, SouthWest, Melee, Ranged, Magic),
                    southEast = NylocasSpawn(Small, Melee, SouthEast, Ranged, Melee, Magic),
                    westNorth = NylocasSpawn(Small, Melee, NorthWest, Magic, Melee, Ranged),
                    westSouth = NylocasSpawn(Small, Ranged, null, Melee, Magic, Melee),
                ),
                // Wave 29
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastNorth = NylocasSpawn(Small, Magic, NorthEast, Ranged, Melee, Magic),
                    eastSouth = NylocasSpawn(Small, Ranged, null, Melee, Magic, Melee),
                    southWest = NylocasSpawn(Big, Melee, SouthEast),
                    westNorth = NylocasSpawn(Small, Melee, null, Ranged, Magic, Ranged),
                    westSouth = NylocasSpawn(Small, Ranged, SouthWest, Melee, Ranged, Melee),
                ),
                // Wave 30
                NylocasWave(
                    ticksAfterPrevious = 4,
                    // Unlike the other east bigs, the recorded wave-30 anchor is x3310.
                    eastSouth =
                        NylocasSpawn(Big, Magic, null, sourceOverride = CoordGrid(3310, 4248, 0)),
                    southWest = NylocasSpawn(Small, Magic, SouthWest, Ranged, Melee, Magic),
                    southEast = NylocasSpawn(Small, Melee, SouthEast, Ranged, Melee, Magic),
                    westSouth = NylocasSpawn(Big, Ranged, SouthWest, Melee, Ranged, Melee),
                ),
                // Wave 31
                NylocasWave(
                    ticksAfterPrevious = 4,
                    eastNorth = NylocasSpawn(Small, Magic, NorthEast, Ranged, Magic, Ranged),
                    eastSouth = NylocasSpawn(Small, Ranged, SouthEast, Melee, Ranged, Melee),
                    southWest = NylocasSpawn(Small, Magic, SouthWest, Melee, Ranged, Melee),
                    southEast = NylocasSpawn(Small, Melee, SouthEast, Magic, Ranged, Melee),
                    westNorth = NylocasSpawn(Small, Melee, NorthWest, Ranged, Magic, Ranged),
                    westSouth = NylocasSpawn(Small, Ranged, SouthWest, Melee, Ranged, Magic),
                ),
            )
        )
}

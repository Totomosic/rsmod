package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.NpcList
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid

class NylocasEncounterTest {
    class Dependencies @Inject constructor(val regions: RegionRegistry, val npcs: NpcList)

    @Test
    fun GameTestState.`pass enters the arena and spawns exactly one wave four ticks later`() =
        runInjectedGameTest(Dependencies::class, null, NylocasEncounter::class) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance(ticks = 8)
            assertEquals(0, deps.npcs.count())

            player.opLoc1(barrier)
            advance()
            assertEquals(CoordGrid(3295, 4254, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(0, deps.npcs.count())
            advance(ticks = 3)
            assertEquals(0, deps.npcs.count())
            advance()

            assertEquals(
                setOf(
                    8344 to CoordGrid(3295, 4233, 0),
                    8343 to CoordGrid(3281, 4249, 0),
                    8342 to CoordGrid(3310, 4248, 0),
                ),
                deps.npcs.map { it.id to deps.regions.normalizeCoords(it.coords) }.toSet(),
            )
            assertEquals(3, deps.npcs.count())
            assertTrue(deps.npcs.all { it.size == 1 && !it.respawns })
            advance(ticks = 40)
            assertEquals(3, deps.npcs.count())
        }

    @Test
    fun GameTestState
        .`both barrier halves share one countdown independent of the starting player`() =
        runInjectedGameTest(Dependencies::class, null, NylocasEncounter::class) { deps ->
            val room = createRoom()
            val west = placeBarrier(room)
            val east = placeBarrier(room, x = 3296)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            val other = registerPlayer(coords = room.normal[CoordGrid(3296, 4256, 0)])
            advance()

            player.opLoc1(west)
            advance()
            other.opLoc1(east)
            advance()
            unregisterPlayer(player)
            advance(ticks = 2)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())

            // Passing out and back in after the wave must not start a second one.
            other.opLoc1(east)
            advance()
            other.opLoc1(east)
            advance(ticks = 10)
            assertEquals(3, deps.npcs.count())
        }

    @Test
    fun GameTestState.`separate instances spawn their own waves`() =
        runInjectedGameTest(Dependencies::class, null, NylocasEncounter::class) { deps ->
            val first = createRoom()
            val firstBarrier = placeBarrier(first)
            player.telejump(first.normal[CoordGrid(3295, 4256, 0)])
            advance()

            val second = createRoom()
            val secondBarrier = placeBarrier(second)
            val other = registerPlayer(coords = second.normal[CoordGrid(3295, 4256, 0)])
            advance()
            player.opLoc1(firstBarrier)
            other.opLoc1(secondBarrier)
            advance(ticks = 4)
            assertEquals(0, deps.npcs.count())
            advance()

            assertEquals(6, deps.npcs.count())
            assertEquals(3, deps.npcs.count { deps.regions[it.coords]?.uid == first.uid })
            assertEquals(3, deps.npcs.count { deps.regions[it.coords]?.uid == second.uid })
        }

    @Test
    fun GameTestState.`reclaiming a room cancels pending spawns`() =
        runInjectedGameTest(Dependencies::class, null, NylocasEncounter::class) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance()
            player.opLoc1(barrier)
            advance()

            player.telejump(CoordGrid(3222, 3218, 0))
            advance()
            removeInactiveRegions()
            assertEquals(Region.INVALID_SLOT, room.slot)
            advance(ticks = 8)
            assertEquals(0, deps.npcs.count())
        }

    @Test
    fun GameTestState.`passing from inside only exits and does not start a wave`() =
        runInjectedGameTest(Dependencies::class, null, NylocasEncounter::class) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4254, 0)])
            advance()
            player.opLoc1(barrier)
            advance()

            assertEquals(CoordGrid(3295, 4256, 0), deps.regions.normalizeCoords(player.coords))
            advance(ticks = 8)
            assertEquals(0, deps.npcs.count())
        }

    @Test
    fun GameTestState.`the shared barrier type outside Nylocas does not start a wave`() =
        runInjectedGameTest(Dependencies::class, null, NylocasEncounter::class) { deps ->
            val source = CoordGrid(3200, 3200, 0)
            val barrier = placeMapLoc(source, nylocas_locs.barrier)
            player.telejump(source.translateZ(1))
            advance()
            player.opLoc1(barrier)
            advance(ticks = 8)

            assertEquals(source.translateZ(1), player.coords)
            assertEquals(0, deps.npcs.count())
        }

    private fun GameTestScope.createRoom(): Region {
        // Instanced loc lookups resolve through the source map, as they do with the live cache.
        placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
        placeMapLoc(CoordGrid(3296, 4255, 0), nylocas_locs.barrier)
        return createRegion(NylocasRoom.template)
    }

    private fun GameTestScope.placeBarrier(room: Region, x: Int = 3295): BoundLocInfo {
        val source = placeMapLoc(CoordGrid(x, 4255, 0), nylocas_locs.barrier)
        return source.copy(coords = room.normal[source.coords])
    }
}

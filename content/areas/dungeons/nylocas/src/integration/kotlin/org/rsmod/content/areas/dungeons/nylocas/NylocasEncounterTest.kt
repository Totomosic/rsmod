package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.NpcList
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid

class NylocasEncounterTest {
    class Dependencies
    @Inject
    constructor(val regions: RegionRegistry, val npcs: NpcList, val raid: TheatreRaid)

    @ParameterizedTest
    @CsvSource("3295, 4256, 4254", "3296, 4256, 4254", "3295, 4254, 4256", "3296, 4254, 4256")
    fun `barrier crossing walks with a one tick delay even when running`(
        x: Int,
        startZ: Int,
        endZ: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room, x)
            player.telejump(room.normal[CoordGrid(x, startZ, 0)])
            player.enableRun()
            advance(ticks = 2)
            deps.raid.start(player)

            player.opLoc1(barrier)
            advance()

            assertEquals(CoordGrid(x, endZ, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(MoveSpeed.Walk, player.moveSpeed)
            assertTrue(player.isDelayed)
            advance()
            assertFalse(player.isDelayed)
        }

    @ParameterizedTest
    @CsvSource("1, 8", "2, 8", "3, 8", "4, 9", "5, 11")
    fun `first wave scales current and maximum HP to the raid team`(
        teamSize: Int,
        expectedHp: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            repeat(teamSize - 1) { registerPlayer(coords = room.normal[CoordGrid(3295, 4256, 0)]) }
            advance()
            deps.raid.start(player)
            player.opLoc1(barrier)
            advance(ticks = 5)

            assertEquals(3, deps.npcs.count())
            for (npc in deps.npcs) {
                assertEquals(expectedHp, npc.hitpoints)
                assertEquals(expectedHp, npc.baseHitpointsLvl)
            }
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 40)
            assertTrue(
                deps.npcs.all { it.hitpoints == expectedHp && it.baseHitpointsLvl == expectedHp }
            )
        }

    @Test
    fun GameTestState.`departures during the countdown do not change raid HP scaling`() =
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            val others = List(4) { registerPlayer(coords = room.normal[CoordGrid(3295, 4256, 0)]) }
            advance()
            deps.raid.start(player)
            player.opLoc1(barrier)
            advance()
            others.forEach(::unregisterPlayer)
            advance(ticks = 4)

            assertEquals(3, deps.npcs.count())
            assertTrue(deps.npcs.all { it.hitpoints == 11 && it.baseHitpointsLvl == 11 })
        }

    @Test
    fun GameTestState.`pass enters the arena and spawns exactly one wave four ticks later`() =
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance(ticks = 8)
            assertEquals(0, deps.npcs.count())
            deps.raid.start(player)

            player.opLoc1(barrier)
            advance()
            assertEquals(CoordGrid(3295, 4254, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(0, deps.npcs.count())
            advance(ticks = 3)
            assertEquals(0, deps.npcs.count())
            advance()

            assertEquals(
                setOf(
                    8344 to CoordGrid(3296, 4233, 0),
                    8343 to CoordGrid(3281, 4248, 0),
                    8342 to CoordGrid(3310, 4249, 0),
                ),
                deps.npcs.map { it.id to deps.regions.normalizeCoords(it.coords) }.toSet(),
            )
            assertEquals(3, deps.npcs.count())
            assertTrue(deps.npcs.all { it.size == 1 && !it.respawns })
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 40)
            assertEquals(3, deps.npcs.count())
        }

    @Test
    fun GameTestState
        .`both barrier halves share one countdown independent of the starting player`() =
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val west = placeBarrier(room)
            val east = placeBarrier(room, x = 3296)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            val other = registerPlayer(coords = room.normal[CoordGrid(3296, 4256, 0)])
            advance()

            deps.raid.start(player)
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
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val first = createRoom()
            val firstBarrier = placeBarrier(first)
            player.telejump(first.normal[CoordGrid(3295, 4256, 0)])
            advance()

            val second = createRoom()
            val secondBarrier = placeBarrier(second)
            val other = registerPlayer(coords = second.normal[CoordGrid(3295, 4256, 0)])
            advance()
            deps.raid.start(player)
            deps.raid.start(other)
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
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance()
            deps.raid.start(player)
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
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
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
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val source = CoordGrid(3200, 3200, 0)
            val barrier = placeMapLoc(source, nylocas_locs.barrier)
            player.telejump(source.translateZ(1))
            advance()
            player.opLoc1(barrier)
            advance(ticks = 8)

            assertEquals(source.translateZ(1), player.coords)
            assertEquals(0, deps.npcs.count())
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `entry preserves the phase established before the barrier`(
        delay: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance()
            deps.raid.start(player)
            advance(ticks = delay)
            player.opLoc1(barrier)
            advance()
            val countdown = 4 + Math.floorMod(-delay, 4)
            advance(ticks = countdown - 1)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())
        }

    // These tests isolate entry/countdown/party behavior from later waves.
    private fun firstWave(): AbstractModule =
        object : AbstractModule() {
            override fun configure() {
                bind(NylocasWaveTable::class.java)
                    .toInstance(NylocasWaveTable(NylocasWaveTable().waves.take(1)))
            }
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

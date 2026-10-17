package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
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
    @ValueSource(ints = [1, 20, 21, 31])
    fun `selected starting wave spawns after the entry countdown with its real composition`(
        startingWave: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance()
            deps.raid.start(player, TheatreRaidOptions(startingWave))
            // An attempted change after Start raid cannot alter the pending encounter.
            deps.raid.start(player, TheatreRaidOptions(if (startingWave == 1) 31 else 1))
            player.opLoc1(barrier)
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance(ticks = 4)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(startingWave, player.vars[tob_varbits.progress_value])
            val expected =
                NylocasWaveTable().waves[startingWave - 1].spawns.map { (slot, spawn) ->
                    room.normal[spawn.source(slot)] to
                        nylocas_npcs.incoming(spawn.size, spawn.style).id
                }
            assertEquals(expected.toSet(), deps.npcs.map { it.coords to it.id }.toSet())
            assertEquals(expected.size, deps.npcs.count())
            val next = NylocasWaveTable().waves.getOrNull(startingWave)
            if (next != null) {
                advance(ticks = next.ticksAfterPrevious - 1)
                assertEquals(startingWave, player.vars[tob_varbits.progress_value])
                advance()
                assertEquals(startingWave + 1, player.vars[tob_varbits.progress_value])
            }
        }

    @Test
    fun GameTestState.`entry waits for confirmation before starting the four tick countdown`() =
        runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val barrier = placeBarrier(room)
            val outside = room.normal[CoordGrid(3295, 4256, 0)]
            player.telejump(outside)
            advance()
            deps.raid.start(player)

            player.opLoc1(barrier)
            advance(ticks = 10)
            assertTrue(player.ui.containsModal(interfaces.chatmenu))
            assertEquals(outside, player.coords)
            assertEquals(0, player.vars[tob_varbits.progress_type])
            assertEquals(0, deps.npcs.count())

            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()
            assertFalse(player.ui.containsModal(interfaces.chatmenu))
            assertEquals(CoordGrid(3295, 4254, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(1, player.vars[tob_varbits.progress_type])
            advance(ticks = 3)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancel or walking away closes the prompt without starting the room`(
        walkAway: Boolean,
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
            player.opLoc1(barrier)
            advance()
            assertTrue(player.ui.containsModal(interfaces.chatmenu))

            if (walkAway) {
                player.moveGameClick(room.normal[NylocasRoom.bridge])
            } else {
                player.resumePauseButton(components.chatmenu_pbutton, sub = 2)
            }
            advance()
            assertFalse(player.ui.containsModal(interfaces.chatmenu))
            // A stale keypress cannot enter after cancelling.
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance(ticks = 8)
            assertTrue(deps.regions.normalizeCoords(player.coords).z >= 4256)
            assertEquals(0, player.vars[tob_varbits.progress_type])
            assertEquals(0, deps.npcs.count())

            // Cancelling does not prevent a fresh attempt.
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance()
            player.opLoc1(barrier)
            advance()
            assertTrue(player.ui.containsModal(interfaces.chatmenu))
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()
            assertEquals(CoordGrid(3295, 4254, 0), deps.regions.normalizeCoords(player.coords))
        }

    @ParameterizedTest
    @CsvSource(
        "false, false, false",
        "true, false, false",
        "false, true, false",
        "false, false, true",
        "true, false, true",
    )
    fun `one confirmation brings both waiting players through on the same tick`(
        otherConfirms: Boolean,
        bothConfirm: Boolean,
        sameBarrier: Boolean,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            firstWave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = createRoom()
            val west = placeBarrier(room)
            val otherX = if (sameBarrier) 3295 else 3296
            val otherBarrier = placeBarrier(room, x = otherX)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            val other = registerPlayer(coords = room.normal[CoordGrid(otherX, 4256, 0)])
            val bystander = registerPlayer(coords = room.normal[NylocasRoom.bridge])
            advance()
            deps.raid.start(player)
            player.enableRun()
            other.enableRun()
            player.opLoc1(west)
            other.opLoc1(otherBarrier)
            advance(ticks = 6)
            assertTrue(player.ui.containsModal(interfaces.chatmenu))
            assertTrue(other.ui.containsModal(interfaces.chatmenu))
            assertEquals(0, deps.npcs.count())

            val confirming = if (otherConfirms) other else player
            confirming.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            if (bothConfirm) other.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()

            for ((member, x) in listOf(player to 3295, other to otherX)) {
                assertEquals(CoordGrid(x, 4254, 0), deps.regions.normalizeCoords(member.coords))
                assertEquals(
                    CoordGrid(x, 4256, 0),
                    deps.regions.normalizeCoords(member.previousCoords),
                )
                assertFalse(member.ui.containsModal(interfaces.chatmenu))
                assertEquals(MoveSpeed.Walk, member.moveSpeed)
                assertTrue(member.isDelayed)
            }
            assertEquals(room.normal[NylocasRoom.bridge], bystander.coords)
            advance()
            assertFalse(player.isDelayed)
            assertFalse(other.isDelayed)
            advance(ticks = 2)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())
        }

    @ParameterizedTest
    @ValueSource(strings = ["option", "walk", "close", "logout"])
    fun `players who cancel are excluded when a teammate confirms`(
        cancellation: String,
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
            val other = registerPlayer(coords = room.normal[CoordGrid(3296, 4256, 0)])
            advance()
            deps.raid.start(player)
            player.opLoc1(barrier)
            other.opLoc1(placeBarrier(room, x = 3296))
            advance()

            when (cancellation) {
                "option" -> other.resumePauseButton(components.chatmenu_pbutton, sub = 2)
                "walk" -> other.moveGameClick(room.normal[NylocasRoom.bridge])
                "close" -> other.ifClose()
                "logout" -> unregisterPlayer(other)
            }
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()
            assertEquals(CoordGrid(3295, 4254, 0), deps.regions.normalizeCoords(player.coords))
            assertTrue(deps.regions.normalizeCoords(other.coords).z >= 4256)
            advance(ticks = 4)
            assertEquals(3, deps.npcs.count())
        }

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
            if (startZ > 4255) {
                advance()
                player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            }
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
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
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
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
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
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
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
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()
            other.opLoc1(east)
            advance()
            assertFalse(other.ui.containsModal(interfaces.chatmenu))
            assertEquals(CoordGrid(3296, 4254, 0), deps.regions.normalizeCoords(other.coords))
            unregisterPlayer(player)
            advance(ticks = 2)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())

            // Passing out and back in after the wave must not start a second one.
            other.opLoc1(east)
            advance(ticks = 2)
            other.opLoc1(east)
            advance(ticks = 10)
            assertFalse(other.ui.containsModal(interfaces.chatmenu))
            assertEquals(CoordGrid(3296, 4254, 0), deps.regions.normalizeCoords(other.coords))
            assertEquals(3, deps.npcs.count())
        }

    @Test
    fun GameTestState.`separate instances start their clocks on their own barrier entry`() =
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
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance(ticks = 2)
            assertTrue(other.ui.containsModal(interfaces.chatmenu))
            assertEquals(CoordGrid(3295, 4256, 0), deps.regions.normalizeCoords(other.coords))
            other.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance(ticks = 2)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())
            assertTrue(deps.npcs.all { deps.regions[it.coords]?.uid == first.uid })
            advance()
            assertEquals(3, deps.npcs.count())
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
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
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
    @ValueSource(ints = [0, 1, 2, 3, 20, 21, 22, 23])
    fun `barrier entry is tick zero regardless of the wait after starting the raid`(
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
            assertEquals(0, deps.npcs.count())
            assertEquals(0, player.vars[tob_varbits.progress_type])
            player.opLoc1(barrier)
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()
            assertEquals(1, player.vars[tob_varbits.progress_type])
            advance(ticks = 3)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())
            assertEquals(2, player.vars[tob_varbits.progress_type])
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

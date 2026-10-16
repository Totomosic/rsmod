package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.client.ClientCycle
import org.rsmod.game.client.NoopClientCycle
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

class NylocasPillarsTest {
    class Dependencies
    @Inject
    constructor(val raid: TheatreRaid, val npcs: NpcList, val regions: RegionRegistry)

    @Test
    fun GameTestState.`room start sends all four pillar NPCs before the first countdown update`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            TheatreRaid::class,
            NylocasEncounter::class,
            NylocasPillars::class,
        ) { deps ->
            val source = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            val other = registerPlayer(coords = room.normal[CoordGrid(3296, 4256, 0)])
            advance()
            deps.raid.start(player)
            advance(ticks = 5)
            assertEquals(0, deps.npcs.count())

            val updates = mutableListOf<Pair<Int, Set<CoordGrid>>>()
            player.clientCycle =
                object : ClientCycle by NoopClientCycle {
                    override fun update(player: Player) {
                        updates +=
                            player.vars[tob_varbits.progress_type] to
                                deps.npcs
                                    .filter { it.id == 8358 }
                                    .map { deps.regions.normalizeCoords(it.coords) }
                                    .toSet()
                    }
                }
            player.opLoc1(source.copy(coords = room.normal[source.coords]))
            advance()
            assertEquals(0, deps.npcs.count())
            updates.clear()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()

            assertEquals(1, updates.single().first)
            assertEquals(NylocasPillar.entries.map { it.source }.toSet(), updates.single().second)
            assertEquals(4, deps.npcs.count())
            assertTrue(deps.npcs.all { it.id == 8358 && !it.respawns && it.aiTimer == 0 })

            // A second party member crossing must not produce a second set of pillars.
            other.opLoc1(source.copy(coords = room.normal[source.coords]))
            advance(ticks = 4)
            assertEquals(4, deps.npcs.count { it.id == 8358 })
            assertEquals(3, deps.npcs.count { it.id in 8342..8353 })
        }

    @Test
    fun GameTestState.`pillars belong to their room and are removed with a reclaimed instance`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class, NylocasPillars::class) {
            deps ->
            val first = createRegion(NylocasRoom.template)
            player.telejump(first.normal[NylocasRoom.bridge])
            advance()
            deps.raid.start(player)
            deps.raid.beginRoom(first)
            val pillars = deps.npcs.toList()
            assertEquals(4, pillars.size)

            val second = createRegion(NylocasRoom.template)
            val other = registerPlayer(coords = second.normal[NylocasRoom.bridge])
            advance()
            deps.raid.start(other)
            assertEquals(0, deps.npcs.count { deps.regions[it.coords]?.uid == second.uid })
            deps.raid.beginRoom(second)
            assertEquals(8, deps.npcs.count())

            player.telejump(CoordGrid(3222, 3218, 0))
            advance()
            removeInactiveRegions()
            advance()
            assertTrue(pillars.none { it.isSlotAssigned })
            assertEquals(4, deps.npcs.count())
        }
}

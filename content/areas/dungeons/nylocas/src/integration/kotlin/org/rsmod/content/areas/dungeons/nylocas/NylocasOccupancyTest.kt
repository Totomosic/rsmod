package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.config.refs.components
import org.rsmod.api.death.plugin.NpcDeathScript
import org.rsmod.api.npc.queueDeath
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.map.collision.add
import org.rsmod.game.map.collision.get
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasOccupancyTest {
    class Dependencies
    @Inject
    constructor(
        val regions: RegionRegistry,
        val npcs: NpcList,
        val npcTypes: NpcTypeList,
        val npcRepo: NpcRepository,
        val raid: TheatreRaid,
        val bounds: BoundValidator,
        val collision: CollisionFlagMap,
    )

    @Test
    fun GameTestState.`a full encounter leaves no NPC collision on empty tiles during expiry`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasEncounter::class,
            TheatreRaid::class,
            NylocasPillars::class,
        ) { deps ->
            val room = start(deps)
            assertEquals(3, deps.npcs.count { it.id in 8342..8353 })
            assertEquals(4, deps.npcs.count { it.id == 8358 })
            val tiles =
                (3280..3311).flatMap { x ->
                    (4232..4255).map { z -> room.normal[CoordGrid(x, z, 0)] }
                }
            repeat(900) { tick ->
                val occupied = buildSet {
                    for (npc in deps.npcs) {
                        for (x in 0 until npc.collisionSize) {
                            for (z in 0 until npc.collisionSize) add(npc.coords.translate(x, z))
                        }
                    }
                }
                val stale =
                    tiles.filter {
                        it !in occupied && deps.collision[it] and CollisionFlag.BLOCK_NPCS != 0
                    }
                assertTrue(stale.isEmpty()) {
                    "Empty tiles retain NPC collision at tick $tick: $stale"
                }
                if (tick == 40) {
                    val firstBig = deps.npcs.single { it.id in 8342..8353 && it.size == 2 }
                    val pillar =
                        NpcAvatar(2).apply { coords = room.normal[NylocasPillar.SouthWest.source] }
                    assertTrue(deps.bounds.touches(firstBig.avatar, pillar))
                }
                advance()
            }
            assertEquals(31, player.vars[tob_varbits.progress_value])
            assertEquals(0, deps.npcs.count { it.id in 8342..8353 })
            assertEquals(4, deps.npcs.count { it.id == 8358 })
        }

    @Test
    fun GameTestState.`a lane big reserves only its southwest tile and moves that reservation`() =
        runInjectedGameTest(
            Dependencies::class,
            wave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val big = deps.npcs.single()
            val source = big.coords
            assertEquals(2, big.size)
            assertEquals(setOf(source), occupied(deps, source))
            advance()
            assertEquals(source.translate(-1, 0), big.coords)
            assertEquals(setOf(big.coords), occupied(deps, big.coords))
            assertEquals(0, deps.collision[source] and CollisionFlag.BLOCK_NPCS)
        }

    @ParameterizedTest
    @CsvSource("NorthEast, 3300", "NorthWest, 3290")
    fun `a big reaches the south face while both small contact tiles are occupied`(
        pillar: NylocasPillar,
        x: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            wave(pillar),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val big = enter(deps, room)
            val smalls = (0..1).map { stationary(deps, room, x + it, 4252) }
            big.telejump(room.normal[CoordGrid(x, 4250, 0)])
            advance()
            assertEquals(room.normal[CoordGrid(x, 4251, 0)], big.coords)
            val target = NpcAvatar(2).apply { coords = room.normal[pillar.source] }
            assertTrue(deps.bounds.touches(big.avatar, target))
            assertTrue(smalls.all { deps.bounds.collides(big.avatar, it.avatar) })
            advance(ticks = 3)
            assertEquals(room.normal[CoordGrid(x, 4251, 0)], big.coords)
            assertTrue(smalls.all { it.hitpoints > 0 })
        }

    @Test
    fun GameTestState
        .`two living bigs can settle with overlapping footprints and distinct anchors`() =
        runInjectedGameTest(
            Dependencies::class,
            wave(NylocasPillar.SouthWest, secondBig = true),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            advance(ticks = 20)
            val (parked, approaching) = deps.npcs.toList()
            parked.telejump(room.normal[CoordGrid(3292, 4243, 0)])
            approaching.telejump(room.normal[CoordGrid(3295, 4243, 0)])
            advance(ticks = 6)
            val pillar = NpcAvatar(2).apply { coords = room.normal[NylocasPillar.SouthWest.source] }
            assertEquals(room.normal[CoordGrid(3292, 4243, 0)], parked.coords)
            assertNotEquals(parked.coords, approaching.coords)
            assertTrue(deps.bounds.touches(approaching.avatar, pillar))
            assertTrue(deps.bounds.collides(parked.avatar, approaching.avatar))
            assertTrue(deps.npcs.all { it.size == 2 && it.hitpoints > 0 })
        }

    @Test
    fun GameTestState
        .`NPCs without an encounter collision override still reserve their full size`() =
        runInjectedGameTest(
            Dependencies::class,
            wave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val anchor = room.normal[CoordGrid(3295, 4245, 0)]
            val ordinary =
                spawnNpc(anchor, deps.npcTypes[nylocas_npcs.big_melee]) {
                    mode = NpcMode.None
                    setHunt(0)
                    aiTimer(0)
                }
            assertEquals(2, ordinary.collisionSize)
            assertEquals(4, occupied(deps, anchor).size)
            deps.npcRepo.del(ordinary, Int.MAX_VALUE)
            assertTrue(occupied(deps, anchor).isEmpty())
        }

    @Test
    fun GameTestState.`a big cannot take an occupied southwest anchor`() =
        runInjectedGameTest(
            Dependencies::class,
            wave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val big = enter(deps, room)
            val blocker = stationary(deps, room, 3300, 4251)
            big.telejump(room.normal[CoordGrid(3300, 4250, 0)])
            repeat(4) {
                advance()
                assertNotEquals(blocker.coords, big.coords)
            }
        }

    @ParameterizedTest
    @CsvSource("1, 0", "0, 1", "1, 1")
    fun `big movement ignores terrain under its other three footprint tiles`(
        offsetX: Int,
        offsetZ: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            wave(),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val big = enter(deps, room)
            val anchor = room.normal[CoordGrid(3300, 4251, 0)]
            val blocked = anchor.translate(offsetX, offsetZ)
            deps.collision.add(blocked, CollisionFlag.LOC)
            big.telejump(room.normal[CoordGrid(3300, 4250, 0)])
            advance()
            assertEquals(anchor, big.coords)
            val terrain = NpcAvatar(1).apply { coords = blocked }
            assertTrue(deps.bounds.collides(big.avatar, terrain))
        }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `removing or killing a big preserves small reservations in its other footprint tiles`(
        kill: Boolean,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            wave(),
            NylocasEncounter::class,
            TheatreRaid::class,
            NpcDeathScript::class,
            NylocasDeaths::class,
        ) { deps ->
            val room = start(deps)
            val big = enter(deps, room)
            val anchor = room.normal[CoordGrid(3300, 4251, 0)]
            big.telejump(anchor)
            val smalls =
                listOf(3301 to 4251, 3300 to 4252, 3301 to 4252).map { (x, z) ->
                    stationary(deps, room, x, z)
                }
            if (kill) {
                big.hitpoints = 0
                big.queueDeath()
                advanceUntil(predicate = { !big.isSlotAssigned }, timeoutTicks = 15)
            } else {
                deps.npcRepo.del(big, Int.MAX_VALUE)
                assertEquals(0, deps.collision[anchor] and CollisionFlag.BLOCK_NPCS)
            }
            for (small in smalls) {
                assertTrue(small.isSlotAssigned && small.hitpoints > 0)
                assertNotEquals(0, deps.collision[small.coords] and CollisionFlag.BLOCK_NPCS)
            }
        }

    private fun occupied(deps: Dependencies, anchor: CoordGrid): Set<CoordGrid> = buildSet {
        for (x in 0..1) for (z in 0..1) {
            val tile = anchor.translate(x, z)
            if (deps.collision[tile] and CollisionFlag.BLOCK_NPCS != 0) add(tile)
        }
    }

    private fun GameTestScope.stationary(deps: Dependencies, room: Region, x: Int, z: Int): Npc =
        spawnNpc(room.normal[CoordGrid(x, z, 0)], deps.npcTypes[nylocas_npcs.small_melee]) {
            mode = NpcMode.None
            setHunt(0)
            aiTimer(0)
        }

    private fun GameTestScope.enter(deps: Dependencies, room: Region): Npc {
        val big = deps.npcs.single()
        advanceUntil(
            predicate = { big.coords == room.normal[CoordGrid(3301, 4248, 0)] },
            timeoutTicks = 15,
        )
        advance()
        return big
    }

    private fun wave(
        pillar: NylocasPillar = NylocasPillar.NorthEast,
        secondBig: Boolean = false,
    ): AbstractModule =
        object : AbstractModule() {
            override fun configure() {
                val spawn = NylocasSpawn(NylocasSize.Big, NylocasStyle.Melee, pillar)
                bind(NylocasWaveTable::class.java)
                    .toInstance(
                        NylocasWaveTable(
                            listOf(
                                NylocasWave(
                                    4,
                                    eastSouth = spawn,
                                    southWest = spawn.takeIf { secondBig },
                                )
                            )
                        )
                    )
            }
        }

    private fun GameTestScope.start(deps: Dependencies): Region {
        val source = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
        val room = createRegion(NylocasRoom.template)
        player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
        advance()
        deps.raid.start(player)
        player.opLoc1(source.copy(coords = room.normal[source.coords]))
        advance()
        player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
        advance(ticks = 5)
        player.telejump(room.normal[NylocasRoom.bridge])
        return room
    }
}

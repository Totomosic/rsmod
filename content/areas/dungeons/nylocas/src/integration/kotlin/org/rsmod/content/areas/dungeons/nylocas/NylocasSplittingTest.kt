package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.npc.queueDeath
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.map.collision.get
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasSplittingTest {
    class Dependencies
    @Inject
    constructor(
        val regions: RegionRegistry,
        val npcs: NpcList,
        val npcRepo: NpcRepository,
        val npcTypes: NpcTypeList,
        val clock: MapClock,
        val events: EventBus,
        val collision: CollisionFlagMap,
        val raid: TheatreRaid,
    )

    @ParameterizedTest
    @CsvSource(
        "Melee, false, false",
        "Ranged, false, false",
        "Magic, false, false",
        "Melee, true, true",
        "Ranged, true, true",
        "Magic, true, true",
    )
    fun `killed bigs produce two children on removal after their full death sequence`(
        style: NylocasStyle,
        aggro: Boolean,
        moving: Boolean,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            table(style, aggro),
            NylocasEncounter::class,
            NylocasDeaths::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val parent = deps.npcs.single()
            advance(ticks = 30)
            if (moving) {
                parent.telejump(room.normal[CoordGrid(3295, 4248, 0)])
                advance()
                parent.aiTimer(0)
                parent.walk(room.normal[CoordGrid(3298, 4248, 0)])
                advance()
                assertTrue(parent.hasMovedThisCycle)
            }
            val lethalCycle = deps.clock.cycle
            var removed = -1
            var born = -1
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (!parent.isSlotAssigned && removed == -1) removed = deps.clock.cycle
                if (deps.npcs.any { it !== parent } && born == -1) born = deps.clock.cycle
            }
            // Both duplicate and mixed styles are legal. Pillar rolls are independent.
            random.next = 2
            random.then = 0
            random.then = 2
            random.then = 3
            parent.hitpoints = 0
            parent.queueDeath()
            advanceUntil(predicate = { !parent.isSlotAssigned }, timeoutTicks = 12)
            assertEquals(if (moving) 7 else 6, removed - lethalCycle)
            assertEquals(removed, born)
            assertEquals(2, deps.npcs.count())
            val children = deps.npcs.toList()
            assertEquals(
                setOf(parent.coords, parent.coords.translate(1, 1)),
                children.map { it.coords }.toSet(),
            )
            assertTrue(
                children.all { it.id == 8344 && it.size == 1 && it.hitpoints == 8 && !it.respawns }
            )
            for (child in children) assertTrue(
                deps.collision[child.coords] and CollisionFlag.BLOCK_NPCS != 0
            )
            // Cross the parent's former expiry without producing a second pair.
            advance(ticks = 25)
            assertEquals(2, deps.npcs.count())
            assertTrue(children.all { it.visType.id == 8344 })
        }

    @ParameterizedTest
    @CsvSource("1, 8", "4, 9", "5, 11")
    fun `expired bigs split at age 55 with independently scaled fresh lifetimes`(
        teamSize: Int,
        hp: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            table(NylocasStyle.Ranged, true),
            NylocasEncounter::class,
            NylocasDeaths::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps, teamSize)
            val parent = deps.npcs.single()
            advance(ticks = 54)
            assertEquals(listOf(parent), deps.npcs.toList())
            val anchor = parent.coords
            random.next = 0
            random.then = 0
            random.then = 1
            random.then = 3
            advance()
            assertFalse(parent.isSlotAssigned)
            val children = deps.npcs.toList()
            assertEquals(2, children.size)
            assertEquals(setOf(anchor, anchor.translate(1, 1)), children.map { it.coords }.toSet())
            assertEquals(setOf(8342, 8343), children.map { it.id }.toSet())
            assertTrue(
                children.all { it.hitpoints == hp && it.baseHitpointsLvl == hp && !it.respawns }
            )
            advance(ticks = 51)
            assertEquals(children.toSet(), deps.npcs.toSet())
            advance()
            assertEquals(0, deps.npcs.count())
            advance(ticks = 6)
            assertEquals(0, deps.npcs.count())
        }

    @Test
    fun GameTestState
        .`a big killed in the lane splits at its final position and children enter the room`() =
        runInjectedGameTest(
            Dependencies::class,
            table(NylocasStyle.Melee, false),
            NylocasEncounter::class,
            NylocasDeaths::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val parent = deps.npcs.single()
            parent.hitpoints = 0
            parent.queueDeath()
            advanceUntil(predicate = { !parent.isSlotAssigned }, timeoutTicks = 12)
            val children = deps.npcs.toList()
            assertEquals(2, children.size)
            assertEquals(
                setOf(parent.coords, parent.coords.translate(1, 1)),
                children.map { it.coords }.toSet(),
            )
            assertTrue(children.all { deps.regions.normalizeCoords(it.coords).z < 4242 })
            advance(ticks = 35)
            assertTrue(children.all { deps.regions.normalizeCoords(it.coords).z >= 4242 })
            assertTrue(children.all { it.visType.id in 8342..8344 })
        }

    @Test
    fun GameTestState
        .`occupied birth tiles still produce both children and small kills never split`() =
        runInjectedGameTest(
            Dependencies::class,
            table(NylocasStyle.Melee, false),
            NylocasEncounter::class,
            NylocasDeaths::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val parent = deps.npcs.single()
            advance(ticks = 54)
            val blocker =
                Npc(
                    deps.npcTypes[nylocas_npcs.incoming(NylocasSize.Small, NylocasStyle.Magic)],
                    parent.coords.translate(1, 1),
                )
            blocker.noneMode()
            blocker.setHunt(0)
            blocker.aiTimer(0)
            deps.npcRepo.add(blocker, Int.MAX_VALUE)
            blocker.respawns = false
            val anchor = parent.coords
            advance()
            val children = deps.npcs.filter { it !== blocker }
            assertEquals(2, children.size)
            assertEquals(setOf(anchor, anchor.translate(1, 1)), children.map { it.coords }.toSet())
            for (child in children) {
                child.hitpoints = 0
                child.queueDeath()
            }
            advance(ticks = 5)
            assertTrue(children.none { it.isSlotAssigned })
            assertEquals(listOf(blocker), deps.npcs.toList())
            advance(ticks = 55)
            assertEquals(listOf(blocker), deps.npcs.toList())
        }

    private fun table(style: NylocasStyle, aggro: Boolean): AbstractModule =
        object : AbstractModule() {
            override fun configure() {
                val spawn =
                    NylocasSpawn(
                        NylocasSize.Big,
                        style,
                        if (aggro) null else NylocasPillar.SouthWest,
                    )
                bind(NylocasWaveTable::class.java)
                    .toInstance(NylocasWaveTable(listOf(NylocasWave(4, southWest = spawn))))
            }
        }

    private fun GameTestScope.start(deps: Dependencies, teamSize: Int = 1): Region {
        val barrier = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
        val room = createRegion(NylocasRoom.template)
        player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
        repeat(teamSize - 1) { registerPlayer(coords = room.normal[NylocasRoom.bridge]) }
        advance()
        deps.raid.start(player)
        player.opLoc1(barrier.copy(coords = room.normal[barrier.coords]))
        advance(ticks = 5)
        player.telejump(room.normal[NylocasRoom.bridge])
        return room
    }
}

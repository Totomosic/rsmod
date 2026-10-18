package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.NpcList
import org.rsmod.game.map.collision.add
import org.rsmod.game.map.collision.remove
import org.rsmod.game.region.Region
import org.rsmod.game.seq.EntitySeq
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasMovementTest {
    class Dependencies
    @Inject
    constructor(
        val regions: RegionRegistry,
        val npcs: NpcList,
        val raid: TheatreRaid,
        val bounds: BoundValidator,
        val collision: CollisionFlagMap,
        val npcRepo: NpcRepository,
        val events: EventBus,
        val clock: MapClock,
    )

    @ParameterizedTest
    @CsvSource(
        "SouthWest, Small",
        "SouthWest, Big",
        "NorthWest, Small",
        "NorthWest, Big",
        "SouthEast, Small",
        "SouthEast, Big",
        "NorthEast, Small",
        "NorthEast, Big",
    )
    fun `an arrival reaches an empty pillar with the real support NPCs present`(
        pillar: NylocasPillar,
        size: NylocasSize,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Magic, pillar)
        state.runInjectedGameTest(
            Dependencies::class,
            table(NylocasWave(4, southWest = spawn)),
            NylocasEncounter::class,
            TheatreRaid::class,
            NylocasPillars::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 35)
            assertEquals(4, deps.npcs.count { it.id == 8358 })
            val npc = deps.npcs.single { it.id in 8342..8353 }
            val target = NpcAvatar(2).apply { coords = room.normal[pillar.source] }
            assertTrue(deps.bounds.touches(npc.avatar, target)) {
                "$size stopped at ${deps.regions.normalizeCoords(npc.coords)} beside $pillar"
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "SouthWest, Small",
        "NorthWest, Small",
        "SouthEast, Small",
        "NorthEast, Small",
        "SouthWest, Big",
        "NorthWest, Big",
        "SouthEast, Big",
        "NorthEast, Big",
    )
    fun `nylos arriving at the same pillar occupy separate tiles`(
        pillar: NylocasPillar,
        size: NylocasSize,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Melee, pillar)
        state.runInjectedGameTest(
            Dependencies::class,
            table(NylocasWave(4, westNorth = spawn), NylocasWave(4, westNorth = spawn)),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            advance(ticks = 35)
            assertEquals(2, deps.npcs.count())
            val target = NpcAvatar(2).apply { coords = room.normal[pillar.source] }
            assertTrue(deps.npcs.all { deps.bounds.touches(it.avatar, target) })
            assertEquals(2, deps.npcs.map { it.coords }.toSet().size)
            val npcs = deps.npcs.toList()
            if (size == NylocasSize.Small)
                assertFalse(deps.bounds.collides(npcs[0].avatar, npcs[1].avatar))
        }
    }

    @ParameterizedTest
    @EnumSource(NylocasPillar::class)
    fun `a full pillar causes local retries until an approached contact is vacated`(
        pillar: NylocasPillar,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(NylocasSize.Small, NylocasStyle.Melee, pillar)
        state.runInjectedGameTest(
            Dependencies::class,
            table(*Array(5) { NylocasWave(if (it == 0) 4 else 1, westNorth = spawn) }),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            advance(ticks = 32)
            val tiles = pillarTiles(pillar).map { room.normal[it] }.toSet()
            // Arrange occupancy explicitly: recordings do not promise that five
            // arrivals from one lane globally fill all four faces' contact tiles.
            val actors = deps.npcs.toList()
            val settled = actors.take(4)
            settled.zip(tiles).forEach { (npc, tile) -> npc.telejump(tile) }
            val waiting = actors.last()
            waiting.telejump(waitingTile(room, pillar))
            settled.forEach { it.addBlockWalkCollision(deps.collision, it.coords) }
            assertEquals(tiles, settled.map { it.coords }.toSet())
            val positions =
                List(10) {
                    val coords = waiting.coords
                    assertEquals(5, deps.npcs.map { it.coords }.toSet().size)
                    assertEquals(tiles, settled.map { it.coords }.toSet())
                    advance()
                    coords
                }
            assertTrue(positions.toSet().size >= 2)
            for (i in 1 until positions.size) {
                val previous = positions[i - 1]
                val current = positions[i]
                assertTrue(previous.chebyshevDistance(current) <= 1)
            }
            val freed = settled.first().coords
            deps.npcRepo.del(settled.first(), Int.MAX_VALUE)
            advance(ticks = 6)
            assertEquals(freed, waiting.coords)
            assertEquals(tiles, deps.npcs.map { it.coords }.toSet())
        }
    }

    @ParameterizedTest
    @EnumSource(NylocasPillar::class)
    fun `a waiting nylo takes the vacancy left by natural expiry`(
        pillar: NylocasPillar,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(NylocasSize.Small, NylocasStyle.Melee, pillar)
        state.runInjectedGameTest(
            Dependencies::class,
            table(
                NylocasWave(4, westNorth = spawn),
                NylocasWave(1, westNorth = spawn),
                NylocasWave(1, westNorth = spawn),
                NylocasWave(1, westNorth = spawn),
                NylocasWave(16, westNorth = spawn),
            ),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            val oldest = deps.npcs.single()
            advance(ticks = 43)
            val tiles = pillarTiles(pillar).map { room.normal[it] }.toSet()
            val actors = deps.npcs.toList()
            actors.take(4).zip(tiles).forEach { (npc, tile) -> npc.telejump(tile) }
            val waiting = actors.last()
            waiting.telejump(waitingTile(room, pillar))
            actors.take(4).forEach { it.addBlockWalkCollision(deps.collision, it.coords) }
            advanceUntil(predicate = { !oldest.isSlotAssigned }, timeoutTicks = 12)
            advance(ticks = 6)
            assertTrue(waiting.isSlotAssigned)
            assertTrue(waiting.coords in tiles)
        }
    }

    @ParameterizedTest
    @EnumSource(NylocasPillar::class)
    fun `pillar arrivals respect occupancy flags and retry when a tile becomes free`(
        pillar: NylocasPillar,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(NylocasSize.Small, NylocasStyle.Magic, pillar)
        state.runInjectedGameTest(
            Dependencies::class,
            table(NylocasWave(4, westNorth = spawn)),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val tiles = pillarTiles(pillar).map { room.normal[it] }
            tiles.forEach { deps.collision.add(it, CollisionFlag.BLOCK_NPCS) }
            val npc = deps.npcs.single()
            repeat(30) {
                assertFalse(npc.coords in tiles)
                advance()
            }
            val freed = tiles.minBy { npc.coords.chebyshevDistance(it) }
            deps.collision.remove(freed, CollisionFlag.BLOCK_NPCS)
            advance(ticks = 10)
            assertEquals(freed, npc.coords)
        }
    }

    @ParameterizedTest
    @EnumSource(NylocasPillar::class)
    fun `simultaneous arrivals from every lane fill the pillar without overlapping`(
        pillar: NylocasPillar,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(NylocasSize.Small, NylocasStyle.Ranged, pillar)
        val wave = NylocasWave(4, spawn, spawn, spawn, spawn, spawn, spawn)
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            repeat(40) {
                advance()
                assertEquals(6, deps.npcs.map { it.coords }.toSet().size)
            }
            val tiles = pillarTiles(pillar).map { room.normal[it] }.toSet()
            assertEquals(tiles, deps.npcs.filter { it.coords in tiles }.map { it.coords }.toSet())
        }
    }

    @Test
    fun GameTestState
        .`a crowded north approach retries locally with straight and diagonal steps`() =
        runInjectedGameTest(
            Dependencies::class,
            table(
                NylocasWave(
                    4,
                    eastSouth =
                        NylocasSpawn(NylocasSize.Big, NylocasStyle.Magic, NylocasPillar.SouthEast),
                )
            ),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            // Blert 1073635f, big64113: the north anchors are occupied while
            // west-face contacts remain available. It keeps retrying north.
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            val npc = deps.npcs.single()
            advance(ticks = 12)
            npc.telejump(room.normal[CoordGrid(3300, 4246, 0)])
            for (x in 3299..3301) deps.collision.add(
                room.normal[CoordGrid(x, 4245, 0)],
                CollisionFlag.BLOCK_NPCS,
            )
            val positions =
                List(20) {
                    val before = deps.regions.normalizeCoords(npc.coords)
                    advance()
                    assertTrue(deps.regions.normalizeCoords(npc.coords).z >= 4246)
                    before
                }
            assertTrue(positions.zipWithNext().any { (a, b) -> a.x != b.x && a.z != b.z })
            assertTrue(positions.zipWithNext().any { (a, b) -> a.x == b.x || a.z == b.z })
            val freed = room.normal[CoordGrid(3300, 4245, 0)]
            deps.collision.remove(freed, CollisionFlag.BLOCK_NPCS)
            advance(ticks = 4)
            assertEquals(freed, npc.coords)
        }

    @ParameterizedTest
    @EnumSource(NylocasSize::class)
    fun `pillar diagonals ignore neighbouring NPCs but respect terrain and destination reservations`(
        size: NylocasSize,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            table(
                NylocasWave(
                    4,
                    eastSouth = NylocasSpawn(size, NylocasStyle.Melee, NylocasPillar.SouthEast),
                )
            ),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            // Small61705, 1073635f ticks88-89; the size-two case exercises the
            // same anchor rule while retaining native-size pillar contact.
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            val npc = deps.npcs.single()
            advance(ticks = 12)
            val source = room.normal[CoordGrid(3299 - npc.size, 4243, 0)]
            val corner = source.translate(1, 0)
            val destination = source.translate(1, 1)
            npc.telejump(source)
            deps.collision.add(corner, CollisionFlag.BLOCK_NPCS or CollisionFlag.LOC)
            advance()
            assertNotEquals(destination, npc.coords)
            npc.telejump(source)
            deps.collision.remove(corner, CollisionFlag.LOC)
            advance()
            assertEquals(destination, npc.coords)
            npc.telejump(source)
            deps.collision.add(destination, CollisionFlag.BLOCK_NPCS)
            advance()
            assertNotEquals(destination, npc.coords)
        }

    private fun waitingTile(room: Region, pillar: NylocasPillar): CoordGrid {
        val contact = pillarTiles(pillar).first()
        val dx =
            when {
                contact.x < pillar.source.x -> -1
                contact.x > pillar.source.x + 1 -> 1
                else -> 0
            }
        val dz =
            when {
                contact.z < pillar.source.z -> -1
                contact.z > pillar.source.z + 1 -> 1
                else -> 0
            }
        return room.normal[contact.translate(dx, dz)]
    }

    private fun pillarTiles(pillar: NylocasPillar): List<CoordGrid> =
        when (pillar) {
            NylocasPillar.SouthWest ->
                listOf(3290 to 4245, 3291 to 4245, 3292 to 4243, 3292 to 4244)
            NylocasPillar.NorthWest ->
                listOf(3290 to 4252, 3291 to 4252, 3292 to 4253, 3292 to 4254)
            NylocasPillar.SouthEast ->
                listOf(3299 to 4243, 3299 to 4244, 3300 to 4245, 3301 to 4245)
            NylocasPillar.NorthEast ->
                listOf(3299 to 4253, 3299 to 4254, 3300 to 4252, 3301 to 4252)
        }.map { (x, z) -> CoordGrid(x, z, 0) }

    @ParameterizedTest
    @CsvSource(
        "WestNorth, Small, NorthWest, 3290, 4249",
        "WestSouth, Small, SouthWest, 3290, 4248",
        "SouthWest, Small, SouthWest, 3295, 4243",
        "SouthEast, Small, SouthEast, 3296, 4243",
        "EastNorth, Small, NorthEast, 3301, 4249",
        "EastSouth, Small, SouthEast, 3301, 4248",
        "WestNorth, Big, NorthWest, 3290, 4249",
        "WestSouth, Big, SouthWest, 3290, 4248",
        "SouthWest, Big, SouthWest, 3295, 4243",
        "SouthEast, Big, SouthEast, 3296, 4243",
        "EastNorth, Big, NorthEast, 3301, 4248",
        "EastSouth, Big, SouthEast, 3301, 4248",
    )
    fun `pillar pathfinding starts on the step immediately after entering the room`(
        slot: NylocasSlot,
        size: NylocasSize,
        pillar: NylocasPillar,
        entryX: Int,
        entryZ: Int,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Melee, pillar)
        val wave =
            NylocasWave(
                4,
                westNorth = spawn.takeIf { slot == NylocasSlot.WestNorth },
                westSouth = spawn.takeIf { slot == NylocasSlot.WestSouth },
                southWest = spawn.takeIf { slot == NylocasSlot.SouthWest },
                southEast = spawn.takeIf { slot == NylocasSlot.SouthEast },
                eastNorth = spawn.takeIf { slot == NylocasSlot.EastNorth },
                eastSouth = spawn.takeIf { slot == NylocasSlot.EastSouth },
            )
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val npc = deps.npcs.single()
            val entry = CoordGrid(entryX, entryZ, 0)
            val source = spawn.source(slot)
            val distance = source.chebyshevDistance(entry)
            // Preserve the straight incoming lane up to its first arena tile.
            repeat(distance) { index ->
                advance()
                val position = deps.regions.normalizeCoords(npc.coords)
                val dx = (entry.x - source.x).compareTo(0) * (index + 1)
                val dz = (entry.z - source.z).compareTo(0) * (index + 1)
                assertEquals(source.translate(dx, dz), position)
            }
            assertEquals(entry, deps.regions.normalizeCoords(npc.coords))
            advance()
            val turned = deps.regions.normalizeCoords(npc.coords)
            if (slot.name.startsWith("South")) {
                assertTrue(turned.x != entry.x) {
                    "$slot $size kept walking north: $entry -> $turned"
                }
            } else {
                assertTrue(turned.z != entry.z) {
                    "$slot $size kept walking straight: $entry -> $turned"
                }
            }
        }
    }

    @Test
    fun GameTestState.`default first wave finishes lanes before walking to assigned pillars`() =
        runInjectedGameTest(
            Dependencies::class,
            table(NylocasWaveTable().waves.first()),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            val entries =
                NylocasWaveTable().waves.first().spawns.associate { (slot, spawn) ->
                    slot.source to (slot to spawn)
                }
            assertEquals(3, deps.npcs.count())
            val definitions =
                deps.npcs.associateWith {
                    entries.getValue(deps.regions.normalizeCoords(it.coords))
                }
            val entered = mutableSetOf<org.rsmod.game.entity.Npc>()
            repeat(40) {
                for ((npc, definition) in definitions) {
                    val (slot, _) = definition
                    val source = deps.regions.normalizeCoords(npc.coords)
                    if (source == slot.entry) entered.add(npc)
                    if (npc !in entered) {
                        if (slot.name.startsWith("South")) assertEquals(slot.source.x, source.x)
                        else assertEquals(slot.source.z, source.z)
                        assertEquals(npc.id, npc.visType.id)
                    }
                }
                advance()
            }
            assertEquals(3, entered.size)
            for ((npc, definition) in definitions) {
                val spawn = definition.second
                val type =
                    if (spawn.pillar == null) nylocas_npcs.fighting(spawn.size, spawn.style)
                    else nylocas_npcs.incoming(spawn.size, spawn.style)
                assertEquals(type.id, npc.visType.id)
                spawn.pillar?.let {
                    val pillar = NpcAvatar(2).apply { coords = room.normal[it.source] }
                    assertTrue(deps.bounds.touches(npc.avatar, pillar))
                }
            }
        }

    @Test
    fun GameTestState.`six slots support every size and style and later rows use their delay`() {
        val small = NylocasSize.Small
        val big = NylocasSize.Big
        val pillar = NylocasPillar.SouthEast
        val wave =
            NylocasWave(
                4,
                westNorth = NylocasSpawn(small, NylocasStyle.Melee, pillar),
                westSouth = NylocasSpawn(big, NylocasStyle.Melee, pillar),
                southWest = NylocasSpawn(small, NylocasStyle.Ranged, pillar),
                southEast = NylocasSpawn(big, NylocasStyle.Ranged, pillar),
                eastNorth = NylocasSpawn(small, NylocasStyle.Magic, pillar),
                eastSouth = NylocasSpawn(big, NylocasStyle.Magic, pillar),
            )
        val later = NylocasWave(6, westSouth = NylocasSpawn(small, NylocasStyle.Magic, pillar))
        runInjectedGameTest(
            Dependencies::class,
            table(wave, later),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            assertEquals((8342..8347).toSet(), deps.npcs.map { it.id }.toSet())
            assertEquals(
                wave.spawns.map { (slot, spawn) -> spawn.source(slot) }.toSet(),
                deps.npcs.map { deps.regions.normalizeCoords(it.coords) }.toSet(),
            )
            for (npc in deps.npcs) assertEquals(if (npc.size == 1) 8 else 16, npc.hitpoints)
            advance(ticks = 5)
            assertEquals(6, deps.npcs.count())
            advance()
            assertEquals(7, deps.npcs.count())
            assertEquals(2, player.vars[tob_varbits.progress_value])
            advance(ticks = 35)
            assertTrue(deps.npcs.all { it.visType.id in 8342..8347 })
        }
    }

    @Test
    fun GameTestState.`arena pathfinding routes around obstacles to a pillar`() {
        val wave =
            NylocasWave(
                4,
                westNorth =
                    NylocasSpawn(NylocasSize.Big, NylocasStyle.Melee, NylocasPillar.NorthEast),
            )
        runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            for (z in 4246..4251) deps.collision.add(
                room.normal[CoordGrid(3295, z, 0)],
                CollisionFlag.LOC,
            )
            val npc = deps.npcs.single()
            advance(ticks = 35)
            val pillar = NpcAvatar(2).apply { coords = room.normal[NylocasPillar.NorthEast.source] }
            assertTrue(deps.bounds.touches(npc.avatar, pillar))
        }
    }

    @ParameterizedTest
    @CsvSource("SouthEast, false", "EastNorth, false", "SouthEast, true", "EastNorth, true")
    fun `wave one pillar approaches match recorded diagonal then straight steps`(
        slot: NylocasSlot,
        fullWave: Boolean,
        state: GameTestState,
    ) {
        // Recorded in duo df03c4dc: south 56526 ticks 14-23, east 56527 ticks 13-22.
        val expected =
            when (slot) {
                NylocasSlot.SouthEast ->
                    listOf(
                        3296 to 4243,
                        3297 to 4244,
                        3298 to 4245,
                        3299 to 4246,
                        3300 to 4247,
                        3301 to 4248,
                        3301 to 4249,
                        3301 to 4250,
                        3301 to 4251,
                        3301 to 4252,
                    )
                NylocasSlot.EastNorth ->
                    listOf(
                        3301 to 4249,
                        3300 to 4250,
                        3299 to 4251,
                        3298 to 4252,
                        3297 to 4253,
                        3296 to 4254,
                        3295 to 4254,
                        3294 to 4254,
                        3293 to 4254,
                        3292 to 4254,
                    )
                else -> error("Not a wave one pillar slot: $slot")
            }.map { (x, z) -> CoordGrid(x, z, 0) }
        val first = NylocasWaveTable().waves.first()
        val wave =
            if (fullWave) first
            else
                NylocasWave(
                    4,
                    southEast = first.southEast.takeIf { slot == NylocasSlot.SouthEast },
                    eastNorth = first.eastNorth.takeIf { slot == NylocasSlot.EastNorth },
                )
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            val spawn = wave.spawns.single { it.first == slot }.second
            val npc = deps.npcs.single { it.coords == room.normal[spawn.source(slot)] }
            advanceUntil(predicate = { npc.coords == room.normal[slot.entry] }, timeoutTicks = 15)
            val actual =
                List(expected.size) {
                    val coords = deps.regions.normalizeCoords(npc.coords)
                    advance()
                    coords
                }
            assertEquals(expected, actual)
            assertEquals(room.normal[expected.last()], npc.coords)
        }
    }

    @Test
    fun GameTestState.`pillar detours can temporarily move away from the destination`() =
        runInjectedGameTest(
            Dependencies::class,
            table(
                NylocasWave(
                    4,
                    westNorth =
                        NylocasSpawn(NylocasSize.Small, NylocasStyle.Magic, NylocasPillar.NorthEast),
                )
            ),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            val npc = deps.npcs.single()
            advanceUntil(
                predicate = { npc.coords == room.normal[NylocasSlot.WestNorth.entry] },
                timeoutTicks = 15,
            )
            advance()
            npc.telejump(room.normal[CoordGrid(3297, 4249, 0)])
            // A U-shaped wall requires a retreat south before approaching the pillar.
            for (x in 3293..3301) {
                deps.collision.add(room.normal[CoordGrid(x, 4251, 0)], CollisionFlag.LOC)
            }
            for (z in 4246..4251) {
                for (x in listOf(3293, 3301)) {
                    deps.collision.add(room.normal[CoordGrid(x, z, 0)], CollisionFlag.LOC)
                }
            }
            advance(ticks = 30)
            val pillar = NpcAvatar(2).apply { coords = room.normal[NylocasPillar.NorthEast.source] }
            assertTrue(deps.bounds.touches(npc.avatar, pillar))
        }

    @Test
    fun GameTestState.`player targets are replaced on departure and stay inside this instance`() {
        val wave = NylocasWave(4, westNorth = NylocasSpawn(NylocasSize.Small, NylocasStyle.Magic))
        runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
            WeaponAttackStylesScript::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            player.telejump(room.normal[CoordGrid(3293, 4249, 0)])
            val other = registerPlayer(coords = room.normal[CoordGrid(3298, 4250, 0)])
            other.setBaseLevel(stats.hitpoints, 99)
            other.setCurrentLevel(stats.hitpoints, 99)
            val secondRoom = createRegion(NylocasRoom.template)
            val outsider = registerPlayer(coords = secondRoom.normal[CoordGrid(3293, 4249, 0)])
            outsider.setBaseLevel(stats.hitpoints, 99)
            outsider.setCurrentLevel(stats.hitpoints, 99)
            advance(ticks = 30)
            assertEquals(99, outsider.hitpoints)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 3)
            val hpAfterDeparture = player.hitpoints
            advance(ticks = 15)
            assertEquals(hpAfterDeparture, player.hitpoints)
            assertTrue(other.hitpoints < 99)
            assertEquals(99, outsider.hitpoints)
        }
    }

    @Test
    fun GameTestState
        .`fighting nylo rolls among every room player each tick with repeats allowed`() {
        val wave = NylocasWave(4, westNorth = NylocasSpawn(NylocasSize.Small, NylocasStyle.Magic))
        runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 15)
            val npc = deps.npcs.single()
            val anchor = room.normal[CoordGrid(3294, 4247, 0)]
            val destination = room.normal[CoordGrid(3300, 4251, 0)]
            player.telejump(destination)
            val candidates = listOf(player) + List(4) { registerPlayer(coords = destination) }
            registerPlayer(coords = room.normal[NylocasRoom.bridge])
            val otherRoom = createRegion(NylocasRoom.template)
            registerPlayer(coords = otherRoom.normal[CoordGrid(3300, 4251, 0)])
            for (index in listOf(4, 4, 0, 2, 1, 3)) {
                npc.telejump(anchor)
                random.next = index
                advance()
                assertEquals(candidates[index].slotId, npc.faceEntity.playerSlot)
                assertTrue(
                    npc.coords.chebyshevDistance(destination) <
                        anchor.chebyshevDistance(destination)
                )
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "Small, Melee",
        "Small, Ranged",
        "Small, Magic",
        "Big, Melee",
        "Big, Ranged",
        "Big, Magic",
    )
    fun `fighting nylo chases only its chosen target and attacks when that target is in reach`(
        size: NylocasSize,
        style: NylocasStyle,
        state: GameTestState,
    ) {
        val wave = NylocasWave(4, westNorth = NylocasSpawn(size, style))
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
            WeaponAttackStylesScript::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 15)
            val npc = deps.npcs.single()
            val anchor = room.normal[CoordGrid(3294, 4247, 0)]
            player.telejump(anchor.translateX(-1))
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            val distant = registerPlayer(coords = room.normal[CoordGrid(3301, 4251, 0)])
            val attacks = mutableListOf<Int>()
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (npc.pendingSequence != EntitySeq.NULL) attacks += npc.pendingSequence.id
            }
            npc.telejump(anchor)
            random.next = 1
            advance()
            assertEquals(distant.slotId, npc.faceEntity.playerSlot)
            assertTrue(attacks.isEmpty())
            assertTrue(npc.coords != anchor)

            npc.telejump(anchor)
            random.next = 0
            advance()
            assertEquals(player.slotId, npc.faceEntity.playerSlot)
            assertEquals(1, attacks.size)
            repeat(3) {
                npc.telejump(anchor)
                random.next = 1
                advance()
                assertEquals(distant.slotId, npc.faceEntity.playerSlot)
                assertEquals(1, attacks.size)
            }
            // The three-tick cooldown has elapsed, but only a reachable chosen target can be hit.
            npc.telejump(anchor)
            random.next = 0
            advance()
            assertEquals(2, attacks.size)
        }
    }

    @ParameterizedTest
    @CsvSource("Small, Ranged", "Small, Magic", "Big, Ranged", "Big, Magic")
    fun `age 26 melee flickers keep chasing players but never attack`(
        size: NylocasSize,
        initialStyle: NylocasStyle,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, initialStyle, styleAt26 = NylocasStyle.Melee)
        state.runInjectedGameTest(
            Dependencies::class,
            table(NylocasWave(4, westNorth = spawn)),
            NylocasEncounter::class,
            TheatreRaid::class,
            WeaponAttackStylesScript::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            val born = deps.clock.cycle - 1
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 16)
            val npc = deps.npcs.single()
            val anchor = room.normal[CoordGrid(3294, 4247, 0)]
            npc.telejump(anchor)
            player.telejump(anchor.translateX(-1))
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            val attacks = mutableListOf<Int>()
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (npc.pendingSequence != EntitySeq.NULL) attacks += npc.pendingSequence.id
            }
            advanceUntil(predicate = { deps.clock.cycle == born + 26 })
            assertTrue(attacks.isNotEmpty())
            attacks.clear()
            // Age 26 itself must already suppress attacks, even with an expired cooldown.
            advance()
            assertEquals(nylocas_npcs.fighting(size, NylocasStyle.Melee).id, npc.visType.id)
            assertTrue(attacks.isEmpty())
            val distant = registerPlayer(coords = room.normal[CoordGrid(3301, 4251, 0)])
            npc.telejump(anchor)
            random.next = 1
            advance()
            assertEquals(distant.slotId, npc.faceEntity.playerSlot)
            assertTrue(
                npc.coords.chebyshevDistance(distant.coords) <
                    anchor.chebyshevDistance(distant.coords)
            )
            distant.telejump(room.normal[NylocasRoom.bridge])
            npc.telejump(anchor)
            // Spend the rest of its active lifetime in melee reach.
            player.telejump(anchor.translateX(-1))
            advance(ticks = size.expiryAnimationAge - (deps.clock.cycle - born))
            assertTrue(attacks.isEmpty())
        }
    }

    @ParameterizedTest
    @CsvSource(
        "Small, Melee",
        "Small, Ranged",
        "Small, Magic",
        "Big, Melee",
        "Big, Ranged",
        "Big, Magic",
    )
    fun `nylos without pillars finish the lane and attack a player in the arena`(
        size: NylocasSize,
        style: NylocasStyle,
        state: GameTestState,
    ) {
        val wave = NylocasWave(4, southWest = NylocasSpawn(size, style))
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
            WeaponAttackStylesScript::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            player.telejump(room.normal[CoordGrid(3294, 4243, 0)])
            advance()
            val npc = deps.npcs.single()
            val animations = mutableListOf<Int>()
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (npc.pendingSequence != EntitySeq.NULL) animations += npc.pendingSequence.id
            }
            advance(ticks = 8)
            assertTrue(deps.regions.normalizeCoords(npc.coords).z < NylocasSlot.SouthWest.entry.z)
            assertEquals(99, player.hitpoints)
            advance(ticks = 35)
            assertEquals(nylocas_npcs.fighting(size, style).id, npc.visType.id)
            assertTrue(player.hitpoints < 99)
            val attackId =
                when (style) {
                    NylocasStyle.Melee -> 8004
                    NylocasStyle.Ranged -> 7999
                    NylocasStyle.Magic -> 7989
                }
            assertEquals(setOf(attackId), animations.toSet())
        }
    }

    @ParameterizedTest
    @CsvSource(
        "Small, Ranged, 2, East, true",
        "Small, Ranged, 3, East, false",
        "Small, Magic, 2, East, true",
        "Small, Magic, 3, East, false",
        "Big, Ranged, 2, East, true",
        "Big, Ranged, 3, East, false",
        "Big, Magic, 2, East, true",
        "Big, Magic, 3, East, false",
        "Small, Ranged, 4, East, false",
        "Small, Magic, 4, East, false",
        "Big, Ranged, 4, East, false",
        "Big, Magic, 4, East, false",
        "Big, Ranged, 2, West, true",
        "Big, Ranged, 3, West, false",
        "Big, Magic, 2, South, true",
        "Big, Magic, 3, South, false",
        "Small, Ranged, 2, Diagonal, true",
        "Small, Ranged, 3, Diagonal, false",
        "Small, Magic, 2, Diagonal, true",
        "Small, Magic, 3, Diagonal, false",
    )
    fun `ranged and magic aggros attack only within two tiles of their footprint`(
        size: NylocasSize,
        style: NylocasStyle,
        distance: Int,
        direction: String,
        expectedAttack: Boolean,
        state: GameTestState,
    ) {
        val wave = NylocasWave(4, westNorth = NylocasSpawn(size, style))
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
            WeaponAttackStylesScript::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 15)
            val npc = deps.npcs.single()
            val anchor = room.normal[CoordGrid(3294, 4248, 0)]
            npc.telejump(anchor)
            val target =
                when (direction) {
                    "East" -> anchor.translateX(npc.size - 1 + distance)
                    "West" -> anchor.translateX(-distance)
                    "South" -> anchor.translateZ(-distance)
                    "Diagonal" -> anchor.translate(distance, distance)
                    else -> error("Unknown direction: $direction")
                }
            player.telejump(target)
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            val attacks = mutableListOf<Int>()
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (npc.pendingSequence != EntitySeq.NULL) attacks += npc.pendingSequence.id
            }
            advance()
            assertEquals(expectedAttack, attacks.isNotEmpty())
            if (!expectedAttack) {
                // Route waypoints may approach diagonals one axis at a time.
                // The attack must begin once the chase brings the target into reach.
                advanceUntil(predicate = { attacks.isNotEmpty() }, timeoutTicks = distance * 2 + 1)
            }
            val attackPosition = npc.coords
            advance(ticks = 4)
            assertEquals(attackPosition, npc.coords)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "EastNorth, Small, NorthWest, 3292, 4254",
        "SouthEast, Small, NorthEast, 3301, 4252",
        "WestNorth, Small, NorthEast, 3299, 4254",
        "EastSouth, Small, SouthEast, 3301, 4245",
        "EastSouth, Big, NorthEast, 3300, 4251",
        "SouthWest, Big, SouthEast, 3298, 4243",
    )
    fun `uncongested pillar arrivals prefer the outer end of their approached face`(
        slot: NylocasSlot,
        size: NylocasSize,
        pillar: NylocasPillar,
        expectedX: Int,
        expectedZ: Int,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Magic, pillar)
        val wave =
            NylocasWave(
                4,
                westNorth = spawn.takeIf { slot == NylocasSlot.WestNorth },
                southWest = spawn.takeIf { slot == NylocasSlot.SouthWest },
                southEast = spawn.takeIf { slot == NylocasSlot.SouthEast },
                eastNorth = spawn.takeIf { slot == NylocasSlot.EastNorth },
                eastSouth = spawn.takeIf { slot == NylocasSlot.EastSouth },
            )
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 35)
            assertEquals(room.normal[CoordGrid(expectedX, expectedZ, 0)], deps.npcs.single().coords)
        }
    }

    @Test
    fun GameTestState.`an occupied outer end allows the inner tile on the approached face`() =
        runInjectedGameTest(
            Dependencies::class,
            table(
                NylocasWave(
                    4,
                    westNorth =
                        NylocasSpawn(NylocasSize.Small, NylocasStyle.Magic, NylocasPillar.NorthEast),
                )
            ),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            player.telejump(room.normal[NylocasRoom.bridge])
            deps.collision.add(room.normal[CoordGrid(3299, 4254, 0)], CollisionFlag.BLOCK_NPCS)
            advance(ticks = 35)
            assertEquals(room.normal[CoordGrid(3299, 4253, 0)], deps.npcs.single().coords)
        }

    private fun table(vararg waves: NylocasWave): AbstractModule =
        object : AbstractModule() {
            override fun configure() {
                bind(NylocasWaveTable::class.java).toInstance(NylocasWaveTable(waves.toList()))
                bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
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
        return room
    }
}

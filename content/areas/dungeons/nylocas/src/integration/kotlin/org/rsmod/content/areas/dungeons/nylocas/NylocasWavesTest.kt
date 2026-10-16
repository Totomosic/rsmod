package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.map.CoordGrid

class NylocasWavesTest {
    class Dependencies
    @Inject
    constructor(val regions: RegionRegistry, val npcs: NpcList, val raid: TheatreRaid)

    @Test
    fun `all wave definitions match the recording backed spawn and style matrix`() {
        val waves = NylocasWaveTable().waves
        val fixtures = fixtures()
        assertEquals(31, waves.size)
        assertEquals(120, fixtures.size)
        assertEquals(43, fixtures.count { it.size == NylocasSize.Big })
        assertEquals(35, fixtures.count { it.pillar == null })
        assertEquals(236, waves.sumOf { it.ticksAfterPrevious })
        for ((index, wave) in waves.withIndex()) {
            val expected = fixtures.filter { it.wave == index + 1 }
            assertEquals(expected.first().delay, wave.ticksAfterPrevious, "wave ${index + 1}")
            assertEquals(expected.size, wave.spawns.size, "wave ${index + 1}")
            for (fixture in expected) {
                val spawn = wave.spawns.single { it.first == fixture.slot }.second
                assertEquals(fixture.source, spawn.source(fixture.slot), fixture.toString())
                assertEquals(fixture.size, spawn.size, fixture.toString())
                assertEquals(fixture.pillar, spawn.pillar, fixture.toString())
                assertEquals(fixture.style5, spawn.styleAt5, fixture.toString())
                assertEquals(fixture.style7, spawn.styleAt7, fixture.toString())
                assertEquals(fixture.style26, spawn.styleAt26, fixture.toString())
                for (age in 0..55) {
                    assertEquals(fixture.style(age), spawn.styleAtAge(age), "$fixture age $age")
                }
            }
        }
        assertEquals(
            setOf(
                18 to NylocasSlot.WestSouth,
                19 to NylocasSlot.EastSouth,
                20 to NylocasSlot.SouthWest,
            ),
            fixtures
                .filter { it.style5 != null && it.style26 == null }
                .map { it.wave to it.slot }
                .toSet(),
        )
    }

    @Test
    fun GameTestState
        .`all 31 batches respect caps and expiry while retaining their recorded styles`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val barrier = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance()
            deps.raid.start(player)
            player.opLoc1(barrier.copy(coords = room.normal[barrier.coords]))
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance(ticks = 5)
            // Occupy the instance without combat damage or incidental player collision.
            player.telejump(room.normal[NylocasRoom.bridge])

            val fixtures = fixtures()
            // Fixed expected schedule with no player kills. Big expiry adds two
            // fresh children before each due check; wave 20 still uses cap 12.
            val spawnOffsets =
                listOf(
                    0,
                    4,
                    8,
                    12,
                    52,
                    68,
                    72,
                    84,
                    120,
                    136,
                    180,
                    188,
                    232,
                    240,
                    284,
                    292,
                    336,
                    348,
                    356,
                    368,
                    384,
                    392,
                    444,
                    456,
                    464,
                    472,
                    476,
                    484,
                    500,
                    564,
                    572,
                )
            val due = spawnOffsets.withIndex().associate { it.value to it.index + 1 }
            val offset = spawnOffsets.last()
            val births = mutableMapOf<Npc, Pair<Int, Fixture>>()
            for (elapsed in 0..(offset + 107)) {
                val wave = due[elapsed]
                val newborn = deps.npcs.filter { it !in births }
                val expected = fixtures.filter { it.wave == wave }
                val parents =
                    births
                        .filter { (_, birth) ->
                            birth.second.size == NylocasSize.Big && elapsed - birth.first == 55
                        }
                        .keys
                assertEquals(
                    expected.size + parents.size * 2,
                    newborn.size,
                    "births at first-wave+$elapsed",
                )
                val splitCoords =
                    parents.flatMap { listOf(it.coords, it.coords.translate(1, 1)) }.toMutableList()
                var laneBirths = 0
                for (npc in newborn) {
                    val source = deps.regions.normalizeCoords(npc.coords)
                    val fixture =
                        expected
                            .singleOrNull {
                                it.source == source &&
                                    it.size ==
                                        (if (npc.size == 1) NylocasSize.Small else NylocasSize.Big)
                            }
                            ?.also { laneBirths++ }
                            ?: run {
                                assertTrue(
                                    splitCoords.remove(npc.coords),
                                    "split at $source on $elapsed",
                                )
                                Fixture(
                                    0,
                                    0,
                                    NylocasSlot.WestSouth,
                                    source,
                                    NylocasSize.Small,
                                    NylocasStyle.entries[npc.id - 8342],
                                    NylocasPillar.SouthWest,
                                    null,
                                    null,
                                    null,
                                )
                            }
                    births[npc] = elapsed to fixture
                    assertEquals(nylocas_npcs.incoming(fixture.size, fixture.initial).id, npc.id)
                }
                assertEquals(expected.size, laneBirths)
                assertTrue(splitCoords.isEmpty())
                if (wave != null) assertEquals(wave, player.vars[tob_varbits.progress_value])
                val present =
                    births
                        .filter { (_, birth) ->
                            elapsed - birth.first < birth.second.size.expiryRemovalAge
                        }
                        .keys
                assertEquals(present, deps.npcs.toSet(), "population at first-wave+$elapsed")
                for ((npc, birth) in births) {
                    val (spawnTick, fixture) = birth
                    val age = elapsed - spawnTick
                    if (age >= fixture.size.expiryRemovalAge) {
                        assertFalse(npc.isSlotAssigned)
                        continue
                    }
                    val style = fixture.style(age)
                    val ordinary = nylocas_npcs.incoming(fixture.size, style).id
                    val aggro = nylocas_npcs.fighting(fixture.size, style).id
                    assertTrue(
                        npc.visType.id in setOf(ordinary, aggro),
                        "$fixture age $age: ${npc.visType.id}",
                    )
                    if (fixture.pillar != null || age <= 7) assertEquals(ordinary, npc.visType.id)
                    if (fixture.pillar == null && age >= 26) assertEquals(aggro, npc.visType.id)
                    assertEquals(fixture.size.hitpoints(1), npc.hitpoints)
                    assertEquals(npc.hitpoints, npc.baseHitpointsLvl)
                }
                advance()
            }
            assertEquals(206, births.size)
            assertEquals(0, deps.npcs.count())
            assertEquals(31, player.vars[tob_varbits.progress_value])
        }

    // Frozen from lane-spawn-targets.csv and positive-HP lifecycle-waves.csv. The
    // reference's outgoing naturalStall is shifted to the following wave here.
    private fun fixtures(): List<Fixture> =
        checkNotNull(javaClass.getResourceAsStream("/nylocas-waves.csv"))
            .bufferedReader()
            .useLines { lines ->
                lines
                    .drop(1)
                    .map { line ->
                        val c = line.split(',')
                        Fixture(
                            c[0].toInt(),
                            c[1].toInt(),
                            NylocasSlot.valueOf(c[2]),
                            CoordGrid(c[3].toInt(), c[4].toInt(), 0),
                            NylocasSize.valueOf(c[5]),
                            NylocasStyle.valueOf(c[6]),
                            if (c[7] == "Player") null else NylocasPillar.valueOf(c[7]),
                            c[8].takeIf { it.isNotEmpty() }?.let(NylocasStyle::valueOf),
                            c[9].takeIf { it.isNotEmpty() }?.let(NylocasStyle::valueOf),
                            c[10].takeIf { it.isNotEmpty() }?.let(NylocasStyle::valueOf),
                        )
                    }
                    .toList()
            }

    private data class Fixture(
        val wave: Int,
        val delay: Int,
        val slot: NylocasSlot,
        val source: CoordGrid,
        val size: NylocasSize,
        val initial: NylocasStyle,
        val pillar: NylocasPillar?,
        val style5: NylocasStyle?,
        val style7: NylocasStyle?,
        val style26: NylocasStyle?,
    ) {
        fun style(age: Int): NylocasStyle {
            var result = initial
            if (age >= 5) style5?.let { result = it }
            if (age >= 7) style7?.let { result = it }
            if (age >= 26) style26?.let { result = it }
            return result
        }
    }
}

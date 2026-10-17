package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.config.refs.components
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid

class NylocasStallTest {
    class Dependencies
    @Inject
    constructor(
        val regions: RegionRegistry,
        val npcs: NpcList,
        val npcRepo: NpcRepository,
        val npcTypes: NpcTypeList,
        val raid: TheatreRaid,
    )

    @ParameterizedTest
    @CsvSource("20, 12, false", "21, 12, true", "21, 23, true", "21, 24, false", "31, 23, true")
    fun `starting late uses the cap appropriate to the selected wave`(
        startingWave: Int,
        population: Int,
        admitted: Boolean,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            table(*Array(31) { single() }),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val source = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
            advance()
            deps.raid.start(player, TheatreRaidOptions(startingWave))
            player.opLoc1(source.copy(coords = room.normal[source.coords]))
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()
            repeat(population) { addBlocker(deps, room) }
            advance(ticks = 4)
            assertEquals(if (admitted) startingWave else 0, player.vars[tob_varbits.progress_value])
            assertEquals(population + if (admitted) 1 else 0, deps.npcs.count())
        }

    @ParameterizedTest
    @CsvSource("11, 2, 17", "12, 1, 12", "13, 1, 13")
    fun `admission checks the population before spawning an entire batch`(
        population: Int,
        expectedWave: Int,
        expectedCount: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            table(single(), batch()),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            repeat(population - 1) { addBlocker(deps, room, big = it % 2 == 0) }
            advance(ticks = 4)
            assertEquals(expectedWave, player.vars[tob_varbits.progress_value])
            assertEquals(expectedCount, deps.npcs.count())
        }

    @Test
    fun GameTestState.`stalls retry every four ticks and natural delays follow the actual spawn`() =
        runInjectedGameTest(
            Dependencies::class,
            table(single(), batch(), single(delay = 12)),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val blockers = List(11) { addBlocker(deps, room) }
            advance(ticks = 8)
            assertEquals(1, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
            deps.npcRepo.del(blockers.first(), Int.MAX_VALUE)
            advance(ticks = 3)
            assertEquals(1, player.vars[tob_varbits.progress_value])
            assertEquals(11, deps.npcs.count())
            advance()
            assertEquals(2, player.vars[tob_varbits.progress_value])
            assertEquals(17, deps.npcs.count())
            blockers.drop(1).forEach { deps.npcRepo.del(it, Int.MAX_VALUE) }
            advance(ticks = 11)
            assertEquals(2, player.vars[tob_varbits.progress_value])
            advance()
            assertEquals(3, player.vars[tob_varbits.progress_value])
            assertEquals(8, deps.npcs.count())
        }

    @Test
    fun GameTestState.`zero HP bodies still occupy cap until they are removed`() =
        runInjectedGameTest(
            Dependencies::class,
            table(single(), single()),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val dying = deps.npcs.single()
            repeat(11) { addBlocker(deps, room) }
            dying.hitpoints = 0
            advance(ticks = 4)
            assertEquals(1, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
            // No default death script is registered; emulate its eventual removal.
            deps.npcRepo.del(dying, Int.MAX_VALUE)
            advance(ticks = 3)
            assertEquals(1, player.vars[tob_varbits.progress_value])
            advance()
            assertEquals(2, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
        }

    @ParameterizedTest
    @CsvSource("Small, 3", "Big, 2")
    fun `expiry bodies count until removal and same tick removals precede admission`(
        size: NylocasSize,
        waveAt52: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            table(batch(size), batch(size), batch(size, delay = 48)),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            advance(ticks = 4)
            assertEquals(2, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
            advance(ticks = 47)
            assertEquals(2, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
            advance()
            assertEquals(waveAt52, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
            if (size == NylocasSize.Big) {
                advance(ticks = 3)
                assertEquals(2, player.vars[tob_varbits.progress_value])
                // Each removed big adds two children: the population grows, so
                // the due wave remains stalled despite the parent removals.
                assertEquals(18, deps.npcs.count())
                advance()
                assertEquals(2, player.vars[tob_varbits.progress_value])
                assertEquals(18, deps.npcs.count())
            }
        }

    @Test
    fun GameTestState.`the cap doubles only after wave twenty spawns`() {
        val waves = List(22) { index -> if (index == 20) batch() else single() }
        runInjectedGameTest(
            Dependencies::class,
            table(*waves.toTypedArray()),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            // Reach wave 19 without a congested room or ageing blockers.
            for (wave in 2..19) {
                deps.npcs.toList().forEach { deps.npcRepo.del(it, Int.MAX_VALUE) }
                advance(ticks = 4)
                assertEquals(wave, player.vars[tob_varbits.progress_value])
            }
            repeat(11) { addBlocker(deps, room) }
            advance(ticks = 4)
            assertEquals(19, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
            deps.npcRepo.del(deps.npcs.last(), Int.MAX_VALUE)
            advance(ticks = 4)
            assertEquals(20, player.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count())
            repeat(11) { addBlocker(deps, room) }
            advance(ticks = 4)
            assertEquals(21, player.vars[tob_varbits.progress_value])
            assertEquals(29, deps.npcs.count())
            // Exactly 24 still stalls; 23 admits the pending wave at the next retry.
            deps.npcs.toList().takeLast(5).forEach { deps.npcRepo.del(it, Int.MAX_VALUE) }
            advance(ticks = 4)
            assertEquals(21, player.vars[tob_varbits.progress_value])
            assertEquals(24, deps.npcs.count())
            deps.npcRepo.del(deps.npcs.last(), Int.MAX_VALUE)
            advance(ticks = 3)
            assertEquals(21, player.vars[tob_varbits.progress_value])
            advance()
            assertEquals(22, player.vars[tob_varbits.progress_value])
            assertEquals(24, deps.npcs.count())
        }
    }

    @Test
    fun GameTestState.`counts are isolated by instance and exclude unrelated NPCs`() =
        runInjectedGameTest(
            Dependencies::class,
            table(single(), single()),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val source = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
            val first = createRegion(NylocasRoom.template)
            player.telejump(first.normal[CoordGrid(3295, 4256, 0)])
            advance()
            val second = createRegion(NylocasRoom.template)
            val other = registerPlayer(coords = second.normal[CoordGrid(3295, 4256, 0)])
            advance()
            deps.raid.start(player)
            deps.raid.start(other)
            player.opLoc1(source.copy(coords = first.normal[source.coords]))
            other.opLoc1(source.copy(coords = second.normal[source.coords]))
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            other.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance(ticks = 5)
            player.telejump(first.normal[NylocasRoom.bridge])
            other.telejump(second.normal[NylocasRoom.bridge])
            repeat(11) { addBlocker(deps, first) }
            repeat(12) { addBlocker(deps, second, nylo = false) }
            advance(ticks = 4)
            assertEquals(1, player.vars[tob_varbits.progress_value])
            assertEquals(2, other.vars[tob_varbits.progress_value])
            assertEquals(12, deps.npcs.count { deps.regions[it.coords]?.uid == first.uid })
            assertEquals(14, deps.npcs.count { deps.regions[it.coords]?.uid == second.uid })
        }

    private fun single(delay: Int = 4) =
        NylocasWave(
            delay,
            southWest = NylocasSpawn(NylocasSize.Small, NylocasStyle.Melee, NylocasPillar.SouthWest),
        )

    private fun batch(size: NylocasSize = NylocasSize.Small, delay: Int = 4): NylocasWave {
        val spawn = NylocasSpawn(size, NylocasStyle.Magic, NylocasPillar.SouthWest)
        return NylocasWave(delay, spawn, spawn, spawn, spawn, spawn, spawn)
    }

    private fun table(vararg waves: NylocasWave): AbstractModule =
        object : AbstractModule() {
            override fun configure() {
                bind(NylocasWaveTable::class.java).toInstance(NylocasWaveTable(waves.toList()))
            }
        }

    // These stand-ins do not run wave behaviour or expire: tests control the precise
    // population, including future split/aggro forms that bypass the lane scheduler.
    private fun addBlocker(
        deps: Dependencies,
        room: Region,
        big: Boolean = false,
        nylo: Boolean = true,
    ): Npc {
        val type =
            if (!nylo) org.rsmod.api.config.refs.npcs.cow
            else
                nylocas_npcs.fighting(
                    if (big) NylocasSize.Big else NylocasSize.Small,
                    NylocasStyle.Melee,
                )
        val npc = Npc(deps.npcTypes[type], room.normal[CoordGrid(3294, 4248, 0)])
        npc.mode = NpcMode.None
        npc.setHunt(0)
        npc.aiTimer(0)
        deps.npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        return npc
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

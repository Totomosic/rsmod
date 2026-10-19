package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.testing.GameTestState
import org.rsmod.events.EventBus
import org.rsmod.game.entity.NpcList
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

class NylocasWaveReportTest {
    class Dependencies
    @Inject
    constructor(val raid: TheatreRaid, val npcs: NpcList, val events: EventBus)

    @Test
    fun GameTestState
        .`room sends live kills cleanup and completion then resets only on next room start`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasEncounter::class,
            TheatreRaid::class,
            NpcHitScript::class,
            NylocasDeaths::class,
        ) { deps ->
            val barrier = placeMapLoc(CoordGrid(3295, 4255), nylocas_locs.barrier)
            val received = mutableListOf<List<String>>()
            deps.events.subscribeUnbound(GameLifecycle.EndCycle::class.java) {
                received +=
                    client
                        .filterIsInstance<MessageGame>()
                        .filter { it.message.startsWith("LNW1 ") }
                        .map { it.message.split(" ") }
            }
            fun reports() = received
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[CoordGrid(3295, 4256)])
            advance()
            deps.raid.start(player, TheatreRaidOptions(startingWave = 31))
            player.opLoc1(barrier.copy(coords = room.normal[barrier.coords]))
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advanceUntil(predicate = { reports().any { it[2] == "starting" } })
            val id = reports().last()[1]
            advanceUntil(predicate = { player.vars[tob_varbits.progress_value] == 31 })
            assertEquals("cleanup", reports().last()[2])
            assertEquals("6", reports().last()[4])
            val targets = deps.npcs.filter { it.id in 8342..8353 }
            targets.forEachIndexed { index, npc ->
                npc.queueHit(
                    player,
                    1,
                    HitType.Melee,
                    npc.hitpoints,
                    NpcHitModifier { isThrall = index == 0 },
                )
                if (index == 0) npc.queueHit(player, delay = 1, HitType.Melee, damage = 1)
            }
            advanceUntil(
                predicate = { reports().lastOrNull()?.get(2) == "complete" },
                timeoutTicks = 12,
            )
            val final = reports().last()
            assertEquals(id, final[1])
            assertEquals("0", final[4])
            assertEquals("1", final[7]) // Thrall lethal hit survives processor damage clipping.
            assertEquals(listOf("6", "6", "0", "0"), final.subList(8, 12))
            assertEquals(1, final.subList(15, 18).sumOf(String::toInt))
            player.telejump(CoordGrid(3222, 3218))
            advance()
            assertEquals(final, reports().last())
            val next = createRegion(NylocasRoom.template)
            player.telejump(next.normal[NylocasRoom.bridge])
            advance()
            deps.raid.start(player)
            advance()
            assertEquals(final, reports().last())
            deps.raid.beginRoom(next)
            advance()
            assertNotEquals(id, reports().last()[1])
            assertEquals("starting", reports().last()[2])
            assertTrue(reports().last().drop(3).all { it == "0" })
        }

    @Test
    fun `kills ignore misses count individual damage splats and retain lethal style and thrall credit`() {
        val stats = NylocasWaveStats()
        val small = NylocasWaveStats.Damage(NylocasSize.Small)
        stats.impact(small, NylocasStyle.Melee, 0, 8, false, 1)
        stats.impact(small, NylocasStyle.Melee, 8, 0, true, 2)
        // Overkill style is the nylo's style at death, not the style of a later hit.
        stats.impact(small, NylocasStyle.Magic, 0, 0, false, 2)
        stats.impact(small, NylocasStyle.Magic, 0, 0, false, 2)
        val big = NylocasWaveStats.Damage(NylocasSize.Big)
        stats.impact(big, NylocasStyle.Ranged, 8, 8, false, 3)
        stats.impact(big, NylocasStyle.Magic, 8, 0, false, 4)
        stats.impact(big, NylocasStyle.Magic, 0, 0, true, 5)
        assertEquals(
            "waves 0 2 0 0 1 1 1 1 0 1 0 1 1 0 0 0 0 0 0 0 0 0 0 0",
            stats.payload(2, "waves"),
        )
    }

    @Test
    fun `split distributions change at waves 21 and 31 and reset for a fresh report`() {
        val stats = NylocasWaveStats()
        stats.wave = 20
        stats.split(NylocasStyle.Melee)
        stats.wave = 21
        stats.split(NylocasStyle.Ranged)
        stats.wave = 30
        stats.split(NylocasStyle.Magic)
        stats.wave = 31
        stats.split(NylocasStyle.Melee)
        stats.split(NylocasStyle.Magic)
        assertEquals(listOf(1, 0, 0, 0, 1, 1, 1, 0, 1), fields(stats).takeLast(9))
        assertEquals(List(24) { 0 }, fields(NylocasWaveStats()))
    }

    @Test
    fun `saved stalls count admission checks not bodies and expire after the removal tick`() {
        val stats = NylocasWaveStats()
        repeat(2) { stats.removed(overkilled(stats, NylocasSize.Small), 10) }
        stats.admission(11, 12, 10)
        stats.admission(11, 12, 11)
        stats.admission(12, 12, 12)
        assertEquals(1, fields(stats)[2])
        assertEquals(1, fields(stats)[3])
    }

    @Test
    fun `early big splits offset saved cap slots and a stalled check cannot also be saved`() {
        val stats = NylocasWaveStats()
        stats.removed(overkilled(stats, NylocasSize.Small), 10)
        stats.removed(overkilled(stats, NylocasSize.Big), 10)
        stats.admission(11, 12, 10)
        stats.admission(12, 12, 10)
        assertEquals(1, fields(stats)[2])
        assertEquals(0, fields(stats)[3])
        stats.removed(overkilled(stats, NylocasSize.Small), 11)
        stats.admission(23, 24, 11)
        assertEquals(1, fields(stats)[3])
    }

    private fun overkilled(stats: NylocasWaveStats, size: NylocasSize): NylocasWaveStats.Damage =
        NylocasWaveStats.Damage(size).also {
            stats.impact(it, NylocasStyle.Melee, 8, 0, false, 1)
            stats.impact(it, NylocasStyle.Melee, 0, 0, false, 1)
        }

    private fun fields(stats: NylocasWaveStats): List<Int> =
        stats.payload(0, "waves").split(" ").drop(1).map(String::toInt)
}

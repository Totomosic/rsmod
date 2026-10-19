package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.varps
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.testing.GameTestState
import org.rsmod.events.EventBus
import org.rsmod.game.entity.NpcList
import org.rsmod.game.hit.DamageRoll
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

class NylocasWaveReportTest {
    class Dependencies
    @Inject
    constructor(
        val raid: TheatreRaid,
        val npcs: NpcList,
        val events: EventBus,
        val attacks: PlayerAttackManager,
        val modifier: StandardNpcHitModifier,
    )

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
                        .filter { it.message.startsWith("LNW3 ") }
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
    fun GameTestState
        .`queued rolls retain their launch gear distribution through impact and clipping`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasEncounter::class,
            TheatreRaid::class,
            NpcHitScript::class,
            NylocasDeaths::class,
        ) { deps ->
            val barrier = placeMapLoc(CoordGrid(3295, 4255), nylocas_locs.barrier)
            val reports = mutableListOf<List<String>>()
            deps.events.subscribeUnbound(GameLifecycle.EndCycle::class.java) {
                reports +=
                    client
                        .filterIsInstance<MessageGame>()
                        .filter { it.message.startsWith("LNW3 ") }
                        .map { it.message.split(" ") }
            }
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[CoordGrid(3295, 4256)])
            advance()
            deps.raid.start(player, TheatreRaidOptions(startingWave = 31))
            player.opLoc1(barrier.copy(coords = room.normal[barrier.coords]))
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advanceUntil(predicate = { player.vars[tob_varbits.progress_value] == 31 })
            val target = deps.npcs.first { it.id in 8342..8353 }
            val rolled = deps.attacks.rollDamage(player, target, HitType.Melee, 15)
            target.queueHit(player, 2, HitType.Melee, rolled, deps.modifier)
            VarPlayerIntMapSetter.set(player, varps.com_maxhit, 100)
            advance(ticks = 3)
            assertEquals("1", reports.last()[33]) // Post-cap small attempts.
            assertEquals("500000", reports.last()[35]) // Eight lethal rolls out of sixteen.
            assertEquals(if (rolled >= 8) "1" else "0", reports.last()[34])
            assertEquals("0", reports.last()[39]) // Distribution was measured, not excluded.
        }

    @Test
    fun `kills ignore misses distinguish attacks and retain lethal style and thrall credit`() {
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
            stats.payload(2, "waves").split(" ").take(25).joinToString(" "),
        )
    }

    @Test
    fun `split distributions change at waves 21 and 29 and reset for a fresh report`() {
        val stats = NylocasWaveStats()
        stats.wave = 20
        stats.split(NylocasStyle.Melee)
        stats.wave = 21
        stats.split(NylocasStyle.Ranged)
        stats.wave = 28
        stats.split(NylocasStyle.Magic)
        stats.wave = 29
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

    @Test
    fun `luck uses first damaging roll and separates phase size and unsupported hits`() {
        val stats = NylocasWaveStats()
        stats.wave = 20
        val small = NylocasWaveStats.Damage(NylocasSize.Small)
        val big = NylocasWaveStats.Damage(NylocasSize.Big)
        stats.impact(small, NylocasStyle.Melee, 0, 8, false, 1, DamageRoll(0..15, true))
        stats.impact(small, NylocasStyle.Melee, 4, 4, false, 2, DamageRoll(0..15, true))
        // A later hit with different gear must not change this Nylo's expectation.
        stats.impact(small, NylocasStyle.Melee, 4, 0, false, 3, DamageRoll(100..100))
        stats.impact(big, NylocasStyle.Melee, 16, 0, false, 3, DamageRoll(16..16))
        stats.wave = 21
        stats.impact(
            NylocasWaveStats.Damage(NylocasSize.Small),
            NylocasStyle.Magic,
            8,
            0,
            false,
            4,
            DamageRoll(0..15),
        )
        stats.wave = 31
        stats.impact(
            NylocasWaveStats.Damage(NylocasSize.Big),
            NylocasStyle.Melee,
            8,
            8,
            false,
            5,
            DamageRoll(0..15, true),
        )
        stats.impact(
            NylocasWaveStats.Damage(NylocasSize.Small),
            NylocasStyle.Melee,
            8,
            0,
            true,
            5,
            DamageRoll(0..15, true),
        )
        stats.impact(NylocasWaveStats.Damage(NylocasSize.Small), NylocasStyle.Melee, 8, 0, false, 5)
        assertEquals(
            listOf(1L, 0L, 500000L, 1L, 1L, 1000000L, 1L, 1L, 533333L, 1L, 0L, 0L, 2L),
            stats.payload(0, "cleanup").split(" ").drop(25).take(13).map(String::toLong),
        )
    }

    @Test
    fun `damage roll probabilities preserve zero floors minimum hits and guaranteed hits`() {
        assertEquals(0.5, DamageRoll(0..15, true).chanceAtLeast(8))
        assertEquals(8.0 / 15, DamageRoll(0..15).chanceAtLeast(8))
        assertEquals(1.0, DamageRoll(8..15).chanceAtLeast(8))
        assertEquals(0.0, DamageRoll(0..7, true).chanceAtLeast(8))
        assertEquals(1.0, DamageRoll(8..8).chanceAtLeast(8))
        assertEquals(0.0, DamageRoll(0..0).chanceAtLeast(8))
        assertEquals(1.0, DamageRoll(0..0, true).chanceAtLeast(1))
    }

    @Test
    fun `one attack can kill with multiple splats across ticks but separate attacks cannot combine`() {
        val stats = NylocasWaveStats()
        val attack = NylocasAttack(mutableListOf(DamageRoll(1..5), DamageRoll(1..5)))
        val first = NylocasWaveStats.Damage(NylocasSize.Small)
        stats.impact(first, NylocasStyle.Melee, 4, 4, false, 1, attack = attack)
        stats.impact(first, NylocasStyle.Melee, 4, 0, false, 2, attack = attack)
        val second = NylocasWaveStats.Damage(NylocasSize.Small)
        stats.impact(second, NylocasStyle.Melee, 4, 4, false, 3, attack = attack)
        stats.impact(
            second,
            NylocasStyle.Melee,
            4,
            0,
            false,
            3,
            attack = NylocasAttack(mutableListOf(DamageRoll(4..4))),
        )
        assertEquals(listOf(2, 1), fields(stats).subList(5, 7))
        val luck = stats.payload(0, "complete").split(" ").drop(25).map(String::toLong)
        assertEquals(listOf(2L, 1L, 480000L), luck.take(3))
        assertEquals(760000L, luck.last())
    }

    @Test
    fun `attack probability sums independent splats before conditioning on damage`() {
        assertEquals(
            0.24,
            NylocasAttack(mutableListOf(DamageRoll(1..5), DamageRoll(1..5))).chanceAtLeast(8)!!,
            1e-12,
        )
        assertEquals(
            1.0 / 3,
            NylocasAttack(mutableListOf(DamageRoll(0..1), DamageRoll(0..1))).chanceAtLeast(2)!!,
            1e-12,
        )
        assertEquals(
            1.0,
            NylocasAttack(mutableListOf(DamageRoll(0..0, true), DamageRoll(0..0, true)))
                .chanceAtLeast(2)!!,
            1e-12,
        )
        assertEquals(0.0, NylocasAttack(mutableListOf(DamageRoll(0..0))).chanceAtLeast(8))
        assertEquals(
            0.5,
            NylocasAttack(mutableListOf(DamageRoll(0..15, true))).chanceAtLeast(8)!!,
            1e-12,
        )
    }

    @Test
    fun `luck percentile uses each attack probability and splits ties equally`() {
        val stats = NylocasWaveStats()
        val first = NylocasWaveStats.Damage(NylocasSize.Small)
        val second = NylocasWaveStats.Damage(NylocasSize.Small)
        val low = NylocasAttack(mutableListOf(DamageRoll(1..10)))
        val high = NylocasAttack(mutableListOf(DamageRoll(1..10)))
        stats.impact(first, NylocasStyle.Melee, 1, 9, false, 1, attack = low)
        stats.impact(second, NylocasStyle.Melee, 1, 1, false, 1, attack = high)
        assertEquals("45000", stats.payload(0, "waves").split(" ").last())
        stats.impact(first, NylocasStyle.Melee, 9, 0, false, 2, attack = low)
        assertEquals("500000", stats.payload(0, "waves").split(" ").last())
        stats.impact(second, NylocasStyle.Melee, 1, 0, false, 2, attack = high)
        assertEquals("955000", stats.payload(0, "waves").split(" ").last())
        val certain = NylocasWaveStats()
        certain.impact(
            NylocasWaveStats.Damage(NylocasSize.Small),
            NylocasStyle.Melee,
            1,
            1,
            false,
            1,
            DamageRoll(1..1),
        )
        certain.impact(
            NylocasWaveStats.Damage(NylocasSize.Small),
            NylocasStyle.Melee,
            1,
            0,
            false,
            1,
            DamageRoll(1..1),
        )
        assertEquals("500000", certain.payload(0, "waves").split(" ").last())
    }

    private fun overkilled(stats: NylocasWaveStats, size: NylocasSize): NylocasWaveStats.Damage =
        NylocasWaveStats.Damage(size).also {
            stats.impact(it, NylocasStyle.Melee, 8, 0, false, 1)
            stats.impact(it, NylocasStyle.Melee, 0, 0, false, 1)
        }

    private fun fields(stats: NylocasWaveStats): List<Int> =
        stats.payload(0, "waves").split(" ").drop(1).take(24).map(String::toInt)
}

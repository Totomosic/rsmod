package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.NpcList
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

class NylocasBossTest {
    class Dependencies
    @Inject
    constructor(
        val boss: NylocasBoss,
        val raid: TheatreRaid,
        val npcs: NpcList,
        val repo: NpcRepository,
        val regions: RegionRegistry,
        val modifier: NpcHitModifier,
        val events: EventBus,
        val clock: MapClock,
        val maxHits: MaxHitFormulae,
    )

    @ParameterizedTest
    @CsvSource("1,1875", "2,1875", "3,1875", "4,2187", "5,2500")
    fun `native boss drops then starts melee for nine ticks before ten tick phases`(
        scale: Int,
        hp: Int,
        state: GameTestState,
    ) =
        state.runBossTest { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            repeat(scale - 1) { registerPlayer(coords = player.coords) }
            advance()
            deps.raid.start(player)
            val spawn = deps.clock.cycle
            val boss = deps.boss.spawn(room, scale, spawn)
            assertEquals(room.normal[CoordGrid(3294, 4247)], boss.coords)
            assertEquals(4, boss.size)
            assertEquals(hp, boss.hitpoints)
            assertEquals(hp, boss.baseHitpointsLvl)
            assertFalse(boss.respawns)
            val forms = mutableMapOf<Int, Int>()
            deps.events.subscribeUnbound(GameLifecycle.EndCycle::class.java) {
                forms[deps.clock.cycle - 1 - spawn] = boss.visType.id
            }
            advance(ticks = 43)
            assertEquals(8354, forms[1])
            assertEquals(8355, forms[2])
            assertEquals(8355, forms[10])
            for (switch in listOf(11, 21, 31, 41)) {
                assertTrue(forms.getValue(switch) in 8355..8357)
                assertNotEquals(forms[switch - 1], forms[switch])
                for (tick in switch until minOf(switch + 10, 43)) assertEquals(
                    forms[switch],
                    forms[tick],
                )
            }
            assertEquals(1, player.vars[tob_varbits.progress_type])
            assertEquals(1000, player.vars[tob_varbits.progress_value])
            assertEquals(1000, player.vars[tob_varbits.progress_max])
        }

    @Test
    fun GameTestState.`boss uses all three native attacks and protection prayers block them`() =
        runBossTest { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[CoordGrid(3294, 4243)])
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            for (prayer in
                listOf(
                    varbits.protect_from_melee,
                    varbits.protect_from_missiles,
                    varbits.protect_from_magic,
                )) VarPlayerIntMapSetter.set(player, prayer, 1)
            advance()
            deps.raid.start(player)
            val boss = deps.boss.spawn(room, 1, deps.clock.cycle)
            val hits = mutableListOf<PlayerHitEvents.Impact>()
            deps.events.subscribeUnbound(PlayerHitEvents.Impact::class.java) {
                if (hit.isFromNpc && hit.resolveNpcSource(deps.npcs) === boss) hits += this
            }
            val maximums = mutableMapOf<Int, Int>()
            deps.events.subscribeUnbound(GameLifecycle.EndCycle::class.java) {
                maximums[boss.visType.id] =
                    when (boss.visType.id) {
                        8355 -> deps.maxHits.getMeleeMaxHit(boss, player, null)
                        8356 -> deps.maxHits.getMagicMaxHit(boss, player)
                        8357 -> deps.maxHits.getRangedMaxHit(boss, player)
                        else -> 0
                    }
            }
            advance(ticks = 8)
            assertNotEquals(room.normal[CoordGrid(3294, 4247)], boss.coords)
            advance(ticks = 92)
            assertEquals(
                setOf(HitType.Melee, HitType.Ranged, HitType.Magic),
                hits.map { it.hit.type }.toSet(),
            )
            assertTrue(hits.all { it.damage == 0 })
            assertEquals(99, player.stats[stats.hitpoints])
            for (id in 8355..8357) assertTrue(maximums.getValue(id) >= 40)
        }

    @Test
    fun GameTestState
        .`matching damage hurts and wrong styles heal and reflect without permanent nulling`() =
        runBossTest { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            advance()
            deps.raid.start(player)
            val boss = deps.boss.spawn(room, 1, deps.clock.cycle)
            advance(ticks = 2)
            boss.queueHit(player, 1, HitType.Melee, 30, deps.modifier)
            advance()
            assertEquals(1845, boss.hitpoints)
            boss.queueHit(player, 1, HitType.Magic, 20, deps.modifier)
            advance()
            assertEquals(1865, boss.hitpoints)
            assertEquals(79, player.stats[stats.hitpoints])
            // Healing clamps to max HP; reflection retains the full rolled damage.
            boss.queueHit(player, 1, HitType.Ranged, 20, deps.modifier)
            advance()
            assertEquals(1875, boss.hitpoints)
            assertEquals(59, player.stats[stats.hitpoints])
            boss.queueHit(player, 1, HitType.Melee, 10, deps.modifier)
            advance()
            assertEquals(1865, boss.hitpoints)
        }

    @Test
    fun GameTestState.`projectiles use impact style and each splat is handled separately`() =
        runBossTest { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            advance()
            deps.raid.start(player)
            val boss = deps.boss.spawn(room, 1, deps.clock.cycle)
            advance(ticks = 9)
            boss.hitpoints = 1800
            // All launch in melee, land after its first transformation.
            boss.queueHit(player, 3, HitType.Melee, 10, deps.modifier)
            boss.queueHit(player, 3, HitType.Melee, 5, deps.modifier)
            advance(ticks = 3)
            assertTrue(boss.visType.id != 8355)
            assertEquals(1815, boss.hitpoints)
            assertEquals(84, player.stats[stats.hitpoints])
            val matching =
                NylocasStyle.entries.single { nylocas_npcs.boss(it).id == boss.visType.id }
            boss.queueHit(player, 1, matching.hitType, 8, deps.modifier)
            advance()
            assertEquals(1807, boss.hitpoints)
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `cleanup waits for final bodies and drops on the wave phase sixteen to nineteen ticks later`(
        residue: Int,
        state: GameTestState,
    ) =
        state.runBossTest { deps ->
            val source = placeMapLoc(CoordGrid(3295, 4255), nylocas_locs.barrier)
            val room = createRegion(NylocasRoom.template)
            val barrier = source.copy(coords = room.normal[source.coords])
            player.telejump(room.normal[CoordGrid(3295, 4256)])
            advance()
            deps.raid.start(player, TheatreRaidOptions(startingWave = 31))
            player.opLoc1(barrier)
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance(ticks = 5)
            assertEquals(31, player.vars[tob_varbits.progress_value])
            val phase = deps.clock.cycle % 4
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = residue)
            val bodies = deps.npcs.filter { it.id in 8342..8353 }
            // Merely setting zero HP cannot start cleanup while bodies remain.
            bodies.forEach { it.hitpoints = 0 }
            advance(ticks = 20)
            assertTrue(deps.npcs.none { it.id in 8354..8357 })
            bodies.forEach { deps.repo.del(it, Int.MAX_VALUE) }
            // Deletion between engine cycles is observed at the next EndCycle.
            val cleanup = deps.clock.cycle + 1
            val expected = cleanup + 16 + Math.floorMod(phase - cleanup, 4)
            advanceUntil(predicate = { deps.npcs.any { it.id == 8354 } }, timeoutTicks = 25)
            assertEquals(expected, deps.clock.cycle)
            advance(ticks = 2)
            assertEquals(1, deps.npcs.count { it.visType.id == 8355 })
            advance(ticks = 25)
            assertEquals(1, deps.npcs.count { it.id in 8354..8357 })
        }

    @Test
    fun GameTestState
        .`lethal matching damage removes boss and completes room after death animation`() =
        runBossTest { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance()
            deps.raid.start(player)
            val boss = deps.boss.spawn(room, 1, deps.clock.cycle)
            advance(ticks = 2)
            boss.queueHit(player, 1, HitType.Melee, 9999, deps.modifier)
            advance()
            assertEquals(0, boss.hitpoints)
            assertEquals(1, player.vars[tob_varbits.progress_type])
            advanceUntil(predicate = { !boss.isSlotAssigned }, timeoutTicks = 10)
            assertEquals(0, player.vars[tob_varbits.progress_type])
            advance(ticks = 30)
            assertTrue(deps.npcs.none { it.id in 8354..8357 })
        }

    private fun GameTestState.runBossTest(body: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            CombatModule,
            NylocasEncounter::class,
            TheatreRaid::class,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            testBody = body,
        )

    private object CombatModule : AbstractModule() {
        override fun configure() {
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }
}

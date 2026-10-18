package org.rsmod.content.other.special.attacks

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import net.rsprot.protocol.game.outgoing.zone.payload.MapProjAnimV2
import net.rsprot.protocol.game.outgoing.zone.payload.SoundArea
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.npcs
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.walktriggers
import org.rsmod.api.freeze.FreezeScript
import org.rsmod.api.freeze.isFreezeImmune
import org.rsmod.api.freeze.isFrozen
import org.rsmod.api.freeze.remainingFreezeCycles
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.isProtectionPrayerDisabled
import org.rsmod.api.player.quiver
import org.rsmod.api.player.righthand
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.specials.SpecialAttack
import org.rsmod.api.specials.SpecialAttackModule as ApiSpecialAttackModule
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.content.other.special.attacks.configs.special_objs
import org.rsmod.content.other.special.attacks.configs.special_spots
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.spot.EntitySpotanim
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.walktrig.isType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript

@Execution(ExecutionMode.SAME_THREAD)
class CombatSpecialAttacksTest {
    @ParameterizedTest
    @CsvSource(
        "false, false, 1, 2, 3",
        "false, false, 3, 3, 3",
        "false, false, 9, 4, 5",
        "true, false, 1, 2, 3",
        "true, false, 3, 3, 3",
        "true, false, 9, 4, 5",
        "true, true, 1, 2, 3",
        "true, true, 3, 3, 3",
        "true, true, 9, 4, 5",
    )
    fun `bow specials apply both arrows on their respective projectile ticks`(
        darkBow: Boolean,
        dragonArrows: Boolean,
        distance: Int,
        firstTick: Int,
        secondTick: Int,
        state: GameTestState,
    ) =
        state.runSpecialsTest { deps ->
            val npc = spawnDummy()
            player.placeAt(npc.coords.translateZ(distance))
            player.stats[stats.ranged] = 99
            val weapon = if (darkBow) objs.dark_bow else special_objs.magic_shortbow
            player.righthand = InvObj(weapon)
            player.quiver = InvObj(if (dragonArrows) objs.dragon_arrow else objs.rune_arrow, 10)
            random.next = HIT_ROLL
            random.then = 5
            random.then = HIT_ROLL
            random.then = 5
            random.then = 4
            random.then = 4
            val special = deps.registry[InvObj(weapon)] as SpecialAttack.Ranged
            val attack =
                CombatAttack.Ranged(
                    InvObj(weapon),
                    RangedAttackType.Standard,
                    RangedAttackStyle.Rapid,
                )
            player.withProtectedAccess { assertTrue(special.attack(this, npc, attack)) }
            val projectiles =
                checkNotNull(deps.zones[ZoneKey.from(player.coords)])
                    .filterIsInstance<MapProjAnimV2>()
            assertEquals(
                setOf(firstTick - 1, secondTick - 1),
                projectiles.map { it.endTime / 30 }.toSet(),
            )
            val hits = mutableListOf<Pair<Hit, Int>>()
            val iterator = checkNotNull(npc.queueList.iterator())
            while (iterator.hasNext()) {
                val queue = iterator.next()
                val hit = queue.args
                if (hit is Hit) hits += hit to queue.remainingCycles
            }
            iterator.cleanUp()
            assertEquals(listOf(firstTick, secondTick), hits.map { it.second })
            assertTrue(hits.all { it.first.damage > 0 })
            for (tick in 1..secondTick) {
                advance()
                val expectedDamage = hits.filter { it.second <= tick }.sumOf { it.first.damage }
                assertEquals(DUMMY_HITPOINTS - expectedDamage, npc.hitpoints)
            }
        }

    @Test
    fun GameTestState.`claw special plays all three sound phases in the world`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            random.next = HIT_ROLL
            useMeleeSpecial(deps, special_objs.dragon_claws, npc, MeleeAttackType.Slash)
            val sounds =
                deps.zones[ZoneKey.from(player.coords)]?.filterIsInstance<SoundArea>().orEmpty()
            assertEquals(
                listOf(4138 to 0, 4140 to 15, 4141 to 30),
                sounds.map { it.id to it.delay },
            )
        }

    @Test
    fun GameTestState
        .`godsword specials always hit their full boosted maximum on combat dummies`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy(combatDummy = true)
            val base =
                deps.attacks.calculateMeleeMaxHit(
                    player,
                    npc,
                    MeleeAttackType.Slash,
                    MeleeAttackStyle.Accurate,
                    1.1,
                )
            val maximum = (base * 1.25).toInt()
            assertTrue(maximum > 1)
            random.next = MISS_ROLL
            useMeleeSpecial(deps, special_objs.armadyl_godsword, npc, MeleeAttackType.Slash)
            advance()
            assertEquals(DUMMY_HITPOINTS - maximum, npc.hitpoints)
        }

    @Test
    fun GameTestState.`dragon claws use the maximum accurate cascade on combat dummies`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy(combatDummy = true)
            val maximum =
                deps.attacks.calculateMeleeMaxHit(
                    player,
                    npc,
                    MeleeAttackType.Slash,
                    MeleeAttackStyle.Accurate,
                    1.0,
                )
            val first = maximum - 1
            val second = first / 2
            val third = second / 2
            assertTrue(first > 1)
            random.next = MISS_ROLL
            useMeleeSpecial(deps, special_objs.dragon_claws, npc, MeleeAttackType.Slash)
            advance()
            assertEquals(DUMMY_HITPOINTS - first - second, npc.hitpoints)
            advance()
            assertEquals(DUMMY_HITPOINTS - first - second - third - (third + 1), npc.hitpoints)
        }

    @Test
    fun GameTestState.`spec weapons are registered with their energy requirements`() =
        runSpecialsTest { deps ->
            fun assertEnergy(obj: ObjType, expected: Int) {
                val special = deps.registry[InvObj(obj)]
                assertNotNull(special)
                val energy =
                    when (special) {
                        is SpecialAttack.Melee -> special.energyInHundreds
                        is SpecialAttack.Ranged -> special.energyInHundreds
                        else -> error("Unexpected special type for $obj: $special")
                    }
                assertEquals(expected, energy)
            }

            assertEnergy(special_objs.dragon_dagger, 250)
            assertEnergy(special_objs.dragon_dagger_p, 250)
            assertEnergy(special_objs.dragon_dagger_p_plus, 250)
            assertEnergy(special_objs.dragon_dagger_p_plus_plus, 250)
            assertEnergy(special_objs.abyssal_whip, 500)
            assertEnergy(special_objs.volcanic_abyssal_whip, 500)
            assertEnergy(special_objs.frozen_abyssal_whip, 500)
            assertEnergy(special_objs.dragon_scimitar, 550)
            assertEnergy(special_objs.dragon_scimitar_or, 550)
            assertEnergy(special_objs.dragon_mace, 250)
            assertEnergy(special_objs.armadyl_godsword, 500)
            assertEnergy(special_objs.armadyl_godsword_or, 500)
            assertEnergy(special_objs.bandos_godsword, 500)
            assertEnergy(special_objs.bandos_godsword_or, 500)
            assertEnergy(special_objs.saradomin_godsword, 500)
            assertEnergy(special_objs.saradomin_godsword_or, 500)
            assertEnergy(special_objs.zamorak_godsword, 500)
            assertEnergy(special_objs.zamorak_godsword_or, 500)
            assertEnergy(special_objs.dragon_warhammer, 500)
            assertEnergy(special_objs.dragon_warhammer_cr, 500)
            assertEnergy(special_objs.dragon_claws, 500)
            assertEnergy(special_objs.dragon_claws_cr, 500)
            assertEnergy(special_objs.magic_shortbow, 550)
            assertEnergy(special_objs.magic_shortbow_i, 500)
            assertEnergy(special_objs.dinhs_bulwark, 500)
            assertEnergy(special_objs.dinhs_blazing_bulwark, 500)
        }

    @Test
    fun GameTestState.`dragon dagger hits twice`() = runSpecialsTest { deps ->
        val npc = spawnDummy()
        random.next = HIT_ROLL
        random.then = 10
        random.then = HIT_ROLL
        random.then = 7
        useMeleeSpecial(deps, special_objs.dragon_dagger, npc, MeleeAttackType.Stab)
        advance(ticks = 1)
        assertEquals(DUMMY_HITPOINTS - 17, npc.hitpoints)
    }

    @Test
    fun GameTestState.`shared melee ranged and powered staff damage rolls clamp zero to one`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            random.next = 0
            assertEquals(
                1,
                deps.attacks.rollMeleeMaxHit(
                    player,
                    npc,
                    MeleeAttackType.Slash,
                    MeleeAttackStyle.Accurate,
                    1.0,
                ),
            )
            random.next = 0
            assertEquals(
                1,
                deps.attacks.rollRangedMaxHit(
                    player,
                    npc,
                    RangedAttackType.Standard,
                    RangedAttackStyle.Accurate,
                    1.0,
                    0,
                ),
            )
            random.next = 0
            assertEquals(
                1,
                deps.attacks.rollStaffMaxHit(player, npc, baseMaxHit = 45, multiplier = 1.0),
            )
        }

    @Test
    fun GameTestState.`dragon warhammer drains defence by 30 percent on hit`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            random.next = HIT_ROLL
            random.then = 5
            useMeleeSpecial(deps, special_objs.dragon_warhammer, npc, MeleeAttackType.Crush)
            assertEquals(DUMMY_DEFENCE - (DUMMY_DEFENCE * 30) / 100, npc.defenceLvl)
        }

    @Test
    fun GameTestState.`dragon warhammer does not drain defence on miss`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            random.next = MISS_ROLL
            useMeleeSpecial(deps, special_objs.dragon_warhammer, npc, MeleeAttackType.Crush)
            assertEquals(DUMMY_DEFENCE, npc.defenceLvl)
        }

    @Test
    fun GameTestState.`bandos godsword drains defence then strength by damage dealt`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            random.next = HIT_ROLL
            random.then = DUMMY_DEFENCE + 7
            useMeleeSpecial(deps, special_objs.bandos_godsword, npc, MeleeAttackType.Slash)
            assertEquals(0, npc.defenceLvl)
            assertEquals(DUMMY_STRENGTH - 7, npc.strengthLvl)
        }

    @Test
    fun GameTestState.`godsword accurate zero damage roll deals one and freezes`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            random.next = HIT_ROLL
            random.then = 0
            useMeleeSpecial(deps, special_objs.zamorak_godsword, npc, MeleeAttackType.Slash)
            assertTrue(npc.isFrozen())
            advance()
            assertEquals(DUMMY_HITPOINTS - 1, npc.hitpoints)
        }

    @Test
    fun GameTestState.`dragon claws cascade from the second hit`() = runSpecialsTest { deps ->
        val npc = spawnDummy()
        random.next = MISS_ROLL
        random.then = HIT_ROLL
        random.then = 20
        useMeleeSpecial(deps, special_objs.dragon_claws, npc, MeleeAttackType.Slash)
        advance(ticks = 1)
        assertEquals(DUMMY_HITPOINTS - 20, npc.hitpoints)
        advance(ticks = 1)
        // 0, 20, 10, 11
        assertEquals(DUMMY_HITPOINTS - 41, npc.hitpoints)
    }

    @Test
    fun GameTestState.`zamorak godsword freezes the target on a successful hit`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            advance()
            random.next = HIT_ROLL
            random.then = 5
            useMeleeSpecial(deps, special_objs.zamorak_godsword, npc, MeleeAttackType.Slash)
            assertTrue(npc.isFrozen())
            assertEquals(ICE_CLEAVE_FREEZE_CYCLES, npc.remainingFreezeCycles())
            val spotanims = npc.pendingSpotanims.map { EntitySpotanim(it).id }
            assertEquals(listOf(special_spots.ice_barrage_impact.id), spotanims)

            advance(ticks = ICE_CLEAVE_FREEZE_CYCLES - 1)
            assertTrue(npc.isFrozen())
            advance()
            assertFalse(npc.isFrozen())
            assertTrue(npc.isFreezeImmune())
        }

    @Test
    fun GameTestState.`zamorak godsword does not freeze on a miss`() = runSpecialsTest { deps ->
        val npc = spawnDummy()
        advance()
        random.next = MISS_ROLL
        useMeleeSpecial(deps, special_objs.zamorak_godsword_or, npc, MeleeAttackType.Slash)
        assertFalse(npc.isFrozen())
        assertTrue(npc.pendingSpotanims.isEmpty())
    }

    @Test
    fun GameTestState.`zamorak godsword does not refresh an existing freeze`() =
        runSpecialsTest { deps ->
            val npc = spawnDummy()
            advance()
            random.next = HIT_ROLL
            random.then = 5
            useMeleeSpecial(deps, special_objs.zamorak_godsword, npc, MeleeAttackType.Slash)
            advance(ticks = 10)

            random.next = HIT_ROLL
            random.then = 5
            useMeleeSpecial(deps, special_objs.zamorak_godsword, npc, MeleeAttackType.Slash)
            assertEquals(ICE_CLEAVE_FREEZE_CYCLES - 10, npc.remainingFreezeCycles())
        }

    @Test
    fun GameTestState.`zamorak godsword freeze stops a player target from walking`() =
        runSpecialsTest { deps ->
            val target = spawnPlayerTarget()
            advance()
            random.next = HIT_ROLL
            random.then = 5
            useMeleeSpecial(deps, special_objs.zamorak_godsword, target, MeleeAttackType.Slash)
            assertTrue(target.isFrozen())
            assertTrue(target.walkTrigger.isType(walktriggers.pvp_frozen))

            val start = target.coords
            target.moveGameClick(start.translateX(3))
            advance()
            assertEquals(start, target.coords)
        }

    @Test
    fun GameTestState.`dragon scimitar disables protection prayers on a successful hit`() =
        runSpecialsTest { deps ->
            val target = spawnPlayerTarget()
            target.setVarBit(varbits.protect_from_melee, 1)
            target.setVarBit(varbits.piety, 1)
            target.overheadIcon = constants.overhead_protect_from_melee
            advance()

            random.next = HIT_ROLL
            random.then = 3
            useMeleeSpecial(deps, special_objs.dragon_scimitar, target, MeleeAttackType.Slash)
            assertEquals(0, target.vars[varbits.protect_from_melee])
            assertEquals(1, target.vars[varbits.piety])
            assertNull(target.overheadIcon)
            assertTrue(target.isProtectionPrayerDisabled())

            advance(ticks = SEVER_DISABLE_CYCLES - 1)
            assertTrue(target.isProtectionPrayerDisabled())
            advance()
            assertFalse(target.isProtectionPrayerDisabled())
        }

    @Test
    fun GameTestState.`dragon scimitar does not disable protection prayers on a miss`() =
        runSpecialsTest { deps ->
            val target = spawnPlayerTarget()
            target.setVarBit(varbits.protect_from_melee, 1)
            advance()

            random.next = MISS_ROLL
            useMeleeSpecial(deps, special_objs.dragon_scimitar_or, target, MeleeAttackType.Slash)
            assertEquals(1, target.vars[varbits.protect_from_melee])
            assertFalse(target.isProtectionPrayerDisabled())
        }

    private fun GameTestScope.spawnPlayerTarget(): Player {
        val target = registerPlayer(CoordGrid(0, 50, 50, 0, 0))
        target.setBaseLevel(stats.prayer, 99)
        target.setCurrentLevel(stats.prayer, 99)
        player.placeAt(target.coords.translateZ(1))
        player.stats[stats.attack] = 99
        player.stats[stats.strength] = 99
        return target
    }

    private fun GameTestScope.spawnDummy(combatDummy: Boolean = false): Npc {
        val type =
            if (combatDummy)
                npcTypeFactory.create(id = npcs.combat_dummy.id) {
                    name = "Max hit dummy"
                    hitpoints = DUMMY_HITPOINTS
                    defence = DUMMY_DEFENCE
                    defaultMode = NpcMode.None
                }
            else dummy
        val npc = spawnNpc(CoordGrid(0, 50, 50, 0, 0), type)
        player.placeAt(npc.coords.translateZ(1))
        player.stats[stats.attack] = 99
        player.stats[stats.strength] = 99
        return npc
    }

    private fun GameTestScope.useMeleeSpecial(
        deps: SpecialsTestDependencies,
        weapon: ObjType,
        target: PathingEntity,
        type: MeleeAttackType,
    ) {
        val special = deps.registry[InvObj(weapon)]
        check(special is SpecialAttack.Melee) { "Expected melee special for $weapon: $special" }
        val attack =
            CombatAttack.Melee(
                InvObj(weapon),
                type,
                MeleeAttackStyle.Accurate,
                CombatStance.Stance1,
            )
        player.withProtectedAccess { special.attack(this, target, attack) }
    }

    private fun GameTestState.runSpecialsTest(
        testBody: GameTestScope.(SpecialsTestDependencies) -> Unit
    ) =
        runInjectedGameTest(
            SpecialsTestDependencies::class,
            SpecialsTestModule,
            specialAttackScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            FreezeScript::class,
            WeaponAttackStylesScript::class,
            testBody = testBody,
        )

    class SpecialsTestDependencies
    @Inject
    constructor(
        val registry: SpecialAttackRegistry,
        val attacks: PlayerAttackManager,
        val zones: ZoneUpdateMap,
    )

    private object SpecialsTestModule : AbstractModule() {
        override fun configure() {
            install(ApiSpecialAttackModule())
            install(SpecialAttackModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }

    private companion object {
        /** A roll of `0` always passes the accuracy check (`hitChance > roll`). */
        private const val HIT_ROLL = 0

        /** The highest possible roll, which fails any accuracy check below 100%. */
        private const val MISS_ROLL = 9_999

        private const val DUMMY_HITPOINTS = 200
        private const val DUMMY_DEFENCE = 50
        private const val DUMMY_STRENGTH = 60

        private const val ICE_CLEAVE_FREEZE_CYCLES = 32
        private const val SEVER_DISABLE_CYCLES = 8

        /** `SpecialAttackScript` is internal to `api:specials`; it registers every special map. */
        @Suppress("UNCHECKED_CAST")
        private val specialAttackScript: KClass<out PluginScript> =
            Class.forName("org.rsmod.api.specials.scripts.SpecialAttackScript").kotlin
                as KClass<out PluginScript>

        private val dummy =
            npcTypeFactory.create {
                name = "Dummy"
                size = 1
                hitpoints = DUMMY_HITPOINTS
                defence = DUMMY_DEFENCE
                strength = DUMMY_STRENGTH
                defaultMode = NpcMode.None
            }
    }
}

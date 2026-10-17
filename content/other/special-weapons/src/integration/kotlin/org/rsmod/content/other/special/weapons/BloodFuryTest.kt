package org.rsmod.content.other.special.weapons

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.api.weapons.attack
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.content.other.special.weapons.melee.ScytheOfViturWeapons
import org.rsmod.content.other.special.weapons.melee.SulphurBladesWeapons
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid

class BloodFuryTest {
    @Test
    fun GameTestState.`ranged and magic damage cannot trigger blood fury`() =
        runFuryTest { deps, target ->
            deps.combat.queueRangedDamage(player, target, null, 20, 1)
            deps.combat.queueMagicHit(player, target, null, 20, 0, 1)
            assertEquals(30, player.hitpoints)
        }

    @ParameterizedTest
    @CsvSource("0,6", "1,0", "2,0", "3,0", "4,0")
    fun `one of five rolls heals thirty percent of melee damage`(
        roll: Int,
        heal: Int,
        state: GameTestState,
    ) =
        state.runFuryTest { deps, target ->
            random.next = roll
            deps.combat.queueMeleeHit(player, target, 20)
            assertEquals(30 + heal, player.hitpoints)
        }

    @ParameterizedTest
    @CsvSource("1,0", "3,0", "4,1", "19,5", "20,6")
    fun `healing rounds down per splat`(damage: Int, heal: Int, state: GameTestState) =
        state.runFuryTest { deps, target ->
            random.next = 0
            deps.combat.queueMeleeHit(player, target, damage)
            assertEquals(30 + heal, player.hitpoints)
        }

    @ParameterizedTest
    @CsvSource("0,0,0,5", "0,1,0,3", "1,0,1,2", "1,1,1,0")
    fun `scythe rolls healing independently on all three splats`(
        first: Int,
        second: Int,
        third: Int,
        heal: Int,
        state: GameTestState,
    ) =
        state.runFuryTest { deps, target ->
            with(deps.scythe) { deps.repository.register(deps.weapons) }
            val obj = special_weapon_objs.scythe_of_vitur
            player.worn[Wearpos.RightHand.slot] = InvObj(obj)
            for (damage in listOf(8, 8, 4)) {
                random.next = 0 // Accuracy.
                random.then = damage
            }
            for (proc in listOf(first, second, third)) random.then = proc
            player.withProtectedAccess {
                val weapon =
                    checkNotNull(deps.registry.getMelee(player.worn[Wearpos.RightHand.slot]))
                weapon.attack(
                    this,
                    target,
                    CombatAttack.Melee(
                        InvObj(obj),
                        MeleeAttackType.Slash,
                        MeleeAttackStyle.Accurate,
                        CombatStance.Stance1,
                    ),
                )
            }
            assertEquals(30 + heal, player.hitpoints)
        }

    @Test
    fun GameTestState.`sulphur blades roll independently for both splats`() =
        runFuryTest { deps, target ->
            with(deps.sulphur) { deps.repository.register(deps.weapons) }
            val obj = special_weapon_objs.sulphur_blades
            player.worn[Wearpos.RightHand.slot] = InvObj(obj)
            repeat(2) {
                random.next = 0
                random.then = 8
            }
            random.then = 1
            random.then = 0
            player.withProtectedAccess {
                val weapon =
                    checkNotNull(deps.registry.getMelee(player.worn[Wearpos.RightHand.slot]))
                weapon.attack(
                    this,
                    target,
                    CombatAttack.Melee(
                        InvObj(obj),
                        MeleeAttackType.Slash,
                        MeleeAttackStyle.Accurate,
                        CombatStance.Stance1,
                    ),
                )
            }
            assertEquals(32, player.hitpoints)
        }

    @Test
    fun GameTestState.`healing uses the modified splat and is not capped by enemy remaining hp`() =
        runFuryTest { deps, target ->
            target.hitpoints = 1
            eventBus.subscribeKeyed(NpcHitEvents.Modify::class.java, target.id.toLong()) {
                hit.damage = 19
            }
            random.next = 0
            deps.combat.queueMeleeHit(player, target, 100)
            assertEquals(35, player.hitpoints)
        }

    @Test
    fun GameTestState.`zero damage and non player melee hits do not heal`() =
        runFuryTest { deps, target ->
            deps.combat.queueMeleeHit(player, target, 0)
            target.queueHit(
                player,
                1,
                HitType.Melee,
                20,
            ) // Thralls bypass the player's melee attack path.
            advance()
            assertEquals(30, player.hitpoints)
        }

    @Test
    fun GameTestState.`amulet must be equipped rather than merely carried`() =
        runFuryTest { deps, target ->
            player.inv[0] = player.worn[Wearpos.Front.slot]
            player.worn[Wearpos.Front.slot] = null
            deps.combat.queueMeleeHit(player, target, 20)
            assertEquals(30, player.hitpoints)
        }

    @ParameterizedTest
    @CsvSource("98,99", "99,99", "110,110", "0,0")
    fun `healing caps at base hp preserves overhealing and cannot revive`(
        hp: Int,
        expected: Int,
        state: GameTestState,
    ) =
        state.runFuryTest { deps, target ->
            player.setCurrentLevel(stats.hitpoints, hp)
            random.next = 0
            deps.combat.queueMeleeHit(player, target, 20)
            assertEquals(expected, player.hitpoints)
        }

    @Test
    fun GameTestState.`melee hits against players can heal too`() = runFuryTest { deps, _ ->
        val other = registerPlayer(player.coords.translateZ(1))
        random.next = 0
        deps.combat.queueMeleeHit(player, other, 20)
        assertEquals(36, player.hitpoints)
    }

    private fun GameTestState.runFuryTest(body: GameTestScope.(Dependencies, Npc) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            NpcHitScript::class,
            WeaponAttackStylesScript::class,
        ) { deps ->
            player.teleport(CoordGrid(8000, 8000, 0))
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 30)
            player.setCurrentLevel(stats.attack, 99)
            player.setCurrentLevel(stats.strength, 150)
            player.worn[Wearpos.Front.slot] = InvObj(findObjType { it.id == 24780 }.single())
            val target =
                spawnNpc(
                    player.coords.translateZ(1),
                    npcTypeFactory.create {
                        name = "Blood Fury target"
                        size = 3
                        hitpoints = 1000
                        defence = 1
                        defaultMode = NpcMode.None
                        op[1] = "Attack"
                    },
                )
            body(deps, target)
        }

    class Dependencies
    @Inject
    constructor(
        val combat: PlayerAttackManager,
        val scythe: ScytheOfViturWeapons,
        val sulphur: SulphurBladesWeapons,
        val repository: WeaponRepository,
        val registry: WeaponRegistry,
        val weapons: WeaponAttackManager,
    )

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(WeaponModule())
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }
}

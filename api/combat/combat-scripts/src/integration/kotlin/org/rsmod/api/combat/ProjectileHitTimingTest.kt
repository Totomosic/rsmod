package org.rsmod.api.combat

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.zone.payload.MapProjAnimV2
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.player.quiver
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.inv.InvObj
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

class ProjectileHitTimingTest {
    @ParameterizedTest
    @CsvSource("0, 1", "29, 1", "30, 2", "59, 2", "60, 3", "89, 3", "90, 4")
    fun `ranged and magic defaults include a queue tick after projectile travel`(
        clientDelay: Int,
        impactTick: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(Dependencies::class, TestModule, NpcHitScript::class) { deps ->
            val targets =
                List(7) {
                    spawnNpc(
                        player.coords.translateZ(it + 1),
                        npcTypeFactory.create {
                            hitpoints = 200
                            defaultMode = NpcMode.None
                        },
                    )
                }
            val impacts = mutableListOf<Int>()
            eventBus.subscribeKeyed(NpcHitEvents.Impact::class.java, targets.first().id.toLong()) {
                if (npc in targets) impacts += hit.damage
            }
            player.withProtectedAccess {
                deps.weapons.queueRangedHit(this, targets[0], null, 1, clientDelay)
                deps.specials.queueRangedHit(this, targets[1], null, 1, clientDelay)
                deps.weapons.queueMagicHit(this, targets[2], 1, clientDelay)
                deps.specials.queueMagicHit(this, targets[3], 1, clientDelay)
                deps.spells.queueMagicHit(this, targets[4], objs.spell_wind_strike, 1, clientDelay)
                deps.weapons.queueSplashHit(this, targets[5], clientDelay)
                deps.spells.queueSplashHit(this, targets[6], objs.spell_wind_strike, clientDelay)
            }
            for (tick in 1..impactTick) {
                advance()
                assertTrue(
                    targets.take(5).all { it.hitpoints == if (tick >= impactTick) 199 else 200 }
                )
                assertTrue(targets.drop(5).all { it.hitpoints == 200 })
                val expectedImpacts = if (tick >= impactTick) 7 else 0
                assertEquals(expectedImpacts, impacts.size)
            }
            assertEquals(listOf(0, 0, 1, 1, 1, 1, 1), impacts.sorted())
        }

    @Test
    fun GameTestState.`close ranged hits land one tick after melee`() =
        runInjectedGameTest(Dependencies::class, TestModule, NpcHitScript::class) { deps ->
            val type =
                npcTypeFactory.create {
                    hitpoints = 200
                    defaultMode = NpcMode.None
                }
            val melee = spawnNpc(player.coords.translateZ(1), type)
            val ranged = spawnNpc(player.coords.translateZ(2), type)
            player.withProtectedAccess {
                deps.weapons.queueMeleeHit(this, melee, 1)
                deps.weapons.queueRangedHit(this, ranged, null, 1, clientDelay = 30)
            }
            advance()
            assertEquals(199, melee.hitpoints)
            assertEquals(200, ranged.hitpoints)
            advance()
            assertEquals(199, ranged.hitpoints)
        }

    @ParameterizedTest
    @CsvSource(
        "bow, 1, 2",
        "bow, 2, 2",
        "bow, 3, 3",
        "bow, 9, 4",
        "crossbow, 1, 2",
        "crossbow, 2, 2",
        "crossbow, 3, 3",
        "crossbow, 9, 4",
        "twisted, 1, 2",
        "twisted, 2, 2",
        "twisted, 3, 3",
        "twisted, 9, 4",
        "dart, 1, 2",
        "dart, 5, 2",
        "dart, 6, 3",
        "dart, 9, 3",
    )
    fun `ranged damage retains its original delay at every distance`(
        weapon: String,
        distance: Int,
        impactTick: Int,
        state: GameTestState,
    ) {
        for (playerTarget in listOf(false, true)) {
            state.runInjectedGameTest(
                Dependencies::class,
                TestModule,
                NpcHitScript::class,
                PlayerHitScript::class,
                WeaponAttackStylesScript::class,
                WeaponAttackTypesScript::class,
            ) { deps ->
                val coords = CoordGrid(3200, 3200)
                val target =
                    if (playerTarget) {
                        registerPlayer(coords).also {
                            it.setBaseLevel(stats.hitpoints, 99)
                            it.setCurrentLevel(stats.hitpoints, 99)
                        }
                    } else {
                        spawnNpc(
                            coords,
                            npcTypeFactory.create {
                                hitpoints = 200
                                magic = 50
                                op[1] = "Attack"
                                defaultMode = NpcMode.None
                            },
                        )
                    }
                player.placeAt(coords.translateZ(distance))
                player.setBaseLevel(stats.ranged, 99)
                player.setCurrentLevel(stats.ranged, 99)
                val item =
                    when (weapon) {
                        "bow" -> objs.magic_shortbow
                        "crossbow" -> objs.dragon_hunter_crossbow
                        "twisted" -> objs.twisted_bow
                        else -> objs.rune_dart
                    }
                player.righthand = InvObj(item, if (weapon == "dart") 10 else 1)
                player.quiver =
                    InvObj(if (weapon == "crossbow") objs.runite_bolts else objs.rune_arrow, 10)
                advance(ticks = 20)
                random.next = 4 // No ground-ammo drop.
                random.then = 0 // Accuracy.
                random.then = 1
                val beforeHp =
                    when (target) {
                        is Npc -> target.hitpoints
                        is Player -> target.hitpoints
                    }
                val type =
                    when (weapon) {
                        "crossbow" -> RangedAttackType.Heavy
                        "dart" -> RangedAttackType.Light
                        else -> RangedAttackType.Standard
                    }
                val attack =
                    CombatAttack.Ranged(
                        checkNotNull(player.righthand),
                        type,
                        RangedAttackStyle.Longrange,
                    )
                player.withProtectedAccess {
                    when (target) {
                        is Npc -> deps.pvn.attack(this, target, attack)
                        is Player -> deps.pvp.attack(this, target, attack)
                    }
                }
                val projectile =
                    checkNotNull(deps.zones[ZoneKey.from(player.coords)])
                        .filterIsInstance<MapProjAnimV2>()
                        .single()
                assertEquals(impactTick - 1, projectile.endTime / 30)
                if (impactTick > 1) advance(ticks = impactTick - 1)
                assertEquals(
                    beforeHp,
                    when (target) {
                        is Npc -> target.hitpoints
                        is Player -> target.hitpoints
                    },
                )
                advance()
                assertEquals(
                    beforeHp - 1,
                    when (target) {
                        is Npc -> target.hitpoints
                        is Player -> target.hitpoints
                    },
                )
            }
        }
    }

    internal class Dependencies
    @Inject
    constructor(
        val pvn: PvNCombat,
        val pvp: PvPCombat,
        val zones: ZoneUpdateMap,
        val weapons: WeaponAttackManager,
        val specials: SpecialAttackManager,
        val spells: SpellAttackManager,
    )

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(MagicSpellModule())
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
        }
    }
}

package org.rsmod.content.other.special.weapons

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.PlayerAttackCheck
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.quiver
import org.rsmod.api.player.righthand
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid

class SeekingDragonArrowsTest {
    @Test
    fun GameTestState.`anguish has rupture combat bonuses`() = runArrowTest { deps, _ ->
        player.righthand = null
        player.quiver = null
        player.worn[Wearpos.Front.slot] = InvObj(objs.necklace_of_anguish)
        val bonuses = deps.bonuses.calculate(player)
        assertEquals(20, bonuses.offRange)
        assertEquals(8, bonuses.rangedStr)
        assertEquals(3, bonuses.prayer)
        assertEquals(
            List(11) { 0 },
            listOf(
                bonuses.offStab,
                bonuses.offSlash,
                bonuses.offCrush,
                bonuses.offMagic,
                bonuses.defStab,
                bonuses.defSlash,
                bonuses.defCrush,
                bonuses.defMagic,
                bonuses.defRange,
                bonuses.meleeStr,
                bonuses.magicDmg,
            ),
        )
    }

    @Test
    fun GameTestState.`dragon arrows keep sixty strength and add twenty bow accuracy`() =
        runArrowTest { deps, _ ->
            val arrow = deps.objTypes[objs.dragon_arrow]
            assertEquals(20, arrow.param(params.attack_ranged))
            assertEquals(60, arrow.param(params.ranged_strength))
            player.quiver = null
            val withoutArrows = deps.bonuses.calculate(player)
            player.quiver = InvObj(objs.dragon_arrow, 100)
            val withArrows = deps.bonuses.calculate(player)
            assertEquals(withoutArrows.offRange + 20, withArrows.offRange)
            assertEquals(withoutArrows.rangedStr + 60, withArrows.rangedStr)
        }

    @Test
    fun GameTestState.`equipped dragon arrows give no accuracy to other weapon types`() =
        runArrowTest { deps, _ ->
            for (weapon in
                listOf(
                    objs.dragon_hunter_crossbow,
                    objs.abyssal_whip,
                    objs.crystal_bow,
                    objs.black_chinchompa,
                )) {
                player.righthand = InvObj(weapon)
                player.quiver = null
                val withoutArrows = deps.bonuses.offensiveRangedBonus(player)
                player.quiver = InvObj(objs.dragon_arrow, 100)
                assertEquals(withoutArrows, deps.bonuses.offensiveRangedBonus(player))
            }
        }

    @ParameterizedTest
    @CsvSource("0,3", "1,3", "2,3", "3,3", "4,4")
    fun `successful low rolls are raised to three`(roll: Int, damage: Int, state: GameTestState) =
        state.runArrowTest { deps, target ->
            random.next = 0 // Successful accuracy roll.
            random.then = roll
            assertEquals(damage, deps.combat.rollRangedDamage(player, target, attack()))
        }

    @Test
    fun GameTestState.`seeking arrows can still miss`() = runArrowTest { deps, target ->
        target.defenceLvl = 10000
        random.next = 9999
        assertEquals(0, deps.combat.rollRangedDamage(player, target, attack()))
    }

    @Test
    fun GameTestState.`seeking arrows also raise successful hits against players`() =
        runArrowTest { deps, _ ->
            val target = registerPlayer(player.coords.translateZ(1))
            random.next = 0
            random.then = 0
            assertEquals(3, deps.combat.rollRangedDamage(player, target, attack()))
        }

    @Test
    fun GameTestState.`minimum damage respects target style restrictions`() =
        runArrowTest { deps, target ->
            eventBus.subscribeKeyed(PlayerAttackCheck::class.java, target.id.toLong()) {
                allowed = false
            }
            random.next = 0
            assertEquals(0, deps.combat.rollRangedDamage(player, target, attack()))
        }

    @Test
    fun GameTestState.`other arrows and thrown weapons keep the normal minimum hit`() =
        runArrowTest { deps, target ->
            player.quiver = InvObj(objs.bronze_arrow, 100)
            random.next = 0
            random.then = 0
            assertEquals(1, deps.combat.rollRangedDamage(player, target, attack()))
            player.quiver = InvObj(objs.dragon_arrow, 100)
            player.righthand = InvObj(objs.black_chinchompa, 100)
            random.next = 0
            random.then = 0
            assertEquals(1, deps.combat.rollRangedDamage(player, target, attack()))
        }

    private fun GameTestScope.attack() =
        CombatAttack.Ranged(
            checkNotNull(player.righthand),
            RangedAttackType.Standard,
            RangedAttackStyle.Rapid,
        )

    private fun GameTestState.runArrowTest(body: GameTestScope.(Dependencies, Npc) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            object : AbstractModule() {
                override fun configure() {
                    bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
                }
            },
            WeaponAttackStylesScript::class,
        ) { deps ->
            player.placeAt(CoordGrid(8000, 8000, 0))
            player.setBaseLevel(stats.ranged, 99)
            player.setCurrentLevel(stats.ranged, 99)
            player.righthand = InvObj(objs.twisted_bow)
            player.quiver = InvObj(objs.dragon_arrow, 100)
            val target =
                spawnNpc(
                    player.coords.translateZ(1),
                    npcTypeFactory.create {
                        name = "Seeking arrow target"
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
        val bonuses: WornBonuses,
        val objTypes: ObjTypeList,
    )
}

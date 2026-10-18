package org.rsmod.content.other.special.weapons

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import net.rsprot.protocol.game.outgoing.zone.payload.MapProjAnimV2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.PlayerAttackCheck
import org.rsmod.api.combat.commons.PlayerAttackTarget
import org.rsmod.api.combat.commons.PlayerMaxHitCheck
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varps
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.quiver
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.attack
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript

class TonalzticsOfRalosTest {
    @ParameterizedTest
    @CsvSource("1, false", "3, false", "5, false", "9, false", "1, true", "9, true")
    fun `both hits land together one tick after firing regardless of distance`(
        distance: Int,
        playerTarget: Boolean,
        state: GameTestState,
    ) =
        state.runWeaponTest { deps ->
            val target =
                if (playerTarget) {
                    registerPlayer(CoordGrid(3200, 3200)).also {
                        it.setBaseLevel(stats.hitpoints, 99)
                        it.setCurrentLevel(stats.hitpoints, 99)
                    }
                } else spawnDummy()
            player.placeAt(target.coords.translateZ(distance))
            equip()
            player.quiver = InvObj(objs.rune_arrow, 10)
            val weapon = player.righthand
            val startingHp =
                when (target) {
                    is Npc -> target.hitpoints
                    is Player -> target.hitpoints
                }
            rollHits(true, true)
            shoot(deps, target)
            val projectiles =
                checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)])
                    .filterIsInstance<MapProjAnimV2>()
            assertEquals(listOf(2729, 2730), projectiles.map { it.id })
            assertTrue(projectiles.all { it.endTime == 30 })
            assertEquals(10923, player.pendingSequence.id)
            assertEquals(weapon, player.righthand)
            assertEquals(10, player.quiver?.count)
            if (target is Npc) assertEquals(listOf(5 to 1, 7 to 1), target.pendingHits())
            advance()
            assertEquals(
                startingHp - 12,
                when (target) {
                    is Npc -> target.hitpoints
                    is Player -> target.hitpoints
                },
            )
        }

    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun `each hit rolls its own accuracy and damage`(
        firstHits: Boolean,
        secondHits: Boolean,
        state: GameTestState,
    ) =
        state.runWeaponTest { deps ->
            val npc = spawnDummy()
            equip()
            val beforeXp = player.statMap.getXP(stats.ranged)
            rollHits(firstHits, secondHits)
            shoot(deps, npc)
            val first = if (firstHits) 5 else 0
            val second = if (secondHits) 7 else 0
            assertEquals(listOf(first to 1, second to 1), npc.pendingHits())
            assertEquals(beforeXp + (first + second) * 4, player.statMap.getXP(stats.ranged))
        }

    @ParameterizedTest
    @ValueSource(ints = [75, 99, 112])
    fun `each forced maximum hit is seventy five percent of normal ranged damage`(
        level: Int,
        state: GameTestState,
    ) =
        state.runWeaponTest { deps ->
            val npc = spawnDummy()
            equip()
            player.setCurrentLevel(stats.ranged, level)
            val normalMax =
                deps.attacks.calculateRangedMaxHit(
                    player,
                    npc,
                    RangedAttackType.Light,
                    RangedAttackStyle.Rapid,
                    1.0,
                    0,
                )
            eventBus.subscribeKeyed(PlayerMaxHitCheck::class.java, npc.id.toLong()) {
                forced = true
            }
            shoot(deps, npc)
            assertEquals(List(2) { normalMax * 3 / 4 to 1 }, npc.pendingHits())
        }

    @Test
    fun GameTestState.`quiver ammunition does not add ranged strength to either hit`() =
        runWeaponTest { deps ->
            val npc = spawnDummy()
            equip()
            val normalMax =
                deps.attacks.calculateRangedMaxHit(
                    player,
                    npc,
                    RangedAttackType.Light,
                    RangedAttackStyle.Rapid,
                    1.0,
                    0,
                )
            player.quiver = InvObj(objs.dragon_arrow, 100)
            assertEquals(
                normalMax,
                deps.attacks.calculateRangedMaxHit(
                    player,
                    npc,
                    RangedAttackType.Light,
                    RangedAttackStyle.Rapid,
                    1.0,
                    0,
                ),
            )
            eventBus.subscribeKeyed(PlayerMaxHitCheck::class.java, npc.id.toLong()) {
                forced = true
            }
            shoot(deps, npc)
            assertEquals(List(2) { normalMax * 3 / 4 to 1 }, npc.pendingHits())
            assertEquals(100, player.quiver?.count)
        }

    @Test
    fun GameTestState.`encounter damage restrictions apply to both hits`() = runWeaponTest { deps ->
        val npc = spawnDummy()
        equip()
        val styles = mutableListOf<HitType>()
        eventBus.subscribeKeyed(PlayerAttackCheck::class.java, npc.id.toLong()) {
            styles += style
            allowed = false
        }
        shoot(deps, npc)
        assertEquals(listOf(HitType.Ranged, HitType.Ranged), styles)
        assertEquals(listOf(0 to 1, 0 to 1), npc.pendingHits())
    }

    @ParameterizedTest
    @CsvSource("0, 7", "1, 6", "3, 7")
    fun `ordinary combat repeats two hit attacks without consuming the weapon`(
        stance: Int,
        interval: Int,
        state: GameTestState,
    ) =
        state.runWeaponTest(combat = true) { _ ->
            val npc = spawnDummy()
            equip()
            VarPlayerIntMapSetter.set(player, varps.com_mode, stance)
            allocZoneCollision(player.coords)
            allocZoneCollision(npc.coords)
            advance(ticks = 20)
            val attacks = mutableListOf<Int>()
            eventBus.subscribeUnbound(PlayerAttackTarget::class.java) { attacks += mapClock.cycle }
            val startingHp = npc.hitpoints
            rollHits(true, true)
            player.opNpc2(npc)
            advance()
            assertEquals(listOf(5 to 1, 7 to 1), npc.pendingHits())
            assertEquals(interval, player.actionDelay - attacks.single())
            advance()
            assertEquals(startingHp - 12, npc.hitpoints)
            rollHits(true, true)
            advance(ticks = interval - 1)
            assertEquals(2, attacks.size)
            assertEquals(interval, attacks[1] - attacks[0])
            assertEquals(listOf(5 to 1, 7 to 1), npc.pendingHits())
            assertEquals(special_weapon_objs.tonalztics_of_ralos_charged.id, player.righthand?.id)
            assertEquals(1, player.righthand?.count)
        }

    private fun GameTestScope.equip() {
        player.righthand = InvObj(special_weapon_objs.tonalztics_of_ralos_charged)
        player.stats[stats.ranged] = 99
    }

    private fun GameTestScope.spawnDummy(): Npc =
        spawnNpc(CoordGrid(3200, 3200), dummy) { combatXpMultiplier = 1000 }
            .also { player.placeAt(it.coords.translateZ(1)) }

    private fun GameTestScope.rollHits(firstHits: Boolean, secondHits: Boolean) {
        random.next = if (firstHits) 0 else 9_999
        if (firstHits) random.then = 5
        random.then = if (secondHits) 0 else 9_999
        if (secondHits) random.then = 7
    }

    private fun GameTestScope.shoot(deps: Dependencies, target: PathingEntity) {
        val weaponObj = checkNotNull(player.righthand)
        val weapon = checkNotNull(deps.registry.getRanged(weaponObj))
        val attack = CombatAttack.Ranged(weaponObj, RangedAttackType.Light, RangedAttackStyle.Rapid)
        player.withProtectedAccess {
            assertTrue(
                when (target) {
                    is Npc -> weapon.attack(this, target, attack)
                    is Player -> weapon.attack(this, target, attack)
                }
            )
        }
    }

    private fun Npc.pendingHits(): List<Pair<Int, Int>> = buildList {
        val iterator = queueList.iterator() ?: return@buildList
        while (iterator.hasNext()) {
            val queued = iterator.next()
            val hit = queued.args
            if (hit is Hit) {
                assertEquals(HitType.Ranged, hit.type)
                add(hit.damage to queued.remainingCycles)
            }
        }
        iterator.cleanUp()
    }

    class Dependencies
    @Inject
    constructor(
        val registry: WeaponRegistry,
        val attacks: PlayerAttackManager,
        val zoneUpdates: ZoneUpdateMap,
    )

    private fun GameTestState.runWeaponTest(
        combat: Boolean = false,
        testBody: GameTestScope.(Dependencies) -> Unit,
    ) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            weaponScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            WeaponAttackTypesScript::class,
            *if (combat) arrayOf(pvnCombatScript) else emptyArray(),
            testBody = testBody,
        )

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(WeaponModule())
            install(MagicSpellModule())
            install(RangedWeaponsModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        val weaponScript =
            Class.forName("org.rsmod.api.weapons.scripts.WeaponScript").kotlin
                as KClass<out PluginScript>
        @Suppress("UNCHECKED_CAST")
        val pvnCombatScript =
            Class.forName("org.rsmod.api.combat.scripts.PvNCombatScript").kotlin
                as KClass<out PluginScript>
        val dummy =
            npcTypeFactory.create {
                name = "Tonalztics target"
                size = 1
                hitpoints = 200
                defence = 50
                op[1] = "Attack"
                defaultMode = NpcMode.None
            }
    }
}

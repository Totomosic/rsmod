package org.rsmod.content.other.special.weapons

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.styles.MagicAttackStyle
import org.rsmod.api.combat.commons.types.AttackType
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varps
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.inv.HeldOpScript
import org.rsmod.api.inv.HeldUOpScript
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.attack
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakCharges
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakCharges.Currency
import org.rsmod.content.other.special.weapons.scripts.charge.EyeOfAyakCharging
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.isType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.WeaponCategory
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript

@Execution(ExecutionMode.SAME_THREAD)
class EyeOfAyakTest {
    @Test
    fun GameTestState.`both items resolve to powered staff attacks at speed three`() =
        runEyeTest { deps ->
            assertEquals(560, objs.death_rune.id)
            assertEquals(562, objs.chaos_rune.id)
            for (eye in
                listOf(
                    special_weapon_objs.eye_of_ayak,
                    special_weapon_objs.eye_of_ayak_uncharged,
                )) {
                player.worn[Wearpos.RightHand.slot] = InvObj(eye)
                assertEquals(WeaponCategory.PoweredStaff.id, deps.objTypes[eye].weaponCategory)
                assertEquals(3, deps.objTypes[eye].param(params.attackrate))
                assertEquals(AttackType.Magic, deps.types.get(player))
                assertNotNull(deps.registry.getMagic(InvObj(eye)))
            }
        }

    @Test
    fun GameTestState.`base max hit floors current magic divided by three then subtracts six`() {
        for ((level, expected) in
            listOf(83 to 21, 84 to 22, 85 to 22, 86 to 22, 87 to 23, 99 to 27, 102 to 28)) {
            runEyeTest { deps ->
                val npc = spawnDummy()
                equipEye(deps)
                player.setCurrentLevel(stats.magic, level)
                random.next = HIT_ROLL
                random.then = expected
                cast(deps, npc)
                assertEquals(expected, player.vars[varps.com_maxhit])
                val queued = npc.pendingHits().single()
                assertEquals(expected, queued.first.damage)
                assertEquals(3, player.actionDelay - player.currentMapClock)
                assertEquals(9, deps.charges.count(player.worn[Wearpos.RightHand.slot]))
                advance(ticks = queued.second)
                assertEquals(DUMMY_HITPOINTS - expected, npc.hitpoints)
            }
        }
    }

    @Test
    fun GameTestState.`accurate zero damage roll is clamped to one`() = runEyeTest { deps ->
        val npc = spawnDummy()
        equipEye(deps)
        random.next = HIT_ROLL
        random.then = 0
        cast(deps, npc)
        val queued = npc.pendingHits().single()
        assertEquals(1, queued.first.damage)
        advance(ticks = queued.second)
        assertEquals(DUMMY_HITPOINTS - 1, npc.hitpoints)
    }

    @Test
    fun GameTestState.`splash still consumes one charge and uses three tick attack delay`() =
        runEyeTest { deps ->
            val npc = spawnDummy()
            equipEye(deps)
            random.next = MISS_ROLL
            cast(deps, npc)
            val queued = npc.pendingHits().single()
            assertEquals(0, queued.first.damage)
            assertEquals(3, player.actionDelay - player.currentMapClock)
            assertEquals(9, deps.charges.count(player.worn[Wearpos.RightHand.slot]))
            advance(ticks = queued.second)
            assertEquals(DUMMY_HITPOINTS, npc.hitpoints)
        }

    @Test
    fun GameTestState.`last charge fires and changes the staff to its uncharged form`() =
        runEyeTest { deps ->
            val npc = spawnDummy()
            equipEye(deps, count = 1, currency = Currency.DemonTears)
            random.next = HIT_ROLL
            random.then = 5
            cast(deps, npc)
            assertTrue(
                player.worn[Wearpos.RightHand.slot].isType(
                    special_weapon_objs.eye_of_ayak_uncharged
                )
            )
            assertEquals(0, deps.charges.count(player.worn[Wearpos.RightHand.slot]))
            val queued = npc.pendingHits().single()
            advance(ticks = queued.second)
            assertEquals(DUMMY_HITPOINTS - 5, npc.hitpoints)
        }

    @Test
    fun GameTestState.`empty charged and uncharged items cannot cast`() {
        for (eye in
            listOf(special_weapon_objs.eye_of_ayak, special_weapon_objs.eye_of_ayak_uncharged)) {
            runEyeTest { deps ->
                val npc = spawnDummy()
                player.worn[Wearpos.RightHand.slot] = InvObj(eye)
                cast(deps, npc)
                assertTrue(npc.pendingHits().isEmpty())
                assertTrue(
                    player.worn[Wearpos.RightHand.slot].isType(
                        special_weapon_objs.eye_of_ayak_uncharged
                    )
                )
            }
        }
    }

    @Test
    fun GameTestState.`drained magic does not produce an invalid damage range`() =
        runEyeTest { deps ->
            val npc = spawnDummy()
            equipEye(deps)
            player.setCurrentLevel(stats.magic, 1)
            random.next = HIT_ROLL
            random.then = 0
            cast(deps, npc)
            assertEquals(1, npc.pendingHits().single().first.damage)
        }

    @Test
    fun GameTestState.`player targets use the same staff formula and charge consumption`() =
        runEyeTest { deps ->
            val target = registerPlayer(CoordGrid(0, 50, 50, 0, 0))
            target.stats[stats.hitpoints] = 99
            player.placeAt(target.coords.translateZ(1))
            equipEye(deps)
            random.next = HIT_ROLL
            random.then = 27
            cast(deps, target)
            assertEquals(27, player.vars[varps.com_maxhit])
            assertEquals(9, deps.charges.count(player.worn[Wearpos.RightHand.slot]))
            assertEquals(3, player.actionDelay - player.currentMapClock)
            advance(ticks = 5)
            assertEquals(72, target.hitpoints)
        }

    @Test
    fun GameTestState.`charging with runes consumes two death and one chaos per charge`() =
        runEyeTest { deps ->
            player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
            player.inv[1] = InvObj(objs.death_rune, 20)
            player.inv[2] = InvObj(objs.chaos_rune, 10)
            player.opHeldU(1, 0)
            advance()
            player.resumeCountDialog(4)
            advance()
            assertTrue(player.inv[0].isType(special_weapon_objs.eye_of_ayak))
            assertEquals(4, deps.charges.count(player.inv[0]))
            assertEquals(Currency.Runes, deps.charges.currency(player.inv[0]))
            assertEquals(12, player.inv[1]?.count)
            assertEquals(6, player.inv[2]?.count)
        }

    @Test
    fun GameTestState.`uncharged charge option accepts demon tears one for one`() =
        runEyeTest { deps ->
            player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
            player.inv[1] = InvObj(special_weapon_objs.demon_tear, 10)
            player.opHeld(0, op = 3)
            advance()
            player.resumeCountDialog(4)
            advance()
            assertEquals(4, deps.charges.count(player.inv[0]))
            assertEquals(Currency.DemonTears, deps.charges.currency(player.inv[0]))
            assertEquals(6, player.inv[1]?.count)
        }

    @Test
    fun GameTestState.`charge types cannot be mixed in either direction`() {
        for (existing in Currency.entries) {
            runEyeTest { deps ->
                player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
                deps.charges.add(player.inv, 0, 10, existing)
                player.inv[1] = InvObj(special_weapon_objs.demon_tear, 10)
                player.inv[2] = InvObj(objs.death_rune, 20)
                player.inv[3] = InvObj(objs.chaos_rune, 10)
                player.opHeldU(if (existing == Currency.Runes) 1 else 2, 0)
                advance()
                player.resumeCountDialog(3)
                advance()
                assertEquals(10, deps.charges.count(player.inv[0]))
                assertEquals(existing, deps.charges.currency(player.inv[0]))
                assertEquals(10, player.inv[1]?.count)
                assertEquals(20, player.inv[2]?.count)
                assertEquals(10, player.inv[3]?.count)
            }
        }
    }

    @Test
    fun GameTestState.`spawned charged item with zero charges can be loaded with demon tears`() =
        runEyeTest { deps ->
            player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak)
            player.inv[1] = InvObj(special_weapon_objs.demon_tear, 10)
            player.opHeldU(1, 0)
            advance()
            player.resumeCountDialog(3)
            advance()
            assertEquals(3, deps.charges.count(player.inv[0]))
            assertEquals(Currency.DemonTears, deps.charges.currency(player.inv[0]))
            assertEquals(7, player.inv[1]?.count)
        }

    @Test
    fun GameTestState.`cancelling charging consumes no materials`() = runEyeTest { deps ->
        player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
        player.inv[1] = InvObj(special_weapon_objs.demon_tear, 10)
        player.opHeldU(1, 0)
        advance()
        player.resumeCountDialog(0)
        advance()
        assertTrue(player.inv[0].isType(special_weapon_objs.eye_of_ayak_uncharged))
        assertEquals(0, deps.charges.count(player.inv[0]))
        assertEquals(10, player.inv[1]?.count)
    }

    @Test
    fun GameTestState.`uncharging with a full inventory preserves the staff and its charges`() =
        runEyeTest { deps ->
            player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
            deps.charges.add(player.inv, 0, 5, Currency.Runes)
            for (slot in 1 until player.inv.size) player.inv[slot] = InvObj(objs.shears)
            player.opHeld(0, op = 4)
            advance()
            assertTrue(player.inv[0].isType(special_weapon_objs.eye_of_ayak))
            assertEquals(5, deps.charges.count(player.inv[0]))
            assertEquals(0, player.inv.count(objs.death_rune))
            assertEquals(0, player.inv.count(objs.chaos_rune))
        }

    @Test
    fun GameTestState.`fifty thousand charge limit removes only the materials actually added`() =
        runEyeTest { deps ->
            player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
            deps.charges.add(player.inv, 0, 49_999, Currency.Runes)
            player.inv[1] = InvObj(objs.death_rune, 20)
            player.inv[2] = InvObj(objs.chaos_rune, 10)
            player.opHeldU(1, 0)
            advance()
            player.resumeCountDialog(Int.MAX_VALUE)
            advance()
            assertEquals(50_000, deps.charges.count(player.inv[0]))
            assertEquals(18, player.inv[1]?.count)
            assertEquals(9, player.inv[2]?.count)
        }

    @Test
    fun GameTestState.`uncharging returns the original material type and clears charges`() {
        for (currency in Currency.entries) {
            runEyeTest { deps ->
                player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
                deps.charges.add(player.inv, 0, 5, currency)
                player.opHeld(0, op = 4)
                advance()
                player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
                advance()
                assertTrue(player.inv[0].isType(special_weapon_objs.eye_of_ayak_uncharged))
                assertEquals(0, deps.charges.count(player.inv[0]))
                if (currency == Currency.Runes) {
                    assertEquals(10, player.inv.count(objs.death_rune))
                    assertEquals(5, player.inv.count(objs.chaos_rune))
                    assertEquals(0, player.inv.count(special_weapon_objs.demon_tear))
                } else {
                    assertEquals(5, player.inv.count(special_weapon_objs.demon_tear))
                    assertEquals(0, player.inv.count(objs.death_rune))
                    assertEquals(0, player.inv.count(objs.chaos_rune))
                }
            }
        }
    }

    private fun GameTestScope.spawnDummy(): Npc {
        val npc = spawnNpc(CoordGrid(0, 50, 50, 0, 0), dummy)
        player.placeAt(npc.coords.translateZ(1))
        return npc
    }

    private fun GameTestScope.equipEye(
        deps: EyeTestDependencies,
        count: Int = 10,
        currency: Currency = Currency.Runes,
    ) {
        player.inv[0] = InvObj(special_weapon_objs.eye_of_ayak_uncharged)
        deps.charges.add(player.inv, 0, count, currency)
        player.worn[Wearpos.RightHand.slot] = player.inv[0]
        player.inv[0] = null
        player.stats[stats.magic] = 99
    }

    private fun GameTestScope.cast(deps: EyeTestDependencies, target: PathingEntity) {
        val obj = checkNotNull(player.worn[Wearpos.RightHand.slot])
        val weapon = checkNotNull(deps.registry.getMagic(obj))
        val attack = CombatAttack.Staff(obj, MagicAttackStyle.Accurate)
        player.withProtectedAccess {
            val handled =
                when (target) {
                    is Npc -> weapon.attack(this, target, attack)
                    is Player -> weapon.attack(this, target, attack)
                }
            assertTrue(handled)
        }
    }

    private fun Npc.pendingHits(): List<Pair<Hit, Int>> = buildList {
        val iterator = queueList.iterator() ?: return@buildList
        while (iterator.hasNext()) {
            val queue = iterator.next()
            val hit = queue.args
            if (hit is Hit) add(hit to queue.remainingCycles)
        }
        iterator.cleanUp()
    }

    private fun GameTestState.runEyeTest(testBody: GameTestScope.(EyeTestDependencies) -> Unit) =
        runInjectedGameTest(
            EyeTestDependencies::class,
            EyeTestModule,
            weaponScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            WeaponAttackTypesScript::class,
            EyeOfAyakCharging::class,
            HeldOpScript::class,
            HeldUOpScript::class,
            testBody = testBody,
        )

    class EyeTestDependencies
    @Inject
    constructor(
        val registry: WeaponRegistry,
        val charges: EyeOfAyakCharges,
        val objTypes: ObjTypeList,
        val types: AttackTypes,
    )

    private object EyeTestModule : AbstractModule() {
        override fun configure() {
            install(WeaponModule())
            install(MagicWeaponsModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }

    private companion object {
        private const val HIT_ROLL = 0
        private const val MISS_ROLL = 9_999
        private const val DUMMY_HITPOINTS = 200

        @Suppress("UNCHECKED_CAST")
        private val weaponScript: KClass<out PluginScript> =
            Class.forName("org.rsmod.api.weapons.scripts.WeaponScript").kotlin
                as KClass<out PluginScript>

        private val dummy =
            npcTypeFactory.create {
                name = "Dummy"
                size = 1
                hitpoints = DUMMY_HITPOINTS
                defence = 50
                magic = 50
                defaultMode = NpcMode.None
            }
    }
}

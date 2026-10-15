package org.rsmod.content.other.special.weapons

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.styles.AttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.AttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.inv.HeldOpScript
import org.rsmod.api.inv.HeldUOpScript
import org.rsmod.api.player.back
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.quiver
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.worn.Blowpipe
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.attack
import org.rsmod.content.other.special.weapons.scripts.charge.ToxicBlowpipeCharging
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.isType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript

class ToxicBlowpipeTest {
    @Test
    fun GameTestState.`both cosmetics use light ranged attacks at OSRS speed and range`() =
        runBlowpipeTest { deps ->
            for (pipe in listOf(objs.toxic_blowpipe, objs.blazing_blowpipe)) {
                equip(pipe = pipe)
                assertEquals(AttackType.Light, deps.types.get(player))
                assertEquals(5, deps.objTypes[pipe].param(params.attackrange))
                assertEquals(2, deps.speeds.actual(player, AttackStyle.RapidRanged))
                assertEquals(3, deps.speeds.actual(player, AttackStyle.AccurateRanged))
                assertEquals(3, deps.speeds.actual(player, AttackStyle.LongrangeRanged))
                assertNotNull(deps.registry.getRanged(player.righthand!!))
            }
        }

    @Test
    fun GameTestState
        .`all dart strengths stack with the blowpipe and quiver bonuses are ignored`() =
        runBlowpipeTest { deps ->
            player.quiver = InvObj(objs.dragon_arrow, 100)
            for ((dart, strength) in
                listOf(
                    objs.bronze_dart to 1,
                    objs.iron_dart to 2,
                    objs.steel_dart to 3,
                    objs.black_dart to 6,
                    objs.mithril_dart to 9,
                    objs.adamant_dart to 17,
                    objs.rune_dart to 26,
                    objs.dragon_dart to 35,
                    objs.amethyst_dart to 28,
                )) {
                equip(dart = dart)
                val bonus = deps.bonuses.calculate(player)
                assertEquals(20 + strength, bonus.rangedStr)
                assertEquals(30, bonus.offRange)
            }
        }

    @Test
    fun GameTestState
        .`darts can be loaded before or after scales and every unpoisoned dart works`() {
        for ((index, dart) in Blowpipe.dartTypes.withIndex()) {
            runBlowpipeTest {
                player.inv[0] = InvObj(objs.toxic_blowpipe_uncharged)
                player.inv[1] = InvObj(objs.zulrahs_scales, 20)
                player.inv[2] = InvObj(dart, 10)
                val first = if (index % 2 == 0) 1 else 2
                player.opHeldU(first, 0)
                advance()
                player.opHeldU(0, if (first == 1) 2 else 1)
                advance()
                assertTrue(player.inv[0].isType(objs.toxic_blowpipe))
                assertEquals(20, Blowpipe.scales(player.inv[0]))
                assertEquals(10, Blowpipe.darts(player.inv[0]))
                assertEquals(dart.id, Blowpipe.dartType(player.inv[0])?.id)
                assertNull(player.inv[1])
                assertNull(player.inv[2])
            }
        }
    }

    @Test
    fun GameTestState
        .`both capacities are enforced without losing excess materials or sign bits`() =
        runBlowpipeTest {
            player.inv[0] = InvObj(objs.blazing_blowpipe_uncharged)
            player.inv[1] = InvObj(objs.zulrahs_scales, 20_000)
            player.inv[2] = InvObj(objs.amethyst_dart, 20_000)
            player.opHeldU(1, 0)
            advance()
            player.opHeldU(2, 0)
            advance()
            val full = player.inv[0]
            assertTrue(full.isType(objs.blazing_blowpipe))
            assertEquals(16_383, Blowpipe.scales(full))
            assertEquals(16_383, Blowpipe.darts(full))
            assertEquals(objs.amethyst_dart.id, Blowpipe.dartType(full)?.id)
            assertEquals(3617, player.inv[1]?.count)
            assertEquals(3617, player.inv[2]?.count)
            player.opHeldU(1, 0)
            advance()
            player.opHeldU(2, 0)
            advance()
            assertEquals(full, player.inv[0])
            assertEquals(3617, player.inv[1]?.count)
            assertEquals(3617, player.inv[2]?.count)
        }

    @Test
    fun GameTestState.`switching darts requires unloading and preserves the scales`() =
        runBlowpipeTest {
            player.inv[0] = loaded(scales = 20, dart = objs.rune_dart, darts = 10)
            player.inv[1] = InvObj(objs.dragon_dart, 5)
            val original = player.inv[0]
            player.opHeldU(1, 0)
            advance()
            assertEquals(original, player.inv[0])
            assertEquals(5, player.inv[1]?.count)

            unload()
            assertEquals(20, Blowpipe.scales(player.inv[0]))
            assertEquals(0, Blowpipe.darts(player.inv[0]))
            assertEquals(10, player.inv.count(objs.rune_dart))
            player.opHeldU(1, 0)
            advance()
            assertEquals(objs.dragon_dart.id, Blowpipe.dartType(player.inv[0])?.id)
            assertEquals(5, Blowpipe.darts(player.inv[0]))
        }

    @Test
    fun GameTestState.`poisoned darts are rejected without changing either item`() =
        runBlowpipeTest { deps ->
            player.inv[0] = InvObj(objs.toxic_blowpipe_uncharged)
            player.inv[1] = InvObj(checkNotNull(deps.objTypes[812]), 5)
            val original = player.inv[0]
            player.opHeldU(1, 0)
            advance()
            assertEquals(original, player.inv[0])
            assertEquals(5, player.inv[1]?.count)
        }

    @Test
    fun GameTestState.`uncharge refunds every stored dart and scale and preserves cosmetics`() =
        runBlowpipeTest {
            player.inv[0] = loaded(pipe = objs.blazing_blowpipe, scales = 40, darts = 30)
            unload(scales = true)
            assertTrue(player.inv[0].isType(objs.blazing_blowpipe_uncharged))
            assertEquals(0, player.inv[0]?.vars)
            assertEquals(40, player.inv.count(objs.zulrahs_scales))
            assertEquals(30, player.inv.count(objs.dragon_dart))
        }

    @Test
    fun GameTestState.`cancelled uncharge keeps all resources`() = runBlowpipeTest {
        player.inv[0] = loaded()
        val original = player.inv[0]
        unload(cancel = true)
        assertEquals(original, player.inv[0])
        assertEquals(0, player.inv.count(objs.zulrahs_scales))
        assertEquals(0, player.inv.count(objs.dragon_dart))
    }

    @Test
    fun GameTestState.`full inventory prevents unloading or uncharging without losing charges`() =
        runBlowpipeTest {
            player.inv[0] = loaded()
            for (slot in 1..27) player.inv[slot] = InvObj(objs.bones)
            val original = player.inv[0]
            unload()
            assertEquals(original, player.inv[0])
            unload(scales = true)
            assertEquals(original, player.inv[0])
        }

    @Test
    fun GameTestState.`check reports stored ammo`() = runBlowpipeTest {
        player.inv[0] = loaded(scales = 20, dart = objs.rune_dart, darts = 10)
        player.opHeld(0, op = 3)
        advance()
        assertMessageSent("Your blowpipe contains 20 Zulrah's scales and 10 Rune dart.")
    }

    @Test
    fun GameTestState.`firing requires both scales and loaded darts`() {
        for ((scales, darts) in listOf(0 to 0, 10 to 0, 0 to 10)) {
            runBlowpipeTest { deps ->
                val npc = spawnDummy()
                equip(scales = scales, darts = darts)
                val original = player.righthand
                shoot(deps, npc)
                assertTrue(npc.pendingHits().isEmpty())
                assertEquals(original, player.righthand)
            }
        }
    }

    @Test
    fun GameTestState.`rapid fires a loaded dart and retains the weapon and quiver`() =
        runBlowpipeTest { deps ->
            val npc = spawnDummy()
            equip()
            player.quiver = InvObj(objs.dragon_arrow, 5)
            rollShot(damage = 7)
            shoot(deps, npc)
            val hit = npc.pendingHits().single()
            assertEquals(7, hit.first.damage)
            assertEquals(2, player.actionDelay - player.currentMapClock)
            assertEquals(9, Blowpipe.scales(player.righthand))
            assertEquals(9, Blowpipe.darts(player.righthand))
            assertTrue(player.righthand.isType(objs.toxic_blowpipe))
            assertEquals(5, player.quiver?.count)
            advance(ticks = hit.second)
            assertEquals(193, npc.hitpoints)
        }

    @Test
    fun GameTestState.`accurate and longrange attacks take three ticks`() {
        for (style in listOf(RangedAttackStyle.Accurate, RangedAttackStyle.Longrange)) {
            runBlowpipeTest { deps ->
                val npc = spawnDummy()
                equip()
                rollShot(damage = 3)
                shoot(deps, npc, style)
                assertEquals(3, player.actionDelay - player.currentMapClock)
            }
        }
    }

    @Test
    fun GameTestState.`misses still consume ammunition and scale charges`() =
        runBlowpipeTest { deps ->
            val npc = spawnDummy()
            equip()
            random.next = 9_999
            random.then = 4 // No ground drop.
            random.then = 1 // Consume a scale.
            shoot(deps, npc)
            assertEquals(0, npc.pendingHits().single().first.damage)
            assertEquals(9, Blowpipe.scales(player.righthand))
            assertEquals(9, Blowpipe.darts(player.righthand))
        }

    @Test
    fun GameTestState.`scale consumption saves one of three rolls and consumes on the other two`() =
        runBlowpipeTest { deps ->
            val npc = spawnDummy()
            equip()
            for ((roll, expected) in listOf(0 to 10, 1 to 9, 2 to 8)) {
                rollShot(damage = 1, scaleRoll = roll)
                shoot(deps, npc)
                assertEquals(expected, Blowpipe.scales(player.righthand))
            }
            assertEquals(7, Blowpipe.darts(player.righthand))
        }

    @Test
    fun GameTestState.`Avas assembler conserves loaded darts independently of scales`() =
        runBlowpipeTest { deps ->
            val npc = spawnDummy()
            equip()
            player.back = InvObj(objs.avas_assembler)
            random.next = 0 // Accuracy.
            random.then = 3 // Damage.
            random.then = 0 // Ava's conserves the dart.
            random.then = 1 // Consume a scale.
            shoot(deps, npc)
            assertEquals(10, Blowpipe.darts(player.righthand))
            assertEquals(9, Blowpipe.scales(player.righthand))

            random.next = 0
            random.then = 3
            random.then = 99 // Ava's fails to conserve the dart.
            random.then = 4 // No ground drop.
            random.then = 0 // Save the scale.
            shoot(deps, npc)
            assertEquals(9, Blowpipe.darts(player.righthand))
            assertEquals(9, Blowpipe.scales(player.righthand))
        }

    @Test
    fun GameTestState.`last dart contributes to max hit and leftover scales remain available`() =
        runBlowpipeTest { deps ->
            val npc = spawnDummy()
            equip(darts = 1)
            val maxHit =
                deps.attacks.calculateRangedMaxHit(
                    player,
                    npc,
                    RangedAttackType.Light,
                    RangedAttackStyle.Rapid,
                    1.0,
                    0,
                )
            assertEquals(20, maxHit)
            rollShot(damage = maxHit)
            shoot(deps, npc)
            assertEquals(maxHit, npc.pendingHits().single().first.damage)
            assertEquals(0, Blowpipe.darts(player.righthand))
            assertEquals(9, Blowpipe.scales(player.righthand))
            assertEquals(20, deps.bonuses.rangedStrengthBonus(player))
        }

    @Test
    fun GameTestState.`last scale does not discard leftover darts and blocks the next shot`() =
        runBlowpipeTest { deps ->
            val npc = spawnDummy()
            equip(scales = 1)
            rollShot(damage = 3)
            shoot(deps, npc)
            assertEquals(0, Blowpipe.scales(player.righthand))
            assertEquals(9, Blowpipe.darts(player.righthand))
            assertEquals(objs.dragon_dart.id, Blowpipe.dartType(player.righthand)?.id)
            shoot(deps, npc)
            assertEquals(1, npc.pendingHits().size)
        }

    @Test
    fun GameTestState.`exhausting both resources restores the correct empty cosmetic`() =
        runBlowpipeTest { deps ->
            val npc = spawnDummy()
            equip(pipe = objs.blazing_blowpipe, scales = 1, darts = 1)
            rollShot(damage = 2)
            shoot(deps, npc)
            assertTrue(player.righthand.isType(objs.blazing_blowpipe_uncharged))
            assertEquals(0, player.righthand?.vars)
            assertEquals(1, npc.pendingHits().size)
        }

    @Test
    fun GameTestState.`player targets also receive ranged hits at the chosen attack speed`() =
        runBlowpipeTest { deps ->
            val target = registerPlayer(CoordGrid(0, 50, 50, 0, 0))
            target.setBaseLevel(stats.hitpoints, 99)
            target.setCurrentLevel(stats.hitpoints, 99)
            player.placeAt(target.coords.translateZ(1))
            equip()
            rollShot(damage = 5)
            shoot(deps, target)
            assertEquals(2, player.actionDelay - player.currentMapClock)
            advance(ticks = 3)
            assertEquals(94, target.hitpoints)
        }

    private fun GameTestScope.unload(scales: Boolean = false, cancel: Boolean = false) {
        player.opHeld(0, op = 4)
        advance()
        val option = if (cancel) 3 else if (scales) 2 else 1
        player.resumePauseButton(components.chatmenu_pbutton, sub = option)
        advance()
    }

    private fun GameTestScope.rollShot(damage: Int, scaleRoll: Int = 1) {
        random.next = 0
        random.then = damage
        random.then = 4 // The dart is consumed without a ground drop.
        random.then = scaleRoll
    }

    private fun loaded(
        pipe: ObjType = objs.toxic_blowpipe,
        scales: Int = 10,
        dart: ObjType = objs.dragon_dart,
        darts: Int = 10,
    ): InvObj = Blowpipe.withAmmo(InvObj(pipe), scales, dart, darts)

    private fun GameTestScope.equip(
        pipe: ObjType = objs.toxic_blowpipe,
        scales: Int = 10,
        dart: ObjType = objs.dragon_dart,
        darts: Int = 10,
    ) {
        player.righthand = loaded(pipe, scales, dart, darts)
        player.stats[stats.ranged] = 99
    }

    private fun GameTestScope.spawnDummy(): Npc {
        val npc = spawnNpc(CoordGrid(0, 50, 50, 0, 0), dummy)
        player.placeAt(npc.coords.translateZ(1))
        return npc
    }

    private fun GameTestScope.shoot(
        deps: Dependencies,
        target: PathingEntity,
        style: RangedAttackStyle = RangedAttackStyle.Rapid,
    ) {
        val obj = checkNotNull(player.righthand)
        val weapon = checkNotNull(deps.registry.getRanged(obj))
        val attack = CombatAttack.Ranged(obj, RangedAttackType.Light, style)
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

    private fun GameTestState.runBlowpipeTest(testBody: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            weaponScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            WeaponAttackTypesScript::class,
            ToxicBlowpipeCharging::class,
            HeldOpScript::class,
            HeldUOpScript::class,
            testBody = testBody,
        )

    class Dependencies
    @Inject
    constructor(
        val registry: WeaponRegistry,
        val objTypes: ObjTypeList,
        val bonuses: WornBonuses,
        val speeds: WeaponSpeeds,
        val types: AttackTypes,
        val attacks: PlayerAttackManager,
    )

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(WeaponModule())
            install(RangedWeaponsModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        private val weaponScript =
            Class.forName("org.rsmod.api.weapons.scripts.WeaponScript").kotlin
                as KClass<out PluginScript>

        private val dummy =
            npcTypeFactory.create {
                name = "Dummy"
                size = 1
                hitpoints = 200
                defence = 50
                defaultMode = NpcMode.None
            }
    }
}

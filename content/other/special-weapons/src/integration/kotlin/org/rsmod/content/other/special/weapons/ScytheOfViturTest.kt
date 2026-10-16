package org.rsmod.content.other.special.weapons

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.npcs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.random.GameRandom
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.attack
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.content.other.special.weapons.melee.ScytheOfViturWeapons
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript

@Execution(ExecutionMode.SAME_THREAD)
class ScytheOfViturTest {
    @Test
    fun GameTestState.`combat dummies receive the maximum of each size based splat`() =
        runScytheTest { deps ->
            val npc = spawnDummy(npcSize = 3, combatDummy = true)
            setMaxHit(deps, npc, 51)
            random.next = MISS_ROLL
            useScythe(deps, npc)
            assertEquals(listOf(51 to 1, 25 to 1, 12 to 1), npc.pendingHits())
        }

    @Test
    fun GameTestState.`one tile npc takes one full strength hit`() = assertSizeHits(1, listOf(51))

    @Test
    fun GameTestState.`two tile npc takes full and half strength hits`() =
        assertSizeHits(2, listOf(51, 25))

    @Test
    fun GameTestState.`three tile npc takes hits capped at 51 then 25 then 12`() =
        assertSizeHits(3, listOf(51, 25, 12))

    @Test
    fun GameTestState.`larger npc is capped at three hits`() = assertSizeHits(5, listOf(51, 25, 12))

    @Test
    fun GameTestState.`all charged and uncharged cosmetic variants use size based hits`() {
        for (variant in variants) {
            val damageRandom = MaximumDamageRandom()
            runScytheTest(damageRandom) { deps ->
                val npc = spawnDummy(npcSize = 3)
                equipScythe(variant)
                val attack = attack(variant)
                val maxHit = normalMaxHit(deps, npc, attack)
                repeat(3) { random.next = HIT_ROLL }
                useScythe(deps, npc, attack)
                assertEquals(
                    listOf(maxHit to 1, maxHit / 2 to 1, maxHit / 4 to 1),
                    npc.pendingHits(),
                )
                advance()
                assertEquals(DUMMY_HITPOINTS - maxHit - maxHit / 2 - maxHit / 4, npc.hitpoints)
            }
        }
    }

    @Test
    fun GameTestState.`all eight accuracy outcomes roll each splat independently`() {
        for (mask in 0..7) {
            runScytheTest { deps ->
                val npc = spawnDummy(npcSize = 3)
                val expected =
                    listOf(5, 3, 2).mapIndexed { index, damage ->
                        val accurate = mask and (1 shl index) != 0
                        random.next = if (accurate) HIT_ROLL else MISS_ROLL
                        if (accurate) random.then = damage
                        if (accurate) damage else 0
                    }
                useScythe(deps, npc)
                assertEquals(expected.map { it to 1 }, npc.pendingHits())
                advance()
                assertEquals(DUMMY_HITPOINTS - expected.sum(), npc.hitpoints)
            }
        }
    }

    @Test
    fun GameTestState.`accurate zero rolls deal one on all three splats`() = runScytheTest { deps ->
        val npc = spawnDummy(npcSize = 3)
        repeat(3) {
            random.next = HIT_ROLL
            random.then = 0
        }
        useScythe(deps, npc)
        assertEquals(listOf(1 to 1, 1 to 1, 1 to 1), npc.pendingHits())
        advance()
        assertEquals(DUMMY_HITPOINTS - 3, npc.hitpoints)
    }

    @Test
    fun GameTestState.`halved zero caps stay zero without exceeding the normal max hit`() {
        val damageRandom = MaximumDamageRandom()
        runScytheTest(damageRandom) { deps ->
            val npc = spawnDummy(npcSize = 3)
            player.worn[Wearpos.RightHand.slot] = null
            player.stats[stats.strength] = 1
            assertEquals(1, normalMaxHit(deps, npc, attack()))
            repeat(3) { random.next = HIT_ROLL }
            useScythe(deps, npc)
            assertEquals(listOf(0..1), damageRandom.ranges)
            assertEquals(listOf(1 to 1, 0 to 1, 0 to 1), npc.pendingHits())
            advance()
            assertEquals(DUMMY_HITPOINTS - 1, npc.hitpoints)
        }
    }

    @Test
    fun GameTestState.`player targets take one standard hit`() {
        val damageRandom = MaximumDamageRandom()
        runScytheTest(damageRandom) { deps ->
            val target = registerPlayer(CoordGrid(0, 50, 50, 0, 0))
            target.stats[stats.hitpoints] = 99
            player.placeAt(target.coords.translateZ(1))
            equipScythe()
            setMaxHit(deps, target, 51)
            random.next = HIT_ROLL
            useScythe(deps, target)
            assertEquals(listOf(0..51), damageRandom.ranges)
            assertEquals(99, target.hitpoints)
            advance()
            assertEquals(48, target.hitpoints)
        }
    }

    private fun GameTestState.assertSizeHits(npcSize: Int, expected: List<Int>) {
        val damageRandom = MaximumDamageRandom()
        runScytheTest(damageRandom) { deps ->
            val npc = spawnDummy(npcSize)
            setMaxHit(deps, npc, 51)
            repeat(expected.size) { random.next = HIT_ROLL }
            useScythe(deps, npc)
            assertEquals(expected.map { 0..it }, damageRandom.ranges)
            assertEquals(expected.map { it to 1 }, npc.pendingHits())
            assertEquals(DUMMY_HITPOINTS, npc.hitpoints)
            advance()
            assertEquals(DUMMY_HITPOINTS - expected.sum(), npc.hitpoints)
            advance()
            assertEquals(DUMMY_HITPOINTS - expected.sum(), npc.hitpoints)
        }
    }

    private fun GameTestScope.spawnDummy(npcSize: Int, combatDummy: Boolean = false): Npc {
        val type =
            npcTypeFactory.create(id = if (combatDummy) npcs.combat_dummy.id else 0) {
                name = "Dummy"
                size = npcSize
                hitpoints = DUMMY_HITPOINTS
                defence = 50
                defaultMode = NpcMode.None
            }
        val npc = spawnNpc(CoordGrid(0, 50, 50, 0, 0), type)
        player.placeAt(npc.coords.translateZ(-1))
        equipScythe()
        return npc
    }

    private fun GameTestScope.equipScythe(variant: ObjType = special_weapon_objs.scythe_of_vitur) {
        player.worn[Wearpos.RightHand.slot] = InvObj(variant)
        player.stats[stats.attack] = 99
        player.stats[stats.strength] = 99
    }

    private fun GameTestScope.normalMaxHit(
        deps: ScytheTestDependencies,
        target: PathingEntity,
        attack: CombatAttack.Melee,
    ): Int {
        var maxHit = 0
        player.withProtectedAccess {
            maxHit = deps.manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
        }
        return maxHit
    }

    private fun GameTestScope.setMaxHit(
        deps: ScytheTestDependencies,
        target: PathingEntity,
        maxHit: Int,
    ) {
        for (level in 1..255) {
            player.setCurrentLevel(stats.strength, level)
            if (normalMaxHit(deps, target, attack()) == maxHit) return
        }
        error("Could not reach max hit $maxHit with Scythe of vitur")
    }

    private fun GameTestScope.useScythe(
        deps: ScytheTestDependencies,
        target: PathingEntity,
        attack: CombatAttack.Melee = attack(),
    ) {
        val weapon = checkNotNull(deps.registry.getMelee(attack.weapon))
        player.withProtectedAccess {
            val handled =
                when (target) {
                    is Npc -> weapon.attack(this, target, attack)
                    is Player -> weapon.attack(this, target, attack)
                }
            assertTrue(handled)
        }
    }

    private fun Npc.pendingHits(): List<Pair<Int, Int>> = buildList {
        val iterator = queueList.iterator() ?: return@buildList
        while (iterator.hasNext()) {
            val queue = iterator.next()
            val hit = queue.args
            if (hit is Hit) add(hit.damage to queue.remainingCycles)
        }
        iterator.cleanUp()
    }

    private fun GameTestState.runScytheTest(
        damageRandom: GameRandom? = null,
        testBody: GameTestScope.(ScytheTestDependencies) -> Unit,
    ) =
        runInjectedGameTest(
            ScytheTestDependencies::class,
            ScytheTestModule(damageRandom),
            weaponScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            testBody = testBody,
        )

    class ScytheTestDependencies
    @Inject
    constructor(val registry: WeaponRegistry, val manager: WeaponAttackManager)

    private class ScytheTestModule(private val damageRandom: GameRandom?) : AbstractModule() {
        override fun configure() {
            install(WeaponModule())
            install(MeleeWeaponsModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
            if (damageRandom != null) {
                bind(ScytheOfViturWeapons::class.java)
                    .toInstance(ScytheOfViturWeapons(damageRandom))
            }
        }
    }

    private class MaximumDamageRandom : GameRandom {
        val ranges = mutableListOf<IntRange>()

        override fun of(minInclusive: Int, maxInclusive: Int): Int {
            check(minInclusive <= maxInclusive)
            ranges += minInclusive..maxInclusive
            return maxInclusive
        }

        override fun of(maxExclusive: Int): Int = error("Unexpected exclusive damage roll")

        override fun randomDouble(): Double = error("Unexpected double damage roll")
    }

    private companion object {
        private const val HIT_ROLL = 0
        private const val MISS_ROLL = 9_999
        private const val DUMMY_HITPOINTS = 200

        private fun attack(variant: ObjType = special_weapon_objs.scythe_of_vitur) =
            CombatAttack.Melee(
                InvObj(variant),
                MeleeAttackType.Slash,
                MeleeAttackStyle.Accurate,
                CombatStance.Stance1,
            )

        private val variants
            get() =
                listOf(
                    special_weapon_objs.scythe_of_vitur,
                    special_weapon_objs.scythe_of_vitur_uncharged,
                    special_weapon_objs.holy_scythe_of_vitur,
                    special_weapon_objs.holy_scythe_of_vitur_uncharged,
                    special_weapon_objs.sanguine_scythe_of_vitur,
                    special_weapon_objs.sanguine_scythe_of_vitur_uncharged,
                    special_weapon_objs.blighted_scythe_of_vitur,
                    special_weapon_objs.blighted_scythe_of_vitur_uncharged,
                )

        @Suppress("UNCHECKED_CAST")
        private val weaponScript: KClass<out PluginScript> =
            Class.forName("org.rsmod.api.weapons.scripts.WeaponScript").kotlin
                as KClass<out PluginScript>
    }
}

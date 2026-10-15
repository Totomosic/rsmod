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
import org.rsmod.content.other.special.weapons.melee.SulphurBladesWeapons
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript

@Execution(ExecutionMode.SAME_THREAD)
class SulphurBladesTest {
    @Test
    fun GameTestState.`odd max hit splits into 22 and 23 and lands on the same tick`() {
        val damageRandom = MaximumDamageRandom()
        runWeaponsTest(damageRandom) { deps ->
            val npc = spawnDummy()
            setMaxHit(deps, npc, 45)
            random.next = HIT_ROLL
            random.then = HIT_ROLL
            useBlades(deps, npc)

            assertEquals(listOf(0..22, 0..23), damageRandom.ranges)
            assertEquals(listOf(22 to 1, 23 to 1), npc.pendingHits())
            assertEquals(DUMMY_HITPOINTS, npc.hitpoints)
            advance()
            assertEquals(DUMMY_HITPOINTS - 45, npc.hitpoints)
            advance()
            assertEquals(DUMMY_HITPOINTS - 45, npc.hitpoints)
        }
    }

    @Test
    fun GameTestState.`even max hit splits equally`() {
        val damageRandom = MaximumDamageRandom()
        runWeaponsTest(damageRandom) { deps ->
            val npc = spawnDummy()
            setMaxHit(deps, npc, 44)
            random.next = HIT_ROLL
            random.then = HIT_ROLL
            useBlades(deps, npc)
            assertEquals(listOf(0..22, 0..22), damageRandom.ranges)
            assertEquals(listOf(22 to 1, 22 to 1), npc.pendingHits())
        }
    }

    @Test
    fun GameTestState.`each splat rolls separate damage when both accuracy checks pass`() =
        assertAccuracyOutcomes(firstHits = true, secondHits = true)

    @Test
    fun GameTestState.`second splat can hit when the first misses`() =
        assertAccuracyOutcomes(firstHits = false, secondHits = true)

    @Test
    fun GameTestState.`second splat can miss when the first hits`() =
        assertAccuracyOutcomes(firstHits = true, secondHits = false)

    @Test
    fun GameTestState.`both splats can miss`() =
        assertAccuracyOutcomes(firstHits = false, secondHits = false)

    @Test
    fun GameTestState.`accurate zero damage rolls deal one on each splat`() =
        runWeaponsTest { deps ->
            val npc = spawnDummy()
            random.next = HIT_ROLL
            random.then = 0
            random.then = HIT_ROLL
            random.then = 0
            useBlades(deps, npc)
            assertEquals(listOf(1 to 1, 1 to 1), npc.pendingHits())
            advance()
            assertEquals(DUMMY_HITPOINTS - 2, npc.hitpoints)
        }

    @Test
    fun GameTestState.`max hit of one gives a zero first splat without an invalid damage roll`() {
        val damageRandom = MaximumDamageRandom()
        runWeaponsTest(damageRandom) { deps ->
            val npc = spawnDummy()
            player.worn[Wearpos.RightHand.slot] = null
            player.stats[stats.strength] = 1
            player.withProtectedAccess {
                assertEquals(
                    1,
                    deps.manager.calculateMeleeMaxHit(this, npc, attack.type, attack.style, 1.0),
                )
            }
            random.next = HIT_ROLL
            random.then = HIT_ROLL
            useBlades(deps, npc)
            assertEquals(listOf(0..1), damageRandom.ranges)
            assertEquals(listOf(0 to 1, 1 to 1), npc.pendingHits())
            advance()
            assertEquals(DUMMY_HITPOINTS - 1, npc.hitpoints)
        }
    }

    @Test
    fun GameTestState.`player targets also receive both hits on the same tick`() {
        val damageRandom = MaximumDamageRandom()
        runWeaponsTest(damageRandom) { deps ->
            val target = registerPlayer(CoordGrid(0, 50, 50, 0, 0))
            target.stats[stats.hitpoints] = 99
            player.placeAt(target.coords.translateZ(1))
            equipBlades()
            setMaxHit(deps, target, 45)
            val startingHp = target.hitpoints
            random.next = HIT_ROLL
            random.then = HIT_ROLL
            useBlades(deps, target)
            assertEquals(listOf(0..22, 0..23), damageRandom.ranges)
            assertEquals(startingHp, target.hitpoints)
            advance()
            assertEquals(startingHp - 45, target.hitpoints)
            advance()
            assertEquals(startingHp - 45, target.hitpoints)
        }
    }

    private fun GameTestState.assertAccuracyOutcomes(firstHits: Boolean, secondHits: Boolean) =
        runWeaponsTest { deps ->
            val npc = spawnDummy()
            random.next = if (firstHits) HIT_ROLL else MISS_ROLL
            if (firstHits) random.then = 5
            random.then = if (secondHits) HIT_ROLL else MISS_ROLL
            if (secondHits) random.then = 7
            useBlades(deps, npc)
            val firstDamage = if (firstHits) 5 else 0
            val secondDamage = if (secondHits) 7 else 0
            assertEquals(listOf(firstDamage to 1, secondDamage to 1), npc.pendingHits())
            advance()
            assertEquals(DUMMY_HITPOINTS - firstDamage - secondDamage, npc.hitpoints)
        }

    private fun GameTestScope.spawnDummy(): Npc {
        val npc = spawnNpc(CoordGrid(0, 50, 50, 0, 0), dummy)
        player.placeAt(npc.coords.translateZ(1))
        equipBlades()
        return npc
    }

    private fun GameTestScope.equipBlades() {
        player.worn[Wearpos.RightHand.slot] = InvObj(special_weapon_objs.sulphur_blades)
        player.stats[stats.attack] = 99
        player.stats[stats.strength] = 99
    }

    private fun GameTestScope.setMaxHit(
        deps: WeaponsTestDependencies,
        target: PathingEntity,
        maxHit: Int,
    ) {
        // Select a strength level using the real cache bonuses and normal melee formula.
        for (level in 1..255) {
            player.setCurrentLevel(stats.strength, level)
            var actual = 0
            player.withProtectedAccess {
                actual =
                    deps.manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
            }
            if (actual == maxHit) return
        }
        error("Could not reach max hit $maxHit with Sulphur blades")
    }

    private fun GameTestScope.useBlades(deps: WeaponsTestDependencies, target: PathingEntity) {
        val weapon = deps.registry.getMelee(attack.weapon)
        assertNotNull(weapon)
        player.withProtectedAccess {
            val handled =
                when (target) {
                    is Npc -> checkNotNull(weapon).attack(this, target, attack)
                    is Player -> checkNotNull(weapon).attack(this, target, attack)
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

    private fun GameTestState.runWeaponsTest(
        damageRandom: GameRandom? = null,
        testBody: GameTestScope.(WeaponsTestDependencies) -> Unit,
    ) =
        runInjectedGameTest(
            WeaponsTestDependencies::class,
            WeaponsTestModule(damageRandom),
            weaponScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            testBody = testBody,
        )

    class WeaponsTestDependencies
    @Inject
    constructor(val registry: WeaponRegistry, val manager: WeaponAttackManager)

    private class WeaponsTestModule(private val damageRandom: GameRandom?) : AbstractModule() {
        override fun configure() {
            install(WeaponModule())
            install(MeleeWeaponsModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
            if (damageRandom != null) {
                bind(SulphurBladesWeapons::class.java)
                    .toInstance(SulphurBladesWeapons(damageRandom))
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

        private val attack
            get() =
                CombatAttack.Melee(
                    InvObj(special_weapon_objs.sulphur_blades),
                    MeleeAttackType.Slash,
                    MeleeAttackStyle.Accurate,
                    CombatStance.Stance1,
                )

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
                defaultMode = NpcMode.None
            }
    }
}

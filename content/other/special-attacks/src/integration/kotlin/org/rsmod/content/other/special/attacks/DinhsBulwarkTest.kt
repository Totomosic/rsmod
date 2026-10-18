@file:OptIn(InternalApi::class)

package org.rsmod.content.other.special.attacks

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import net.rsprot.protocol.game.outgoing.zone.payload.SoundArea
import org.junit.jupiter.api.Test
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.formulas.accuracy.melee.PvNMeleeAccuracy
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.stat
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.specials.SpecialAttack
import org.rsmod.api.specials.SpecialAttackModule as ApiSpecialAttackModule
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.content.other.special.attacks.configs.special_objs
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class DinhsBulwarkTest {
    @Test
    fun GameTestState.`both bulwark variants play one shared bash sound per special`() {
        for (weapon in listOf(special_objs.dinhs_bulwark, special_objs.dinhs_blazing_bulwark)) {
            runBulwarkTest { deps ->
                val npc = spawnNpc(center.translateZ(1), dummy)
                random.next = 0
                useSpecial(deps, npc, weapon)
                val sounds =
                    deps.zones[ZoneKey.from(player.coords)]?.filterIsInstance<SoundArea>().orEmpty()
                assertEquals(listOf(3454 to 0), sounds.map { it.id to it.delay })
            }
        }
    }

    @Test
    fun GameTestState
        .`primary gets two independent full damage hits and nearby gets one on the same tick`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            val nearby = spawnNpc(center.translateX(2), dummy)
            markMulti(deps, listOf(center, primary.coords, nearby.coords))
            queueHits(10, 7, 4)
            useSpecial(deps, primary)

            assertEquals(listOf(10 to 1, 7 to 1), primary.pendingHits())
            assertEquals(listOf(4 to 1), nearby.pendingHits())
            assertEquals(95, primary.attackLvl)
            assertEquals(95, primary.strengthLvl)
            advance()
            assertEquals(183, primary.hitpoints)
            assertEquals(196, nearby.hitpoints)
        }

    @Test
    fun GameTestState
        .`sweep is capped at ten NPCs including the original even when it spawns last`() =
        runBulwarkTest { deps ->
            val others = List(12) { spawnNpc(center.translateX(2), dummy) }
            val primary = spawnNpc(center.translateZ(1), dummy)
            markMulti(deps, listOf(center, primary.coords, others.first().coords))
            queueHits(*IntArray(11) { 1 })
            useSpecial(deps, primary)

            assertEquals(2, primary.pendingHits().size)
            assertEquals(9, others.count { it.pendingHits().size == 1 })
            assertEquals(3, others.count { it.pendingHits().isEmpty() })
        }

    @Test
    fun GameTestState
        .`sweep includes all four corners of the player centered eleven by eleven square`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            val corners =
                listOf(-5 to -5, -5 to 5, 5 to -5, 5 to 5).map { (x, z) ->
                    spawnNpc(center.translate(x, z), dummy)
                }
            val outside =
                listOf(-6 to 0, 6 to 0, 0 to -6, 0 to 6).map { (x, z) ->
                    spawnNpc(center.translate(x, z), dummy)
                }
            val upstairs = spawnNpc(CoordGrid(x = center.x, z = center.z, level = 1), dummy)
            markMulti(
                deps,
                listOf(center, primary.coords, upstairs.coords) +
                    (corners + outside).map { it.coords },
            )
            queueHits(*IntArray(6) { 2 })
            useSpecial(deps, primary)

            for (npc in corners) assertEquals(listOf(2 to 1), npc.pendingHits())
            for (npc in outside + upstairs) assertTrue(npc.pendingHits().isEmpty())
        }

    @Test
    fun GameTestState.`sweep skips hidden dead delayed nonattackable and single combat NPCs`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            val candidates =
                listOf(
                    spawnNpc(center.translateX(2), dummy) { hidden = true },
                    spawnNpc(center.translateX(2), dummy) { hitpoints = 0 },
                    spawnNpc(center.translateX(2), dummy) { delay = 100 },
                    spawnNpc(center.translateX(2), npcTypeFactory.create { name = "Noncombatant" }),
                    spawnNpc(center.translateX(-2), dummy),
                )
            markMulti(deps, listOf(center, primary.coords, center.translateX(2)))
            queueHits(2, 3)
            useSpecial(deps, primary)

            assertEquals(2, primary.pendingHits().size)
            for (npc in candidates) assertTrue(npc.pendingHits().isEmpty())
        }

    @Test
    fun GameTestState.`sweep cannot hit through a blocked line of sight`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            val blocked = spawnNpc(center.translateX(3), dummy)
            markMulti(deps, listOf(center, primary.coords, blocked.coords))
            val obstruction = center.translateX(2)
            deps.collision.add(
                obstruction.x,
                obstruction.z,
                obstruction.level,
                CollisionFlag.LOC_PROJ_BLOCKER,
            )
            queueHits(2, 3)
            useSpecial(deps, primary)
            assertTrue(blocked.pendingHits().isEmpty())
        }

    @Test
    fun GameTestState.`primary cannot be hit without line of sight from the player`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            markMulti(deps, listOf(center, primary.coords))
            deps.collision.add(
                primary.coords.x,
                primary.coords.z,
                primary.coords.level,
                CollisionFlag.WALL_SOUTH_PROJ_BLOCKER,
            )
            val before = player.statMap.getXP(stats.attack)
            useSpecial(deps, primary)
            assertTrue(primary.pendingHits().isEmpty())
            assertEquals(100, primary.attackLvl)
            assertEquals(before, player.statMap.getXP(stats.attack))
        }

    @Test
    fun GameTestState.`sweep uses player sight even when the primary can see the NPC`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            val blocked = spawnNpc(center.translateX(3), dummy)
            val visible = spawnNpc(center.translateZ(3), dummy)
            markMulti(deps, listOf(center, primary.coords, blocked.coords, visible.coords))
            deps.collision.add(center.x + 1, center.z, center.level, CollisionFlag.LOC_PROJ_BLOCKER)
            queueHits(2, 3, 4)
            useSpecial(deps, primary)
            assertTrue(blocked.pendingHits().isEmpty())
            assertEquals(listOf(4 to 1), visible.pendingHits())
        }

    @Test
    fun GameTestState.`single combat only hits the primary once`() = runBulwarkTest { deps ->
        val primary = spawnNpc(center.translateZ(1), dummy)
        val nearby = spawnNpc(center.translateX(2), dummy)
        queueHits(7)
        useSpecial(deps, primary)
        assertEquals(listOf(7 to 1), primary.pendingHits())
        assertTrue(nearby.pendingHits().isEmpty())
    }

    @Test
    fun GameTestState.`primary accuracy miss does not stop its independent sweep hit`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            markMulti(deps, listOf(center, primary.coords))
            random.next = 9_999
            queueHits(8)
            useSpecial(deps, primary)
            assertEquals(listOf(0 to 1, 8 to 1), primary.pendingHits())
            assertEquals(95, primary.attackLvl)
        }

    @Test
    fun GameTestState
        .`each sweep NPC rolls accuracy independently and misses do not drain stats`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            val nearby = spawnNpc(center.translateX(2), dummy)
            markMulti(deps, listOf(center, primary.coords, nearby.coords))
            queueHits(1, 2)
            random.then = 9_999
            useSpecial(deps, primary)
            assertEquals(listOf(0 to 1), nearby.pendingHits())
            assertEquals(100, nearby.attackLvl)
        }

    @Test
    fun GameTestState.`two primary misses leave offensive stats unchanged`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            markMulti(deps, listOf(center, primary.coords))
            random.next = 9_999
            random.then = 9_999
            useSpecial(deps, primary)
            assertEquals(listOf(0 to 1, 0 to 1), primary.pendingHits())
            assertEquals(100, primary.attackLvl)
            assertEquals(100, primary.strengthLvl)
            assertEquals(100, primary.rangedLvl)
            assertEquals(100, primary.magicLvl)
        }

    @Test
    fun GameTestState
        .`primary experience is capped once across both hits to remaining hitpoints`() =
        runBulwarkTest { deps ->
            val primary =
                spawnNpc(center.translateZ(1), dummy) {
                    hitpoints = 5
                    combatXpMultiplier = 1000
                }
            markMulti(deps, listOf(center, primary.coords))
            val before = player.statMap.getXP(stats.attack)
            queueHits(10, 7)
            useSpecial(deps, primary)
            assertEquals(before + 20, player.statMap.getXP(stats.attack))
        }

    @Test
    fun GameTestState
        .`blazing bulwark uses the same sweep and a successful zero damage roll becomes one`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            markMulti(deps, listOf(center, primary.coords))
            queueHits(0, 0)
            useSpecial(deps, primary, special_objs.dinhs_blazing_bulwark)
            assertEquals(listOf(1 to 1, 1 to 1), primary.pendingHits())
        }

    @Test
    fun GameTestState.`accuracy is boosted by twenty five percent for both primary and sweep`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            val nearby = spawnNpc(center.translateX(2), dummy)
            markMulti(deps, listOf(center, primary.coords, nearby.coords))
            fun chance(multiplier: Double) =
                deps.accuracy.getHitChance(
                    player,
                    primary,
                    MeleeAttackType.Crush,
                    MeleeAttackStyle.Accurate,
                    MeleeAttackType.Crush,
                    multiplier,
                )
            val threshold = chance(1.25)
            assertTrue(threshold > chance(1.20))
            repeat(3) {
                random.then = threshold - 1
                random.then = 4
            }
            useSpecial(deps, primary)
            assertEquals(listOf(4 to 1, 4 to 1), primary.pendingHits())
            assertEquals(listOf(4 to 1), nearby.pendingHits())
        }

    @Test
    fun GameTestState
        .`stat drain applies once per NPC and prefers melee then ranged then magic on ties`() =
        runBulwarkTest { deps ->
            val primary = spawnNpc(center.translateZ(1), dummy)
            markMulti(deps, listOf(center, primary.coords))
            repeat(3) {
                queueHits(1, 1)
                useSpecial(deps, primary)
                if (it == 0) {
                    assertEquals(95, primary.attackLvl)
                    assertEquals(95, primary.strengthLvl)
                    assertEquals(100, primary.rangedLvl)
                    assertEquals(100, primary.magicLvl)
                }
                if (it == 1) {
                    assertEquals(95, primary.rangedLvl)
                    assertEquals(100, primary.magicLvl)
                }
            }
            assertEquals(95, primary.magicLvl)
            assertEquals(95, primary.attackLvl)
        }

    @Test
    fun GameTestState.`player targets receive one hit and drain uses current levels`() =
        runBulwarkTest { deps ->
            val target = registerPlayer(center.translateZ(1))
            target.setBaseLevel(stats.hitpoints, 99)
            target.setCurrentLevel(stats.hitpoints, 99)
            target.setCurrentLevel(stats.attack, 50)
            target.setCurrentLevel(stats.strength, 50)
            target.setCurrentLevel(stats.ranged, 50)
            target.setCurrentLevel(stats.magic, 80)
            val nearby = spawnNpc(center.translateX(2), dummy)
            markMulti(deps, listOf(center, target.coords, nearby.coords))
            queueHits(5)
            useSpecial(deps, target)
            assertEquals(76, target.stat(stats.magic))
            assertEquals(50, target.stat(stats.attack))
            assertTrue(nearby.pendingHits().isEmpty())
            advance()
            assertEquals(94, target.stat(stats.hitpoints))
        }

    private fun markMulti(deps: Dependencies, coords: List<CoordGrid>) {
        for (coord in coords.distinct()) {
            deps.areas.registerAll(coord, listOf(areas.multiway.id.toShort()).iterator())
        }
    }

    private fun GameTestScope.queueHits(vararg damage: Int) {
        for (hit in damage) {
            random.then = 0
            random.then = hit
        }
    }

    private fun GameTestScope.useSpecial(
        deps: Dependencies,
        target: PathingEntity,
        weapon: ObjType = special_objs.dinhs_bulwark,
    ) {
        player.righthand = InvObj(weapon)
        val special = checkNotNull(deps.registry[InvObj(weapon)]) as SpecialAttack.Melee
        val attack =
            CombatAttack.Melee(
                InvObj(weapon),
                MeleeAttackType.Crush,
                MeleeAttackStyle.Accurate,
                CombatStance.Stance1,
            )
        player.withProtectedAccess { assertTrue(special.attack(this, target, attack)) }
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

    private fun GameTestState.runBulwarkTest(testBody: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            specialAttackScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
        ) { deps ->
            // Use an empty test map square so range checks are independent of world scenery.
            for (x in -1..1) for (z in -1..1) allocZoneCollision(center.translate(x * 8, z * 8))
            player.placeAt(center)
            player.stats[stats.attack] = 99
            player.stats[stats.strength] = 99
            player.righthand = InvObj(special_objs.dinhs_bulwark)
            testBody(deps)
        }

    class Dependencies
    @Inject
    constructor(
        val registry: SpecialAttackRegistry,
        val areas: AreaIndex,
        val accuracy: PvNMeleeAccuracy,
        val collision: CollisionFlagMap,
        val zones: ZoneUpdateMap,
    )

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(ApiSpecialAttackModule())
            install(SpecialAttackModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }

    private companion object {
        private val center = CoordGrid(0, 10, 10, 24, 24)

        @Suppress("UNCHECKED_CAST")
        private val specialAttackScript =
            Class.forName("org.rsmod.api.specials.scripts.SpecialAttackScript").kotlin
                as KClass<out PluginScript>

        private val dummy =
            npcTypeFactory.create {
                name = "Dummy"
                size = 1
                hitpoints = 200
                attack = 100
                strength = 100
                defence = 50
                ranged = 100
                magic = 100
                op[1] = "Attack"
                defaultMode = NpcMode.None
            }
    }
}

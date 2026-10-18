@file:OptIn(org.rsmod.annotations.InternalApi::class)

package org.rsmod.content.other.special.weapons

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import net.rsprot.protocol.game.outgoing.zone.payload.MapProjAnimV2
import net.rsprot.protocol.game.outgoing.zone.payload.SoundArea
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.styles.AttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.AttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.categories
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.back
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.quiver
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.attack
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class BlackChinchompaTest {
    @Test
    fun GameTestState.`elite void medium fuse hits 28 with 112 ranged rigour and 44 strength`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val equipment =
                mapOf(
                    Wearpos.Hat to objs.void_ranger_helm,
                    Wearpos.Torso to objs.elite_void_top,
                    Wearpos.Legs to objs.elite_void_robe,
                    Wearpos.Hands to objs.void_gloves,
                    Wearpos.Front to objs.necklace_of_anguish,
                    Wearpos.Ring to objs.venator_ring,
                )
            for ((slot, obj) in equipment) player.worn[slot.slot] = InvObj(obj)
            player.worn[Wearpos.LeftHand.slot] =
                InvObj(deps.objTypes.values.first { it.internalName == "odium_ward" })
            player.setCurrentLevel(stats.ranged, 112)
            VarPlayerIntMapSetter.set(player, varbits.rigour, 1)
            assertEquals(44, deps.bonuses.rangedStrengthBonus(player))
            var maxHit = 0
            player.withProtectedAccess {
                maxHit =
                    deps.manager.calculateRangedMaxHit(
                        this,
                        primary,
                        RangedAttackType.Heavy,
                        RangedAttackStyle.Rapid,
                        1.0,
                        0,
                    )
            }
            assertEquals(28, maxHit)
            random.next = 0
            random.then = 28
            shoot(deps, primary)
            assertEquals(28, primary.pendingHits().single().first.damage)
        }

    @ParameterizedTest
    @CsvSource("1, 0", "1, 9999", "9, 0", "9, 9999", "10, 0", "10, 9999")
    fun `each throw plays a release sound and one explosion when the projectile lands`(
        distance: Int,
        accuracyRoll: Int,
        state: GameTestState,
    ) {
        for (playerTarget in listOf(false, true)) {
            state.runChinchompaTest { deps ->
                val target =
                    if (playerTarget) {
                        registerPlayer(CoordGrid(3200, 3200)).also {
                            it.setBaseLevel(stats.hitpoints, 99)
                            it.setCurrentLevel(stats.hitpoints, 99)
                        }
                    } else {
                        spawnPrimary().also { primary ->
                            val stacked = List(3) { spawnNpc(primary.coords, dummy) }
                            markMulti(deps, listOf(primary) + stacked)
                        }
                    }
                player.placeAt(target.coords.translateZ(-distance))
                equip(count = 1)
                random.next = accuracyRoll
                repeat(if (playerTarget) 1 else 4) { random.then = 5 }

                shoot(deps, target)

                val projectile =
                    checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)])
                        .filterIsInstance<MapProjAnimV2>()
                        .single()
                val sounds =
                    listOf(ZoneKey.from(player.coords), ZoneKey.from(target.coords))
                        .distinct()
                        .flatMap { deps.zoneUpdates[it]?.toList().orEmpty() }
                        .filterIsInstance<SoundArea>()
                assertEquals(2, sounds.size)
                val launch = sounds.single { it.id == 359 }
                assertEquals(0, launch.delay)
                assertEquals(1, launch.loops)
                assertEquals(ZoneGrid.from(player.coords).x, launch.xInZone)
                assertEquals(ZoneGrid.from(player.coords).z, launch.zInZone)
                assertTrue(
                    checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)]).any {
                        it === launch
                    }
                )
                val explosion = sounds.single { it.id == 360 }
                assertEquals(360, explosion.id)
                assertEquals(projectile.endTime, explosion.delay)
                assertTrue(explosion.delay > 0)
                assertEquals(1, explosion.loops)
                assertEquals(ZoneGrid.from(target.coords).x, explosion.xInZone)
                assertEquals(ZoneGrid.from(target.coords).z, explosion.zInZone)
                assertTrue(
                    checkNotNull(deps.zoneUpdates[ZoneKey.from(target.coords)]).any {
                        it === explosion
                    }
                )
                val impactTick = 1 + projectile.endTime / 30
                if (target is Npc) {
                    assertEquals(impactTick, target.pendingHits().single().second)
                }
                if (accuracyRoll == 0) {
                    val initialHp =
                        when (target) {
                            is Npc -> target.hitpoints
                            is Player -> target.hitpoints
                        }
                    if (impactTick > 1) advance(ticks = impactTick - 1)
                    assertEquals(
                        initialHp,
                        when (target) {
                            is Npc -> target.hitpoints
                            is Player -> target.hitpoints
                        },
                    )
                    advance()
                    assertEquals(
                        initialHp - 5,
                        when (target) {
                            is Npc -> target.hitpoints
                            is Player -> target.hitpoints
                        },
                    )
                }
            }
        }
    }

    @Test
    fun GameTestState.`black chinchompas retain fuse category and use three tick medium fuse`() =
        runChinchompaTest { deps ->
            equip()
            val type = deps.objTypes[objs.black_chinchompa]
            assertTrue(type.isCategoryType(categories.chinchompa))
            assertEquals(AttackType.Heavy, deps.types.get(player))
            assertEquals(9, type.param(params.attackrange))
            assertEquals(3, deps.speeds.actual(player, AttackStyle.RapidRanged))
            assertEquals(4, deps.speeds.actual(player, AttackStyle.AccurateRanged))
            assertEquals(4, deps.speeds.actual(player, AttackStyle.LongrangeRanged))
            assertNotNull(deps.registry.getRanged(player.righthand!!))
        }

    @Test
    fun GameTestState.`quiver bonuses do not contribute to thrown chinchompa damage`() =
        runChinchompaTest { deps ->
            equip()
            val withoutQuiver = deps.bonuses.calculate(player)
            player.quiver = InvObj(objs.dragon_arrow, 100)
            assertEquals(withoutQuiver, deps.bonuses.calculate(player))
        }

    @Test
    fun GameTestState.`every tile of the three by three square and stacked NPCs can be hit`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val splash = buildList {
                for (dx in -1..1) {
                    for (dz in -1..1) {
                        add(spawnNpc(primary.coords.translate(dx, dz), dummy))
                    }
                }
                // There is no tile occupancy exclusion or nine-target cap.
                repeat(3) { add(spawnNpc(primary.coords, dummy)) }
            }
            val outside =
                listOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2, 2 to 2).map { (dx, dz) ->
                    spawnNpc(primary.coords.translate(dx, dz), dummy)
                }
            val upstairs = spawnNpc(primary.coords.translateLevel(1), dummy)
            markMulti(deps, listOf(primary) + splash + outside + upstairs)
            random.next = 0 // One accuracy roll.
            repeat(1 + splash.size) { random.then = 5 }
            random.then = SENTINEL

            shoot(deps, primary)

            for (npc in listOf(primary) + splash) {
                assertEquals(listOf(5), npc.pendingHits().map { it.first.damage })
            }
            for (npc in outside + upstairs) assertTrue(npc.pendingHits().isEmpty())
            assertEquals(SENTINEL, random.next)
            assertEquals(19, player.righthand?.count)
        }

    @Test
    fun GameTestState.`large targets are included only when their southwest tile is in range`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary(size = 3)
            val inside = spawnNpc(primary.coords.translate(1, 1), largeDummy)
            // Its footprint overlaps the square, but its southwest tile lies outside it.
            val overlapping = spawnNpc(primary.coords.translateX(-2), largeDummy)
            // Next to the primary's northeast corner, outside the southwest-centered square.
            val northeast = spawnNpc(primary.coords.translate(2, 2), dummy)
            markMulti(deps, listOf(primary, inside, overlapping, northeast))
            random.next = 0
            random.then = 6
            random.then = 7
            random.then = SENTINEL

            shoot(deps, primary)

            assertEquals(listOf(6), primary.pendingHits().map { it.first.damage })
            assertEquals(listOf(7), inside.pendingHits().map { it.first.damage })
            assertTrue(overlapping.pendingHits().isEmpty())
            assertTrue(northeast.pendingHits().isEmpty())
            assertEquals(SENTINEL, random.next)
        }

    @Test
    fun GameTestState.`a primary miss queues zero for everyone without rolling any damage`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val splash = List(3) { spawnNpc(primary.coords, dummy) }
            markMulti(deps, listOf(primary) + splash)
            random.next = 9_999
            random.then = SENTINEL

            shoot(deps, primary)

            for (npc in listOf(primary) + splash) {
                assertEquals(listOf(0), npc.pendingHits().map { it.first.damage })
            }
            assertEquals(SENTINEL, random.next)
            assertEquals(19, player.righthand?.count)
        }

    @Test
    fun GameTestState.`successful accuracy is shared but each target rolls its own damage`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val splash = List(3) { spawnNpc(primary.coords, dummy) }
            markMulti(deps, listOf(primary) + splash)
            random.next = 0
            random.then = 0 // An accurate zero roll uses the engine's minimum hit of one.
            random.then = 2
            random.then = 4
            random.then = 6
            random.then = SENTINEL

            shoot(deps, primary)

            assertEquals(listOf(1), primary.pendingHits().map { it.first.damage })
            assertEquals(
                listOf(2, 4, 6),
                splash.map { it.pendingHits().single().first.damage }.sorted(),
            )
            assertEquals(SENTINEL, random.next)
        }

    @Test
    fun GameTestState.`splash requires line of sight from the primary rather than the thrower`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val secondary = spawnNpc(primary.coords.translateX(1), dummy)
            player.placeAt(secondary.coords.translateZ(-9))
            markMulti(deps, listOf(primary, secondary))
            val tile = secondary.coords
            val original = deps.collision[tile.x, tile.z, tile.level]
            deps.collision.add(tile.x, tile.z, tile.level, CollisionFlag.WALL_WEST_PROJ_BLOCKER)
            try {
                assertTrue(deps.validator.hasLineOfSight(player.coords, secondary.coords))
                assertFalse(deps.validator.hasLineOfSight(primary.coords, secondary.coords))
                random.next = 0
                random.then = 5
                shoot(deps, primary)
                assertTrue(secondary.pendingHits().isEmpty())
            } finally {
                deps.collision[tile.x, tile.z, tile.level] = original
            }
        }

    @Test
    fun GameTestState.`splash can hit behind a wall blocking only the throwers view`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val secondary = spawnNpc(primary.coords.translateX(1), dummy)
            markMulti(deps, listOf(primary, secondary))
            val tile = secondary.coords.translateZ(-1)
            val original = deps.collision[tile.x, tile.z, tile.level]
            deps.collision.add(tile.x, tile.z, tile.level, CollisionFlag.WALL_SOUTH_PROJ_BLOCKER)
            try {
                assertFalse(deps.validator.hasLineOfSight(player.coords, secondary.coords))
                assertTrue(deps.validator.hasLineOfSight(primary.coords, secondary.coords))
                random.next = 0
                random.then = 5
                random.then = 7
                shoot(deps, primary)
                assertEquals(listOf(7), secondary.pendingHits().map { it.first.damage })
            } finally {
                deps.collision[tile.x, tile.z, tile.level] = original
            }
        }

    @Test
    fun GameTestState.`movement and new arrivals during flight do not change the splash targets`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val secondary = spawnNpc(primary.coords.translateX(1), dummy)
            val outside = spawnNpc(primary.coords.translateX(2), dummy)
            markMulti(deps, listOf(primary, secondary, outside))
            val origin = primary.coords
            random.next = 0
            random.then = 5
            random.then = 7

            shoot(deps, primary)

            val delay = secondary.pendingHits().single().second
            assertTrue(delay > 1)
            assertEquals(HITPOINTS, secondary.hitpoints)
            secondary.telejump(origin.translateX(5))
            primary.telejump(origin.translateZ(5))
            outside.telejump(origin)
            val arrival = spawnNpc(origin, dummy)
            advance(ticks = delay)
            assertEquals(HITPOINTS - 5, primary.hitpoints)
            assertEquals(HITPOINTS - 7, secondary.hitpoints)
            assertEquals(HITPOINTS, outside.hitpoints)
            assertEquals(HITPOINTS, arrival.hitpoints)
        }

    @Test
    fun GameTestState.`invalid NPCs and noncombatants are excluded from splash`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val invalid =
                listOf(
                    spawnNpc(primary.coords, dummy) { hidden = true },
                    spawnNpc(primary.coords, dummy) { hitpoints = 0 },
                    spawnNpc(primary.coords, dummy) { delay = 100 },
                    spawnNpc(primary.coords, npcTypeFactory.create { hitpoints = HITPOINTS }),
                )
            markMulti(deps, listOf(primary))
            random.next = 0
            random.then = 5
            random.then = SENTINEL
            shoot(deps, primary)
            for (npc in invalid) assertTrue(npc.pendingHits().isEmpty())
            assertEquals(SENTINEL, random.next)
        }

    @Test
    fun GameTestState.`splash respects multiway areas for attacker primary and secondaries`() {
        for (missing in 0..2) {
            runChinchompaTest { deps ->
                val primary = spawnPrimary()
                val secondary = spawnNpc(primary.coords.translateX(1), dummy)
                val tiles = listOf(player.coords, primary.coords, secondary.coords)
                for ((index, tile) in tiles.withIndex()) {
                    allocZoneCollision(tile)
                    if (index != missing) {
                        deps.areas.registerAll(tile, listOf(areas.multiway.id.toShort()).iterator())
                    }
                }
                random.next = 0
                random.then = 5
                shoot(deps, primary)
                assertEquals(1, primary.pendingHits().size)
                assertTrue(secondary.pendingHits().isEmpty())
            }
        }
    }

    @Test
    fun GameTestState
        .`last chinchompa retains its max hit and hit weapon even with Avas equipped`() =
        runChinchompaTest { deps ->
            val primary = spawnPrimary()
            val secondary = spawnNpc(primary.coords, dummy)
            markMulti(deps, listOf(primary, secondary))
            equip(count = 1)
            player.back = InvObj(objs.avas_assembler)
            var maxHit = 0
            player.withProtectedAccess {
                maxHit =
                    deps.manager.calculateRangedMaxHit(
                        this,
                        primary,
                        RangedAttackType.Heavy,
                        RangedAttackStyle.Rapid,
                        1.0,
                        0,
                    )
            }
            assertTrue(maxHit > 1)
            random.next = 0
            random.then = maxHit
            random.then = maxHit
            random.then = SENTINEL
            shoot(deps, primary)
            for (npc in listOf(primary, secondary)) {
                val hit = npc.pendingHits().single().first
                assertEquals(maxHit, hit.damage)
                assertTrue(hit.isRighthandObj(objs.black_chinchompa))
            }
            assertNull(player.righthand)
            assertEquals(SENTINEL, random.next)
        }

    @Test
    fun GameTestState
        .`player splash hits eligible players and excludes the thrower and safe players`() =
        runChinchompaTest { deps ->
            equip()
            val primary = registerPlayer(CoordGrid(3200, 3525))
            val secondary = registerPlayer(primary.coords)
            val safe = registerPlayer(primary.coords.translateZ(-1))
            player.placeAt(primary.coords)
            for (target in listOf(player, primary, secondary, safe)) {
                target.setBaseLevel(stats.hitpoints, 99)
                target.setCurrentLevel(stats.hitpoints, 99)
                target.setBaseLevel(stats.ranged, 99)
                target.setCurrentLevel(stats.ranged, 99)
            }
            for (tile in listOf(player, primary, secondary, safe).map { it.coords }.distinct()) {
                allocZoneCollision(tile)
                deps.areas.registerAll(tile, listOf(areas.multiway.id.toShort()).iterator())
            }
            // Register the newly logged-in players in their zones before the splash search.
            advance()
            random.next = 0
            random.then = 5
            random.then = 7
            random.then = SENTINEL
            shoot(deps, primary)
            assertEquals(SENTINEL, deps.random.of(10_000))
            advance(ticks = 3)
            assertEquals(94, primary.hitpoints)
            assertEquals(92, secondary.hitpoints)
            assertEquals(99, safe.hitpoints)
            assertEquals(99, player.hitpoints)
        }

    private fun GameTestScope.equip(count: Int = 20) {
        player.righthand = InvObj(objs.black_chinchompa, count)
        player.stats[stats.ranged] = 99
    }

    private fun GameTestScope.spawnPrimary(size: Int = 1): Npc {
        val npc = spawnNpc(CoordGrid(3200, 3200), if (size == 1) dummy else largeDummy)
        player.placeAt(npc.coords.translateZ(-9))
        equip()
        return npc
    }

    private fun GameTestScope.markMulti(deps: Dependencies, targets: List<Npc>) {
        for (tile in (targets.map { it.coords } + player.coords).distinct()) {
            allocZoneCollision(tile)
            deps.areas.registerAll(tile, listOf(areas.multiway.id.toShort()).iterator())
        }
    }

    private fun GameTestScope.shoot(deps: Dependencies, target: PathingEntity) {
        val obj = checkNotNull(player.righthand)
        val weapon = checkNotNull(deps.registry.getRanged(obj))
        val attack = CombatAttack.Ranged(obj, RangedAttackType.Heavy, RangedAttackStyle.Rapid)
        player.withProtectedAccess {
            assertTrue(
                when (target) {
                    is Npc -> weapon.attack(this, target, attack)
                    is Player -> weapon.attack(this, target, attack)
                }
            )
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

    private fun GameTestState.runChinchompaTest(testBody: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            weaponScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            WeaponAttackTypesScript::class,
            testBody = testBody,
        )

    class Dependencies
    @Inject
    constructor(
        val registry: WeaponRegistry,
        val objTypes: ObjTypeList,
        val types: AttackTypes,
        val speeds: WeaponSpeeds,
        val manager: WeaponAttackManager,
        val bonuses: WornBonuses,
        val areas: AreaIndex,
        val collision: CollisionFlagMap,
        val validator: RayCastValidator,
        val random: GameRandom,
        val zoneUpdates: ZoneUpdateMap,
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
        private const val HITPOINTS = 200
        private const val SENTINEL = 1234

        @Suppress("UNCHECKED_CAST")
        private val weaponScript =
            Class.forName("org.rsmod.api.weapons.scripts.WeaponScript").kotlin
                as KClass<out PluginScript>

        private val dummy =
            npcTypeFactory.create {
                name = "Dummy"
                size = 1
                hitpoints = HITPOINTS
                defence = 50
                op[1] = "Attack"
                defaultMode = NpcMode.None
            }

        private val largeDummy =
            npcTypeFactory.create {
                name = "Large dummy"
                size = 3
                hitpoints = HITPOINTS
                defence = 50
                op[1] = "Attack"
                defaultMode = NpcMode.None
            }
    }
}

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
import org.rsmod.api.combat.formulas.accuracy.ranged.PvNRangedAccuracy
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.npcs
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.config.refs.varps
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.inv.HeldOpScript
import org.rsmod.api.inv.HeldUOpScript
import org.rsmod.api.player.back
import org.rsmod.api.player.bonus.WornBonuses
import org.rsmod.api.player.quiver
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.player.worn.Blowpipe
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.specials.SpecialAttack
import org.rsmod.api.specials.SpecialAttackModule
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.specials.SpecialAttackType
import org.rsmod.api.spells.MagicSpellModule
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
import org.rsmod.game.seq.EntitySeq
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript

class ToxicBlowpipeTest {
    @ParameterizedTest
    @CsvSource(
        "1000, 500, 10, true",
        "500, 0, 10, true",
        "499, 499, 10, false",
        "1000, 1000, 0, false",
    )
    fun `combat deducts fifty percent energy only when the special fires`(
        initialEnergy: Int,
        remainingEnergy: Int,
        scales: Int,
        specialFires: Boolean,
        state: GameTestState,
    ) =
        state.runBlowpipeTest(combat = true) { deps ->
            val npc = spawnDummy()
            equip(scales = scales)
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 50)
            allocZoneCollision(player.coords)
            allocZoneCollision(npc.coords)
            advance(ticks = 20) // Let the test player's initial combat clocks expire.
            player.specialEnergy = initialEnergy
            player.selectedSpecial = SpecialAttackType.Weapon.varValue
            rollShot(damage = 7)
            player.opNpc2(npc)
            advance()
            assertEquals(remainingEnergy, player.vars[varps.sa_energy])
            assertEquals(if (specialFires) 53 else 50, player.hitpoints)
            if (scales > 0) {
                assertEquals(1, npc.pendingHits().size)
                assertEquals(9, Blowpipe.darts(player.righthand))
            } else {
                assertTrue(npc.pendingHits().isEmpty())
                assertEquals(10, Blowpipe.darts(player.righthand))
            }
        }

    @Test
    fun GameTestState.`adjacency uses the edge of a large enemy including diagonal tiles`() {
        for (diagonal in listOf(false, true)) {
            runBlowpipeTest { deps ->
                val npc =
                    spawnNpc(
                        CoordGrid(3200, 3200),
                        npcTypeFactory.create {
                            name = "Large dummy"
                            size = 3
                            hitpoints = 200
                            defaultMode = NpcMode.None
                        },
                    )
                player.placeAt(npc.coords.translate(3, if (diagonal) 3 else 1))
                equip()
                rollShot(damage = 7)
                shoot(deps, npc)
                rollShot(damage = 7)
                assertTrue(useSpecial(deps, npc))
                val projectiles =
                    checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)])
                        .filterIsInstance<MapProjAnimV2>()
                assertEquals(projectiles.first().endTime, projectiles.last().endTime)
                val hits = npc.pendingHits()
                assertEquals(hits.first().second, hits.last().second)
            }
        }
    }

    @Test
    fun GameTestState.`both loaded cosmetics have a fifty percent ranged special`() =
        runBlowpipeTest { deps ->
            for (pipe in listOf(objs.toxic_blowpipe, objs.blazing_blowpipe)) {
                val special = deps.specials[InvObj(pipe)]
                assertTrue(special is SpecialAttack.Ranged)
                assertEquals(500, (special as SpecialAttack.Ranged).energyInHundreds)
            }
        }

    @ParameterizedTest
    @CsvSource("1, false", "2, false", "5, false", "7, false", "1, true", "5, true")
    fun `special projectile takes one extra tick except at adjacent range`(
        distance: Int,
        playerTarget: Boolean,
        state: GameTestState,
    ) {
        for (pipe in listOf(objs.toxic_blowpipe, objs.blazing_blowpipe)) {
            state.runBlowpipeTest { deps ->
                val target =
                    if (playerTarget) {
                        registerPlayer(CoordGrid(3200, 3200)).also {
                            it.setBaseLevel(stats.hitpoints, 99)
                            it.setCurrentLevel(stats.hitpoints, 99)
                        }
                    } else {
                        spawnDummy()
                    }
                player.placeAt(target.coords.translateZ(-distance))
                equip(pipe = pipe)
                val style =
                    if (distance == 7) RangedAttackStyle.Longrange else RangedAttackStyle.Rapid
                rollShot(damage = 7)
                shoot(deps, target, style)
                val normalAnimation = player.pendingSequence.id
                player.pendingSequence = EntitySeq.NULL
                val normal =
                    checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)])
                        .filterIsInstance<MapProjAnimV2>()
                        .single()
                rollShot(damage = 7)
                assertTrue(useSpecial(deps, target, style))
                assertEquals(normalAnimation, player.pendingSequence.id)
                val special =
                    checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)])
                        .filterIsInstance<MapProjAnimV2>()
                        .last()
                assertEquals(1043, special.id)
                assertTrue(normal.id != special.id)
                assertEquals(normal.endTime + if (distance > 1) 30 else 0, special.endTime)
                assertEquals(
                    if (distance == 7) 3 else 2,
                    player.actionDelay - player.currentMapClock,
                )
                if (target is Npc) {
                    val hits = target.pendingHits()
                    assertEquals(
                        hits.first().second + if (distance > 1) 1 else 0,
                        hits.last().second,
                    )
                }
                assertEquals(8, Blowpipe.darts(player.righthand))
                assertEquals(8, Blowpipe.scales(player.righthand))
                val sound =
                    checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)])
                        .filterIsInstance<SoundArea>()
                        .last()
                assertEquals(0, sound.delay)
                assertEquals(ZoneGrid.from(player.coords).x, sound.xInZone)
                assertEquals(ZoneGrid.from(player.coords).z, sound.zInZone)
            }
        }
    }

    @ParameterizedTest
    @CsvSource("0, 50, 50", "1, 50, 50", "7, 50, 53", "8, 50, 54", "19, 95, 99")
    fun `healing rounds down before enemy HP clamping and never overheals`(
        damage: Int,
        initialHp: Int,
        healedHp: Int,
        state: GameTestState,
    ) {
        for (playerTarget in listOf(false, true)) {
            state.runBlowpipeTest { deps ->
                val target =
                    if (playerTarget) {
                        registerPlayer(CoordGrid(3200, 3200)).also {
                            it.setBaseLevel(stats.hitpoints, 99)
                            it.setCurrentLevel(stats.hitpoints, 2)
                        }
                    } else {
                        spawnDummy().also {
                            it.hitpoints = 2
                            it.regenClock = Int.MAX_VALUE
                        }
                    }
                player.placeAt(target.coords.translateZ(-1))
                player.setBaseLevel(stats.hitpoints, 99)
                player.setCurrentLevel(stats.hitpoints, initialHp)
                equip()
                random.next = if (damage == 0) 9999 else 0
                if (damage > 0) random.then = damage
                random.then = 4
                random.then = 1

                assertTrue(useSpecial(deps, target))
                assertEquals(healedHp, player.hitpoints)
                advance(ticks = 2)
                assertEquals(
                    (2 - damage).coerceAtLeast(0),
                    when (target) {
                        is Npc -> target.hitpoints
                        is Player -> target.hitpoints
                    },
                )
            }
        }
    }

    @Test
    fun GameTestState.`special doubles accuracy at the exact hit chance boundary`() {
        for (miss in listOf(false, true)) {
            runBlowpipeTest { deps ->
                val npc = spawnDummy()
                equip()
                fun chance(multiplier: Double) =
                    deps.accuracy.getHitChance(
                        player,
                        npc,
                        RangedAttackType.Light,
                        RangedAttackStyle.Rapid,
                        RangedAttackType.Light,
                        multiplier,
                    )
                val threshold = chance(2.0)
                assertTrue(threshold > chance(1.99))
                random.next = threshold - if (miss) 0 else 1
                if (!miss) random.then = 7
                random.then = 4
                random.then = 1
                assertTrue(useSpecial(deps, npc))
                assertEquals(if (miss) 0 else 7, npc.pendingHits().single().first.damage)
            }
        }
    }

    @Test
    fun GameTestState.`special boosts max hit by fifty percent including the last dart`() =
        runBlowpipeTest { deps ->
            val npc =
                spawnNpc(
                    CoordGrid(3200, 3200),
                    npcTypeFactory.create(id = npcs.combat_dummy.id) {
                        name = "Max hit dummy"
                        hitpoints = 200
                        defaultMode = NpcMode.None
                    },
                )
            player.placeAt(npc.coords.translateZ(1))
            equip(darts = 1)
            val base =
                deps.attacks.calculateRangedMaxHit(
                    player,
                    npc,
                    RangedAttackType.Light,
                    RangedAttackStyle.Rapid,
                    1.0,
                    0,
                )
            random.next = 4 // No accuracy or damage roll on a max hit dummy.
            random.then = 1
            assertTrue(useSpecial(deps, npc))
            assertEquals(20, base)
            assertEquals(30, npc.pendingHits().single().first.damage)
            assertEquals(0, Blowpipe.darts(player.righthand))
        }

    @Test
    fun GameTestState
        .`special refuses missing ammunition without requesting an energy deduction`() {
        for ((scales, darts) in listOf(10 to 0, 0 to 10)) {
            runBlowpipeTest { deps ->
                val npc = spawnDummy()
                equip(scales = scales, darts = darts)
                val original = player.righthand
                assertFalse(useSpecial(deps, npc))
                assertEquals(original, player.righthand)
                assertTrue(npc.pendingHits().isEmpty())
                assertTrue(
                    deps.zoneUpdates[ZoneKey.from(player.coords)]?.toList().orEmpty().none {
                        it is SoundArea || it is MapProjAnimV2
                    }
                )
            }
        }
    }

    private fun GameTestScope.useSpecial(
        deps: Dependencies,
        target: PathingEntity,
        style: RangedAttackStyle = RangedAttackStyle.Rapid,
    ): Boolean {
        val obj = checkNotNull(player.righthand)
        val special = checkNotNull(deps.specials[obj]) as SpecialAttack.Ranged
        val attack = CombatAttack.Ranged(obj, RangedAttackType.Light, style)
        var activated = false
        player.withProtectedAccess { activated = special.attack(this, target, attack) }
        return activated
    }

    @ParameterizedTest
    @CsvSource("1, 0", "1, 9999", "5, 0", "5, 9999")
    fun `each shot plays one sound immediately at the player tile`(
        distance: Int,
        accuracyRoll: Int,
        state: GameTestState,
    ) {
        for (pipe in listOf(objs.toxic_blowpipe, objs.blazing_blowpipe)) {
            for (playerTarget in listOf(false, true)) {
                state.runBlowpipeTest { deps ->
                    val target =
                        if (playerTarget) {
                            registerPlayer(CoordGrid(3200, 3200)).also {
                                it.setBaseLevel(stats.hitpoints, 99)
                                it.setCurrentLevel(stats.hitpoints, 99)
                            }
                        } else {
                            spawnDummy()
                        }
                    player.placeAt(target.coords.translateZ(-distance))
                    equip(pipe = pipe)
                    random.next = accuracyRoll
                    if (accuracyRoll == 0) random.then = 5 // Damage on a hit.
                    random.then = 4 // No ground drop.
                    random.then = 1 // Consume a scale.

                    shoot(deps, target)

                    val sound =
                        listOf(ZoneKey.from(player.coords), ZoneKey.from(target.coords))
                            .distinct()
                            .flatMap { deps.zoneUpdates[it]?.toList().orEmpty() }
                            .filterIsInstance<SoundArea>()
                            .single()
                    assertEquals(0, sound.delay)
                    assertEquals(synths.dart.id, sound.id)
                    assertEquals(1, sound.loops)
                    assertEquals(ZoneGrid.from(player.coords).x, sound.xInZone)
                    assertEquals(ZoneGrid.from(player.coords).z, sound.zInZone)
                    assertTrue(
                        checkNotNull(deps.zoneUpdates[ZoneKey.from(player.coords)]).any {
                            it === sound
                        }
                    )
                }
            }
        }
    }

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

    private fun GameTestState.runBlowpipeTest(
        combat: Boolean = false,
        testBody: GameTestScope.(Dependencies) -> Unit,
    ) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            weaponScript,
            specialAttackScript,
            NpcHitScript::class,
            PlayerHitScript::class,
            WeaponAttackStylesScript::class,
            WeaponAttackTypesScript::class,
            ToxicBlowpipeCharging::class,
            HeldOpScript::class,
            HeldUOpScript::class,
            *if (combat) arrayOf(pvnCombatScript) else emptyArray(),
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
        val zoneUpdates: ZoneUpdateMap,
        val specials: SpecialAttackRegistry,
        val accuracy: PvNRangedAccuracy,
    )

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(WeaponModule())
            install(SpecialAttackModule())
            install(MagicSpellModule())
            install(RangedWeaponsModule())
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }

    private companion object {
        private var Player.specialEnergy by intVarp(varps.sa_energy)
        private var Player.selectedSpecial by intVarp(varps.sa_attack)

        @Suppress("UNCHECKED_CAST")
        private val weaponScript =
            Class.forName("org.rsmod.api.weapons.scripts.WeaponScript").kotlin
                as KClass<out PluginScript>

        @Suppress("UNCHECKED_CAST")
        private val specialAttackScript =
            Class.forName("org.rsmod.api.specials.scripts.SpecialAttackScript").kotlin
                as KClass<out PluginScript>

        @Suppress("UNCHECKED_CAST")
        private val pvnCombatScript =
            Class.forName("org.rsmod.api.combat.scripts.PvNCombatScript").kotlin
                as KClass<out PluginScript>

        private val dummy =
            npcTypeFactory.create {
                name = "Dummy"
                size = 1
                hitpoints = 200
                defence = 50
                op[1] = "Attack"
                defaultMode = NpcMode.None
            }
    }
}

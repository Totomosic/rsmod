@file:OptIn(org.rsmod.annotations.InternalApi::class)

package org.rsmod.api.combat

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.PlayerAttackTarget
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.scripts.PvNCombatScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.spotanims
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.freeze.FreezeScript
import org.rsmod.api.freeze.freeze
import org.rsmod.api.freeze.isFreezeImmune
import org.rsmod.api.freeze.isFrozen
import org.rsmod.api.freeze.remainingFreezeCycles
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackMap
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.api.spells.runes.combo.ComboRuneRepository
import org.rsmod.api.spells.runes.combo.ComboRuneScript
import org.rsmod.api.spells.runes.compact.CompactRuneRepository
import org.rsmod.api.spells.runes.compact.CompactRuneScript
import org.rsmod.api.spells.runes.fake.FakeRuneRepository
import org.rsmod.api.spells.runes.fake.FakeRuneScript
import org.rsmod.api.spells.runes.staves.StaffSubstituteRepository
import org.rsmod.api.spells.runes.staves.StaffSubstituteScript
import org.rsmod.api.spells.runes.subs.RuneSubstituteRepository
import org.rsmod.api.spells.runes.subs.RuneSubstituteScript
import org.rsmod.api.spells.runes.unlimited.UnlimitedRuneRepository
import org.rsmod.api.spells.runes.unlimited.UnlimitedRuneScript
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.content.skills.magic.spell.attacks.SpellAttacksModule
import org.rsmod.content.skills.magic.utility.DeathCharge
import org.rsmod.content.skills.magic.utility.SpellbookSwap
import org.rsmod.content.skills.magic.utility.Thralls
import org.rsmod.content.skills.magic.utility.death_charge_components
import org.rsmod.content.skills.magic.utility.death_charge_varbits
import org.rsmod.content.skills.magic.utility.spellbook_swap_components
import org.rsmod.content.skills.magic.utility.spellbook_swap_varps
import org.rsmod.content.skills.magic.utility.thrall_components
import org.rsmod.content.skills.magic.utility.thrall_varbits
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.inv.InvObj
import org.rsmod.game.spot.EntitySpotanim
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.util.ParamMap
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

// LOS tests temporarily change shared collision flags; keep casts in this class sequential.
@Execution(ExecutionMode.SAME_THREAD)
class AncientSpellsTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
    fun `utility casts preserve ongoing attacks and their timing`(
        variant: Int,
        state: GameTestState,
    ) =
        state.runAncientTest(
            PvNCombatScript::class,
            WeaponAttackTypesScript::class,
            DeathCharge::class,
            Thralls::class,
        ) {
            val npc = spawnTarget()
            player.placeAt(npc.coords.translateZ(1))
            prepareUtilityCast(variant)
            val owner = player
            val attacks = mutableListOf<Int>()
            eventBus.subscribeUnbound(PlayerAttackTarget::class.java) {
                if (player === owner && target === npc) attacks += mapClock.cycle
            }
            advance(ticks = 25)
            player.opNpc2(npc)
            advance(ticks = 1)
            val firstAttack = attacks.single()
            val nextAttack = player.actionDelay
            castUtility(variant)
            assertEquals(nextAttack, player.actionDelay)
            assertNotNull(player.interaction)
            advance(ticks = 8)
            assertEquals(listOf(firstAttack, firstAttack + 4, firstAttack + 8), attacks)
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
    fun `utility casts preserve attacks clicked earlier in the casting tick`(
        variant: Int,
        state: GameTestState,
    ) =
        state.runAncientTest(
            PvNCombatScript::class,
            WeaponAttackTypesScript::class,
            DeathCharge::class,
            Thralls::class,
        ) {
            val npc = spawnTarget()
            prepareUtilityCast(variant)
            val owner = player
            val attacks = mutableListOf<Int>()
            eventBus.subscribeUnbound(PlayerAttackTarget::class.java) {
                if (player === owner && target === npc) attacks += mapClock.cycle
            }
            advance(ticks = 25)
            player.opNpc2(npc)
            castUtility(variant)
            assertNotNull(player.interaction)
            advance(ticks = 10)
            assertTrue(attacks.isNotEmpty())
        }

    private fun GameTestScope.prepareUtilityCast(variant: Int) {
        VarPlayerIntMapSetter.set(player, varbits.spellbook, 3)
        if (variant >= 4) VarPlayerIntMapSetter.set(player, spellbook_swap_varps.active, 1)
        player.setCurrentLevel(stats.prayer, 99)
        player.inv[1] = InvObj(checkNotNull(objTypes[25818])) // Book of the dead.
        player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
    }

    private fun GameTestScope.castUtility(variant: Int) {
        val buttons =
            listOf(
                death_charge_components.cast,
                thrall_components.ghost,
                thrall_components.skeleton,
                thrall_components.zombie,
            )
        player.ifButton(buttons[variant % 4])
        advance(ticks = 1)
        val active = if (variant % 4 == 0) death_charge_varbits.active else thrall_varbits.active
        assertEquals(1, player.vars[active])
        assertEquals(if (variant >= 4) 2 else 3, player.vars[varbits.spellbook])
    }

    @Test
    fun GameTestState.`ongoing combat pauses during SBS and continues without another click`() =
        runAncientTest(PvNCombatScript::class, WeaponAttackTypesScript::class) {
            val npc = spawnTarget()
            player.placeAt(npc.coords.translateZ(1))
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 2)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            val owner = player
            val attacks = mutableListOf<Npc>()
            eventBus.subscribeUnbound(PlayerAttackTarget::class.java) {
                if (player === owner) attacks += target
            }
            advance(ticks = 25)
            player.opNpc2(npc)
            advance(ticks = 1)
            assertEquals(listOf(npc), attacks)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            advance(ticks = 3)
            assertEquals(listOf(npc), attacks)
            advance(ticks = 1)
            assertEquals(0, player.vars[varbits.spellbook])
            assertEquals(listOf(npc, npc), attacks)
            advance(ticks = 4)
            assertEquals(listOf(npc, npc, npc), attacks)
        }

    @ParameterizedTest
    @ValueSource(ints = [1, 6])
    fun `attack clicked before SBS in the same tick resumes when the stall ends`(
        distance: Int,
        state: GameTestState,
    ) =
        state.runAncientTest(PvNCombatScript::class, WeaponAttackTypesScript::class) {
            val npc = spawnTarget()
            player.placeAt(npc.coords.translateZ(distance))
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 2)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            val owner = player
            val attackTicks = mutableListOf<Int>()
            eventBus.subscribeUnbound(PlayerAttackTarget::class.java) {
                if (player === owner && target === npc) attackTicks += mapClock.cycle
            }
            // Initial combat timestamps are zero; allow the initial combat window to pass.
            advance(ticks = 25)
            val start = player.coords
            player.opNpc2(npc)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            advance(ticks = 1)
            assertTrue(attackTicks.isEmpty())
            advance(ticks = 2)
            assertTrue(attackTicks.isEmpty())
            assertEquals(start, player.coords)
            advance(ticks = 1)
            assertEquals(0, player.vars[varbits.spellbook])
            assertNoMessageSent()
            if (distance == 1) {
                assertEquals(1, attackTicks.size)
                assertTrue(npc.pendingHits().isNotEmpty())
            } else {
                assertTrue(attackTicks.isEmpty())
                assertNotEquals(start, player.coords)
                advance(ticks = 10)
                assertTrue(attackTicks.isNotEmpty())
            }
        }

    @Test
    fun GameTestState.`attack clicked after SBS in the same tick does not resume`() =
        runAncientTest(PvNCombatScript::class, WeaponAttackTypesScript::class) {
            val npc = spawnTarget()
            player.placeAt(npc.coords.translateZ(1))
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 2)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            val owner = player
            val attacks = mutableListOf<Npc>()
            eventBus.subscribeUnbound(PlayerAttackTarget::class.java) {
                if (player === owner) attacks += target
            }
            advance(ticks = 25)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            player.opNpc2(npc)
            advance(ticks = 10)
            assertEquals(0, player.vars[varbits.spellbook])
            assertTrue(attacks.isEmpty())
            assertTrue(npc.pendingHits().isEmpty())
        }

    @Test
    fun GameTestState.`spellbook swap returns before ancient damage lands including splashes`() =
        runAncientTest { deps ->
            val npc = spawnBarragePrimary()
            advance()
            for (splash in listOf(false, true)) {
                VarPlayerIntMapSetter.set(player, varbits.spellbook, 1)
                VarPlayerIntMapSetter.set(player, spellbook_swap_varps.active, 1)
                VarPlayerIntMapSetter.set(player, varbits.autocast_set, 1)
                VarPlayerIntMapSetter.set(player, varbits.autocast_spell, 1)
                random.next = if (splash) 9_999 else 0
                if (!splash) random.then = 10
                cast(deps, npc)
                assertEquals(2, player.vars[varbits.spellbook])
                assertEquals(0, player.vars[spellbook_swap_varps.active])
                assertEquals(0, player.vars[varbits.autocast_set])
                assertEquals(0, player.vars[varbits.autocast_spell])
                assertEquals(if (splash) 0 else 10, npc.pendingHits().single().damage)
                advance(ticks = 6)
                assertEquals(190, npc.hitpoints)
            }
        }

    @Test
    fun GameTestState.`blood barrage heals from total rolls before target health clamping`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val targets =
                listOf(primary) +
                    List(2) { spawnNpc(primary.coords, primary.visType) } +
                    spawnNpc(primary.coords.translate(1, 1), primary.visType)
            val outside = spawnNpc(primary.coords.translateX(2), primary.visType)
            markMulti(deps, targets + outside)
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 40)
            player.inv[0] = InvObj(objs.blood_rune, 8)
            player.inv[1] = InvObj(objs.death_rune, 8)
            player.inv[2] = InvObj(objs.soul_rune, 2)
            advance()
            // The first NPC tick initializes its stats, so lower HP after that initialization.
            for (npc in targets + outside) npc.hitpoints = 1
            random.next = 0
            random.then = 29
            random.then = 0
            random.then = 3
            random.then = 9_999
            random.then = 0
            random.then = 4
            random.then = 1234

            cast(deps, primary, objs.spell_blood_barrage)

            // floor((29 + 3 + 0 + 4) / 4) = 9, rather than 8 from rounding each target.
            assertEquals(49, player.stats[stats.hitpoints])
            assertEquals(
                listOf(0, 3, 4, 29),
                targets.map { it.pendingHits().single().damage }.sorted(),
            )
            assertTrue(targets.all { !it.isFrozen() && it.hitpoints == 1 })
            assertTrue(outside.pendingHits().isEmpty())
            assertEquals(1234, deps.random.of(10_000))
            assertEquals(4, player.inv[0]?.count)
            assertEquals(4, player.inv[1]?.count)
            assertEquals(1, player.inv[2]?.count)
            assertEquals(player.currentMapClock + 5, player.actionDelay)
            advance(ticks = 6)
            assertEquals(listOf(0, 0, 0, 1), targets.map { it.hitpoints }.sorted())
            assertEquals(1, outside.hitpoints)
            assertEquals(49, player.stats[stats.hitpoints])
        }

    @Test
    fun GameTestState.`blood barrage can heal from secondary hits when its primary splashes`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val secondary = spawnNpc(primary.coords, primary.visType)
            markMulti(deps, listOf(primary, secondary))
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 50)
            advance()
            random.next = 9_999
            random.then = 0
            random.then = 20
            cast(deps, primary, objs.spell_blood_barrage)
            assertEquals(55, player.stats[stats.hitpoints])
            assertFalse(primary.isFrozen())
            assertFalse(secondary.isFrozen())
            advance(ticks = 6)
            assertEquals(200, primary.hitpoints)
            assertEquals(180, secondary.hitpoints)
        }

    @Test
    fun GameTestState.`blood barrage splashes never heal or freeze`() = runAncientTest { deps ->
        val primary = spawnBarragePrimary()
        val secondary = spawnNpc(primary.coords, primary.visType)
        markMulti(deps, listOf(primary, secondary))
        player.setBaseLevel(stats.hitpoints, 99)
        player.setCurrentLevel(stats.hitpoints, 50)
        advance()
        random.next = 9_999
        random.then = 9_999
        cast(deps, primary, objs.spell_blood_barrage)
        assertEquals(50, player.stats[stats.hitpoints])
        assertFalse(primary.isFrozen())
        assertFalse(secondary.isFrozen())
        advance(ticks = 6)
        assertEquals(200, primary.hitpoints)
        assertEquals(200, secondary.hitpoints)
        assertEquals(50, player.stats[stats.hitpoints])
    }

    @Test
    fun GameTestState.`blood barrage healing is capped at the caster base hitpoints`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 98)
            advance()
            random.next = 0
            random.then = 29
            cast(deps, primary, objs.spell_blood_barrage)
            assertEquals(99, player.stats[stats.hitpoints])
        }

    @Test
    fun GameTestState.`blood barrage healing includes prayer and gear boosted damage rolls`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val secondary = spawnNpc(primary.coords, primary.visType)
            markMulti(deps, listOf(primary, secondary))
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 40)
            VarPlayerIntMapSetter.set(player, varbits.augury, 1)
            player.worn[Wearpos.Hands.slot] = InvObj(objs.tormented_bracelet)
            advance()
            random.next = 0
            random.then = 31
            random.then = 0
            random.then = 31
            cast(deps, primary, objs.spell_blood_barrage)
            assertEquals(55, player.stats[stats.hitpoints])
            advance(ticks = 6)
            assertEquals(169, primary.hitpoints)
            assertEquals(169, secondary.hitpoints)
        }

    @Test
    fun GameTestState.`blood barrage heals from player damage rolls without freezing anyone`() =
        runAncientTest { deps ->
            val primary = registerPlayer(CoordGrid(3200, 3525))
            val secondary = registerPlayer(primary.coords)
            val safe = registerPlayer(primary.coords.translateZ(-1))
            player.placeAt(primary.coords)
            for (target in listOf(player, primary, secondary, safe)) {
                target.setBaseLevel(stats.hitpoints, 99)
                target.setCurrentLevel(stats.hitpoints, if (target == player) 40 else 1)
                target.setBaseLevel(stats.magic, 99)
                target.setCurrentLevel(stats.magic, 99)
            }
            for (tile in listOf(player, primary, secondary, safe).map { it.coords }.distinct()) {
                allocZoneCollision(tile)
                deps.areas.registerAll(tile, listOf(areas.multiway.id.toShort()).iterator())
            }
            advance()
            random.next = 0
            random.then = 29
            random.then = 0
            random.then = 3
            cast(deps, primary, objs.spell_blood_barrage)
            assertEquals(48, player.stats[stats.hitpoints])
            assertTrue(listOf(player, primary, secondary, safe).none { it.isFrozen() })
            assertEquals(1, safe.stats[stats.hitpoints])
            advance(ticks = 6)
            assertEquals(0, primary.stats[stats.hitpoints])
            assertEquals(0, secondary.stats[stats.hitpoints])
            assertEquals(1, safe.stats[stats.hitpoints])
            assertEquals(48, player.stats[stats.hitpoints])
        }

    @Test
    fun GameTestState.`blood barrage cannot heal with an invalid book level or rune supply`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 40)
            advance()
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 0)
            cast(deps, primary, objs.spell_blood_barrage)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 1)
            player.setCurrentLevel(stats.magic, 91)
            advance(ticks = 5)
            cast(deps, primary, objs.spell_blood_barrage)
            player.setCurrentLevel(stats.magic, 99)
            player.inv[0] = null
            advance(ticks = 5)
            cast(deps, primary, objs.spell_blood_barrage)
            assertEquals(40, player.stats[stats.hitpoints])
            assertTrue(primary.pendingHits().isEmpty())
        }

    @Test
    fun GameTestState.`gear and augury increase ancient spell max hits above their base damage`() =
        runAncientTest { deps ->
            val npc = spawnTarget()
            val opponent = registerPlayer(npc.coords)
            val combinations = listOf(false to false, true to false, false to true, true to true)
            for ((obj, maxima) in
                listOf(
                    objs.spell_ice_barrage to listOf(30, 31, 31, 32),
                    objs.spell_ice_blitz to listOf(26, 27, 27, 28),
                    objs.spell_blood_barrage to listOf(29, 30, 30, 31),
                )) {
                val spell = checkNotNull(deps.spells.getObjSpell(obj))
                for ((index, combination) in combinations.withIndex()) {
                    val (prayer, gear) = combination
                    VarPlayerIntMapSetter.set(player, varbits.augury, if (prayer) 1 else 0)
                    player.worn[Wearpos.Hands.slot] =
                        if (gear) InvObj(objs.tormented_bracelet) else null
                    for (target in listOf(npc, opponent)) {
                        val damage =
                            deps.attacks.calculateSpellMaxHit(
                                source = player,
                                target = target,
                                spell = spell.obj,
                                spellbook = spell.spellbook,
                                baseMaxHit = spell.maxHit,
                                attackRate = 5,
                                sunfireRune = false,
                            )
                        assertEquals(maxima[index], damage.last)
                    }
                }
            }
        }

    @Test
    fun GameTestState.`ancient spells use fixed base max hits before prayer and gear modifiers`() =
        runAncientTest { deps ->
            val npc = spawnTarget()
            for ((obj, expected) in
                listOf(
                    objs.spell_ice_barrage to 30,
                    objs.spell_ice_blitz to 26,
                    objs.spell_blood_barrage to 29,
                )) {
                val spell = checkNotNull(deps.spells.getObjSpell(obj))
                assertEquals(expected, spell.maxHit)
                for (magicLevel in listOf(99, 112)) {
                    player.setCurrentLevel(stats.magic, magicLevel)
                    val damage =
                        deps.attacks.calculateSpellMaxHit(
                            source = player,
                            target = npc,
                            spell = spell.obj,
                            spellbook = spell.spellbook,
                            baseMaxHit = spell.maxHit,
                            attackRate = 5,
                            sunfireRune = false,
                        )
                    assertEquals(expected, damage.last)
                }
            }
        }

    @Test
    fun GameTestState.`barrage independently rolls every target even when the primary splashes`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val secondary = List(3) { spawnNpc(primary.coords, primary.visType) }
            markMulti(deps, listOf(primary) + secondary)
            player.inv[0] = InvObj(objs.water_rune, 12)
            player.inv[1] = InvObj(objs.blood_rune, 4)
            player.inv[2] = InvObj(objs.death_rune, 8)
            advance()
            random.next = 9_999
            random.then = 0
            random.then = 8
            random.then = 9_999
            random.then = 0
            random.then = 14
            random.then = 1234

            barrage(deps, primary)

            assertFalse(primary.isFrozen())
            assertEquals(listOf(0), primary.pendingHits().map { it.damage })
            assertEquals(
                listOf(0, 8, 14),
                secondary.map { it.pendingHits().single().damage }.sorted(),
            )
            for (npc in secondary) {
                assertEquals(npc.pendingHits().single().damage > 0, npc.isFrozen())
                if (npc.isFrozen()) assertEquals(32, npc.remainingFreezeCycles())
            }
            assertEquals(1234, deps.random.of(10_000))
            assertEquals(player.currentMapClock + 5, player.actionDelay)
            assertEquals(6, player.inv[0]?.count)
            assertEquals(2, player.inv[1]?.count)
            assertEquals(4, player.inv[2]?.count)
            assertEquals(200, primary.hitpoints)
            assertTrue(secondary.all { it.hitpoints == 200 })
            advance(ticks = 6)
            assertEquals(200, primary.hitpoints)
            assertEquals(listOf(186, 192, 200), secondary.map { it.hitpoints }.sorted())
        }

    @Test
    fun GameTestState
        .`barrage covers the chinchompa square including stacks without a target cap`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val targets = buildList {
                add(primary)
                for (dx in -1..1) for (dz in -1..1) {
                    add(spawnNpc(primary.coords.translate(dx, dz), primary.visType))
                }
                repeat(3) { add(spawnNpc(primary.coords, primary.visType)) }
            }
            val outside =
                listOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2).map { (dx, dz) ->
                    spawnNpc(primary.coords.translate(dx, dz), primary.visType)
                } + spawnNpc(primary.coords.translateLevel(1), primary.visType)
            markMulti(deps, targets + outside)
            advance()
            random.next = 0
            random.then = 30
            repeat(targets.size - 1) {
                random.then = 0
                random.then = 30
            }
            random.then = 1234
            barrage(deps, primary)
            for (npc in targets) {
                assertTrue(npc.isFrozen())
                assertEquals(32, npc.remainingFreezeCycles())
                assertEquals(listOf(30), npc.pendingHits().map { it.damage })
            }
            for (npc in outside) {
                assertFalse(npc.isFrozen())
                assertTrue(npc.pendingHits().isEmpty())
            }
            assertEquals(1234, deps.random.of(10_000))
            advance(ticks = 31)
            assertTrue(primary.isFrozen())
            advance()
            assertFalse(primary.isFrozen())
            assertTrue(primary.isFreezeImmune())
        }

    @Test
    fun GameTestState.`barrage includes large npcs only by their southwest coordinate`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary(size = 3)
            val inside = spawnNpc(primary.coords.translate(1, 1), primary.visType)
            val overlap = spawnNpc(primary.coords.translateX(-2), primary.visType)
            val northeast = spawnNpc(primary.coords.translate(2, 2), iceTarget())
            markMulti(deps, listOf(primary, inside, overlap, northeast))
            advance()
            random.next = 0
            random.then = 6
            random.then = 0
            random.then = 7
            barrage(deps, primary)
            assertTrue(primary.isFrozen())
            assertTrue(inside.isFrozen())
            assertEquals(listOf(7), inside.pendingHits().map { it.damage })
            assertFalse(overlap.isFrozen())
            assertFalse(northeast.isFrozen())
        }

    @Test
    fun GameTestState.`barrage requires caster primary and secondary to be in multi combat`() {
        for (missing in listOf("caster", "primary", "secondary")) {
            runAncientTest { deps ->
                val primary = spawnBarragePrimary()
                val secondary = spawnNpc(primary.coords.translateX(1), primary.visType)
                val multiCoords = buildList {
                    if (missing != "caster") add(player.coords)
                    if (missing != "primary") add(primary.coords)
                    if (missing != "secondary") add(secondary.coords)
                }
                for (tile in multiCoords) {
                    deps.areas.registerAll(tile, listOf(areas.multiway.id.toShort()).iterator())
                }
                advance()
                random.next = 0
                random.then = 10
                barrage(deps, primary)
                assertTrue(primary.isFrozen())
                assertFalse(secondary.isFrozen())
                assertTrue(secondary.pendingHits().isEmpty())
            }
        }
    }

    @Test
    fun GameTestState.`barrage requires line of sight from the primary tile`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val secondary = spawnNpc(primary.coords.translateX(1), primary.visType)
            markMulti(deps, listOf(primary, secondary))
            advance()
            val tile = secondary.coords
            val original = deps.collision[tile.x, tile.z, tile.level]
            deps.collision.add(tile.x, tile.z, tile.level, CollisionFlag.WALL_WEST_PROJ_BLOCKER)
            try {
                assertTrue(deps.validator.hasLineOfSight(player.coords, secondary.coords))
                assertFalse(deps.validator.hasLineOfSight(primary.coords, secondary.coords))
                random.next = 0
                random.then = 10
                barrage(deps, primary)
                assertFalse(secondary.isFrozen())
                assertTrue(secondary.pendingHits().isEmpty())
            } finally {
                deps.collision[tile.x, tile.z, tile.level] = original
            }
        }

    @Test
    fun GameTestState.`moving targets and arrivals during flight do not change barrage victims`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val secondary = spawnNpc(primary.coords.translateX(1), primary.visType)
            val outside = spawnNpc(primary.coords.translateX(2), primary.visType)
            markMulti(deps, listOf(primary, secondary, outside))
            advance()
            random.next = 0
            random.then = 10
            random.then = 0
            random.then = 20
            barrage(deps, primary)
            assertTrue(secondary.isFrozen())
            assertEquals(listOf(20), secondary.pendingHits().map { it.damage })
            assertTrue(outside.pendingHits().isEmpty())
            secondary.telejump(primary.coords.translateX(4))
            outside.telejump(primary.coords)
            advance(ticks = 6)
            assertEquals(190, primary.hitpoints)
            assertEquals(180, secondary.hitpoints)
            assertEquals(200, outside.hitpoints)
            assertFalse(outside.isFrozen())
        }

    @Test
    fun GameTestState.`player barrage rolls independently and excludes caster and safe players`() =
        runAncientTest { deps ->
            val primary = registerPlayer(CoordGrid(3200, 3525))
            val secondary = registerPlayer(primary.coords)
            val safe = registerPlayer(primary.coords.translateZ(-1))
            player.placeAt(primary.coords)
            for (target in listOf(player, primary, secondary, safe)) {
                target.setBaseLevel(stats.hitpoints, 99)
                target.setCurrentLevel(stats.hitpoints, 99)
                target.setBaseLevel(stats.magic, 99)
                target.setCurrentLevel(stats.magic, 99)
            }
            for (tile in listOf(player, primary, secondary, safe).map { it.coords }.distinct()) {
                allocZoneCollision(tile)
                deps.areas.registerAll(tile, listOf(areas.multiway.id.toShort()).iterator())
            }
            advance()
            random.next = 9_999
            random.then = 0
            random.then = 10
            random.then = 1234
            barrage(deps, primary)
            assertFalse(primary.isFrozen())
            assertTrue(secondary.isFrozen())
            assertEquals(32, secondary.remainingFreezeCycles())
            assertFalse(safe.isFrozen())
            assertFalse(player.isFrozen())
            assertEquals(1234, deps.random.of(10_000))
            advance(ticks = 6)
            assertEquals(99, primary.stats[stats.hitpoints])
            assertEquals(89, secondary.stats[stats.hitpoints])
            assertEquals(99, safe.stats[stats.hitpoints])
            assertEquals(99, player.stats[stats.hitpoints])
        }

    @Test
    fun GameTestState.`neither ice spell freezes a player when it splashes`() {
        for (spell in listOf(objs.spell_ice_blitz, objs.spell_ice_barrage)) {
            runAncientTest { deps ->
                val target = registerPlayer(CoordGrid(3222, 3218, 0))
                target.setBaseLevel(stats.hitpoints, 99)
                target.setCurrentLevel(stats.hitpoints, 99)
                player.placeAt(target.coords.translateZ(6))
                advance()
                random.next = 9_999
                cast(deps, target, spell)
                assertFalse(target.isFrozen())
                advance(ticks = 6)
                assertFalse(target.isFrozen())
                assertEquals(99, target.stats[stats.hitpoints])
            }
        }
    }

    @Test
    fun GameTestState.`barrage preserves existing freezes and npc resistance per target`() =
        runAncientTest { deps ->
            val primary = spawnBarragePrimary()
            val frozen = spawnNpc(primary.coords, primary.visType)
            val resistant = spawnNpc(primary.coords, iceTarget(resistance = 100))
            markMulti(deps, listOf(primary, frozen, resistant))
            advance()
            frozen.freeze(2, deps.random)
            random.next = 0
            random.then = 10
            repeat(2) {
                random.then = 0
                random.then = 10
            }
            barrage(deps, primary)
            assertEquals(32, primary.remainingFreezeCycles())
            assertEquals(2, frozen.remainingFreezeCycles())
            assertFalse(resistant.isFrozen())
            advance(ticks = 6)
            for (npc in listOf(primary, frozen, resistant)) assertEquals(190, npc.hitpoints)
        }

    @Test
    fun GameTestState.`ice blitz freezes only its target at launch and damages it later`() =
        runAncientTest { deps ->
            val npc = spawnTarget()
            val bystander = spawnTarget()
            markMulti(deps, listOf(npc, bystander))
            advance()
            random.next = 0
            random.then = 26
            cast(deps, npc)
            assertTrue(npc.isFrozen())
            assertEquals(24, npc.remainingFreezeCycles())
            assertEquals(200, npc.hitpoints)
            assertFalse(bystander.isFrozen())
            assertEquals(seqs.zaros_casting.id, player.pendingSequence.id)
            assertEquals(
                listOf(spotanims.ice_blitz_travel.id),
                player.pendingSpotanims.map { EntitySpotanim(it).id },
            )
            advance()
            assertEquals(200, npc.hitpoints)
            advance(ticks = 5)
            assertEquals(174, npc.hitpoints)
            assertEquals(200, bystander.hitpoints)
            advance(ticks = 18)
            assertFalse(npc.isFrozen())
            assertTrue(npc.isFreezeImmune())
        }

    @Test
    fun GameTestState.`manual casts cannot bypass the five tick attack delay`() =
        runAncientTest { deps ->
            val npc = spawnTarget()
            player.inv[0] = InvObj(objs.water_rune, 30)
            player.inv[1] = InvObj(objs.blood_rune, 20)
            player.inv[2] = InvObj(objs.death_rune, 20)
            advance()
            val start = player.currentMapClock
            random.next = 0
            random.then = 1
            cast(deps, npc)
            assertEquals(start + 5, player.actionDelay)
            assertEquals(27, player.inv[0]?.count)
            advance(ticks = 4)
            cast(deps, npc)
            assertEquals(27, player.inv[0]?.count)
            deps.attacks.stopCombat(player)
            advance()
            random.next = 0
            random.then = 1
            cast(deps, npc)
            assertEquals(24, player.inv[0]?.count)
            assertEquals(start + 10, player.actionDelay)
        }

    @Test
    fun GameTestState.`splash consumes runes without freezing or damaging the target`() =
        runAncientTest { deps ->
            val npc = spawnTarget()
            player.inv[0] = InvObj(objs.water_rune, 3)
            player.inv[1] = InvObj(objs.blood_rune, 2)
            player.inv[2] = InvObj(objs.death_rune, 2)
            advance()
            random.next = 9_999
            cast(deps, npc)
            assertFalse(npc.isFrozen())
            assertNull(player.inv[0])
            assertNull(player.inv[1])
            assertNull(player.inv[2])
            advance(ticks = 6)
            assertEquals(200, npc.hitpoints)
        }

    @Test
    fun GameTestState.`player target cannot walk before the delayed hit lands`() =
        runAncientTest { deps ->
            val target = registerPlayer(CoordGrid(3222, 3218, 0))
            target.setBaseLevel(stats.hitpoints, 99)
            target.setCurrentLevel(stats.hitpoints, 99)
            player.placeAt(target.coords.translateZ(6))
            advance()
            random.next = 0
            random.then = 10
            cast(deps, target)
            assertTrue(target.isFrozen())
            assertEquals(24, target.remainingFreezeCycles())
            val start = target.coords
            target.moveGameClick(start.translateX(1))
            advance()
            assertEquals(start, target.coords)
            assertEquals(99, target.stats[stats.hitpoints])
            advance(ticks = 5)
            assertEquals(89, target.stats[stats.hitpoints])
        }

    @Test
    fun GameTestState.`existing freeze and post freeze immunity are respected`() =
        runAncientTest { deps ->
            val npc = spawnTarget()
            advance()
            npc.freeze(2, deps.random)
            random.next = 0
            random.then = 1
            cast(deps, npc)
            assertEquals(2, npc.remainingFreezeCycles())
            advance(ticks = 5)
            assertTrue(npc.isFreezeImmune())
            random.next = 0
            random.then = 1
            cast(deps, npc)
            assertFalse(npc.isFrozen())
            advance(ticks = 5)
            assertTrue(npc.hitpoints < 200)
        }

    @Test
    fun GameTestState.`freeze resistant npcs still take damage`() = runAncientTest { deps ->
        val npc = spawnTarget(resistance = 100)
        advance()
        random.next = 0
        random.then = 10
        cast(deps, npc)
        assertFalse(npc.isFrozen())
        advance(ticks = 6)
        assertEquals(190, npc.hitpoints)
    }

    @Test
    fun GameTestState.`wrong book low magic and missing runes prevent casting`() =
        runAncientTest { deps ->
            val npc = spawnTarget()
            advance()
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 0)
            cast(deps, npc)
            assertFalse(npc.isFrozen())
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 1)
            player.setCurrentLevel(stats.magic, 81)
            advance(ticks = 5)
            cast(deps, npc)
            assertFalse(npc.isFrozen())
            player.setCurrentLevel(stats.magic, 99)
            player.inv[0] = null
            advance(ticks = 5)
            cast(deps, npc)
            assertFalse(npc.isFrozen())
            assertTrue(player.pendingSpotanims.isEmpty())
            advance(ticks = 6)
            assertEquals(200, npc.hitpoints)
        }

    private fun GameTestScope.spawnTarget(resistance: Int = 0): Npc {
        val npc = spawnNpc(CoordGrid(3222, 3218, 0), iceTarget(resistance = resistance))
        player.placeAt(npc.coords.translateZ(6))
        return npc
    }

    private fun iceTarget(size: Int = 1, resistance: Int = 0): UnpackedNpcType =
        npcTypeFactory.create {
            name = "Ice spell target"
            this.size = size
            hitpoints = 200
            magic = 50
            defence = 50
            op[1] = "Attack"
            val resistanceParams = mapOf(params.freeze_resistance.id to resistance)
            paramMap = ParamMap(resistanceParams, resistanceParams)
        }

    private fun GameTestScope.spawnBarragePrimary(size: Int = 1): Npc {
        val npc = spawnNpc(CoordGrid(3200, 3200), iceTarget(size = size))
        player.placeAt(npc.coords.translateZ(-6))
        return npc
    }

    private fun GameTestScope.markMulti(deps: Dependencies, targets: List<Npc>) {
        for (tile in (targets.map { it.coords } + player.coords).distinct()) {
            allocZoneCollision(tile)
            deps.areas.registerAll(tile, listOf(areas.multiway.id.toShort()).iterator())
        }
    }

    private fun Npc.pendingHits(): List<Hit> = buildList {
        val iterator = queueList.iterator() ?: return@buildList
        while (iterator.hasNext()) {
            val args = iterator.next().args
            if (args is Hit) add(args)
        }
        iterator.cleanUp()
    }

    private fun GameTestScope.barrage(deps: Dependencies, target: PathingEntity) =
        cast(deps, target, objs.spell_ice_barrage)

    private fun GameTestScope.cast(
        deps: Dependencies,
        target: PathingEntity,
        spellObj: ObjType = objs.spell_ice_blitz,
    ) {
        val spell = checkNotNull(deps.spells.getObjSpell(spellObj))
        val attack = CombatAttack.Spell(weapon = null, spell = spell, defensive = false)
        player.withProtectedAccess {
            when (target) {
                is Npc -> deps.pvn.attack(this, target, attack)
                is Player -> deps.pvp.attack(this, target, attack)
            }
        }
    }

    private fun GameTestState.runAncientTest(
        vararg extraScripts: KClass<out PluginScript>,
        testBody: GameTestScope.(Dependencies) -> Unit,
    ) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
            NpcHitScript::class,
            PlayerHitScript::class,
            FreezeScript::class,
            WeaponAttackStylesScript::class,
            SpellbookSwap::class,
            *extraScripts,
        ) { deps ->
            for (map in deps.maps) {
                with(map) { deps.repo.register(deps.spellManager) }
            }
            player.setBaseLevel(stats.magic, 99)
            player.setCurrentLevel(stats.magic, 99)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 1)
            player.inv[0] = InvObj(objs.rune_pouch)
            testBody(deps)
        }

    internal class Dependencies
    @Inject
    constructor(
        val pvn: PvNCombat,
        val pvp: PvPCombat,
        val attacks: PlayerAttackManager,
        val spells: MagicSpellRegistry,
        val random: GameRandom,
        val maps: Set<SpellAttackMap>,
        val repo: SpellAttackRepository,
        val spellManager: SpellAttackManager,
        val areas: AreaIndex,
        val collision: CollisionFlagMap,
        val validator: RayCastValidator,
    )

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(MagicSpellModule())
            install(SpellAttacksModule())
            bind(SpellAttackRegistry::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(ComboRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(CompactRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(FakeRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(StaffSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(RuneSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(UnlimitedRuneRepository::class.java).`in`(Scopes.SINGLETON)
        }
    }
}

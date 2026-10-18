@file:OptIn(org.rsmod.annotations.InternalApi::class)

package org.rsmod.api.combat

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import net.rsprot.protocol.game.outgoing.sound.SynthSound
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.PlayerAttackCheck
import org.rsmod.api.combat.commons.PlayerAttackTarget
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.freeze.isFrozen
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.righthand
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.specials.SpecialAttack
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackRegistry
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.api.spells.attack.attack
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
import org.rsmod.api.testing.capture.CaptureClient
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.api.weapons.WeaponRegistry
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.api.weapons.attack
import org.rsmod.content.areas.dungeons.nylocas.NyloMaxHit
import org.rsmod.content.areas.dungeons.nylocas.NylocasBehaviour
import org.rsmod.content.areas.dungeons.nylocas.NylocasDeaths
import org.rsmod.content.areas.dungeons.nylocas.NylocasEncounter
import org.rsmod.content.areas.dungeons.nylocas.NylocasPillar
import org.rsmod.content.areas.dungeons.nylocas.NylocasRoom
import org.rsmod.content.areas.dungeons.nylocas.NylocasSize
import org.rsmod.content.areas.dungeons.nylocas.NylocasSlot
import org.rsmod.content.areas.dungeons.nylocas.NylocasSpawn
import org.rsmod.content.areas.dungeons.nylocas.NylocasStyle
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaidOptions
import org.rsmod.content.areas.dungeons.nylocas.nylocas_locs
import org.rsmod.content.areas.dungeons.nylocas.nylocas_npcs
import org.rsmod.content.areas.dungeons.nylocas.nylocas_spots
import org.rsmod.content.areas.dungeons.nylocas.tob_varbits
import org.rsmod.content.other.special.attacks.melee.DinhsBulwarkSpecialAttack
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.content.other.special.weapons.melee.ScytheOfViturWeapons
import org.rsmod.content.other.special.weapons.melee.SulphurBladesWeapons
import org.rsmod.content.other.special.weapons.ranged.BlackChinchompaWeapons
import org.rsmod.content.skills.magic.spell.attacks.ancient.AncientSpells
import org.rsmod.content.skills.magic.utility.Thralls
import org.rsmod.content.skills.magic.utility.thrall_components
import org.rsmod.game.MapClock
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.region.Region
import org.rsmod.game.spot.EntitySpotanim
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.routefinder.collision.CollisionFlagMap

class NylocasDamageTest {
    @ParameterizedTest
    @CsvSource("false", "true")
    fun `wave 21 spell then melee overkill removes the melee target before wave 22`(
        overkill: Boolean,
        state: GameTestState,
    ) =
        state.runDamageTest(NylocasDeaths::class, TheatreRaid::class, entryBarrier = true) {
            deps,
            room ->
            val barrier = placeMapLoc(room.normal[CoordGrid(3295, 4255)], nylocas_locs.barrier)
            player.telejump(room.normal[CoordGrid(3295, 4256)])
            advance()
            deps.raid.start(
                player,
                TheatreRaidOptions(startingWave = 21, nyloMaxHit = NyloMaxHit.AllWeapons),
            )
            player.opLoc1(barrier)
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advanceUntil(predicate = { player.vars[tob_varbits.progress_value] == 21 })
            val spawnCycle = deps.clock.cycle - 1
            val mage = deps.npcs.single { it.coords == room.normal[NylocasSlot.WestNorth.source] }
            // A surviving small from an earlier wave, already in melee range inside the room.
            val melee =
                spawnNylo(deps, room, NylocasStyle.Melee).apply {
                    aiTimer(0)
                    baseHitpointsLvl = NylocasSize.Small.hitpoints(1)
                    hitpoints = baseHitpointsLvl
                }
            melee.telejump(room.normal[CoordGrid(3291, 4248)])
            player.telejump(room.normal[CoordGrid(3290, 4248)])
            // Retaining the melee body fills the cap at the wave 22 check.
            repeat(17) { spawnNylo(deps, room, NylocasStyle.Melee).aiTimer(0) }
            val attacks = mutableListOf<Pair<Int, Npc>>()
            val impacts = mutableListOf<Pair<Int, Int>>()
            var removalCycle = -1
            var nextWaveCycle = -1
            var removedBeforeNextWave: Boolean? = null
            eventBus.subscribeUnbound(PlayerAttackTarget::class.java) {
                attacks += deps.clock.cycle - spawnCycle to target
            }
            eventBus.subscribeKeyed(NpcHitEvents.Impact::class.java, melee.id.toLong()) {
                if (npc === melee) impacts += deps.clock.cycle - spawnCycle to hit.damage
            }
            eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                val age = deps.clock.cycle - 1 - spawnCycle
                if (!melee.isSlotAssigned && removalCycle == -1) removalCycle = age
            }
            eventBus.subscribeUnbound(NpcStateEvents.Create::class.java) {
                if (nextWaveCycle == -1) {
                    nextWaveCycle = deps.clock.cycle - spawnCycle
                    removedBeforeNextWave = !melee.isSlotAssigned
                }
            }
            val spell = checkNotNull(deps.spells.getObjSpell(objs.spell_ice_blitz))
            val hp = melee.hitpoints
            player.withProtectedAccess {
                delay(1)
                assertTrue(player.distanceTo(mage) <= 10)
                deps.combat.attack(this, mage, CombatAttack.Spell(null, spell, false))
                assertEquals(player.currentMapClock + 5, player.actionDelay)
            }
            advance(ticks = 5)
            assertTrue(deps.attacks.isAttackDelayed(player))
            player.clearInteractionRoute()
            player.withProtectedAccess {
                delay(1)
                assertFalse(deps.attacks.isAttackDelayed(player))
                assertEquals(1, player.distanceTo(melee))
                player.righthand =
                    InvObj(
                        if (overkill) special_weapon_objs.sulphur_blades else objs.scythe_of_vitur
                    )
                deps.combat.attack(this, melee, meleeAttack().copy(weapon = player.righthand))
            }
            advance(ticks = 7)
            assertEquals(listOf(1, 6), attacks.map { it.first })
            assertTrue(attacks[0].second === mage)
            assertTrue(attacks[1].second === melee)
            assertEquals(if (overkill) listOf(hp, 0) else listOf(hp), impacts.map { it.second })
            assertEquals(if (overkill) 8 else 9, removalCycle)
            assertEquals(if (overkill) 8 else 12, nextWaveCycle)
            assertEquals(true, removedBeforeNextWave)
        }

    @Test
    fun GameTestState
        .`max hit mode applies to each scythe splat and matching chin splash victim`() =
        runDamageTest { deps, room ->
            deps.raid.start(player, TheatreRaidOptions(nyloMaxHit = NyloMaxHit.AllWeapons))
            val big = spawnNylo(deps, room, NylocasStyle.Melee, NylocasSize.Big)
            player.righthand = InvObj(objs.scythe_of_vitur)
            val maxHit =
                deps.attacks.calculateMeleeMaxHit(
                    player,
                    big,
                    MeleeAttackType.Crush,
                    MeleeAttackStyle.Accurate,
                    1.0,
                )
            random.next = 0
            player.withProtectedAccess {
                val weapon = checkNotNull(deps.weapons.getMelee(player.righthand))
                weapon.attack(this, big, meleeAttack())
            }
            assertEquals(listOf(maxHit, maxHit / 2), big.pendingHits().map { it.damage })
            deps.npcRepo.del(big, Int.MAX_VALUE)
            val targets = mixedGroup(deps, room)
            random.next = 9_999
            throwChin(deps, targets.getValue(NylocasStyle.Magic))
            val ranged = targets.getValue(NylocasStyle.Ranged)
            // Shared accuracy misses on the wrong-style primary; the matching victim still maxes.
            val rangedMax =
                deps.attacks.calculateRangedMaxHit(
                    player,
                    ranged,
                    RangedAttackType.Heavy,
                    RangedAttackStyle.Rapid,
                    1.0,
                    0,
                )
            assertEquals(rangedMax, ranged.pendingHits().single().damage)
            for (style in listOf(NylocasStyle.Melee, NylocasStyle.Magic)) {
                assertEquals(0, targets.getValue(style).pendingHits().single().damage)
            }
        }

    @Test
    fun GameTestState
        .`all weapons max hit correct styles without bypassing nulls or other raids`() =
        runDamageTest { deps, room ->
            deps.raid.start(player, TheatreRaidOptions(nyloMaxHit = NyloMaxHit.AllWeapons))
            for (style in NylocasStyle.entries) {
                val npc = spawnNylo(deps, room, style)
                random.next = 9_999
                when (style) {
                    NylocasStyle.Melee -> {
                        assertTrue(
                            deps.attacks.rollMeleeAccuracy(player, npc, null, null, null, 1.0)
                        )
                        random.next = 0
                        assertEquals(
                            deps.attacks.calculateMeleeMaxHit(player, npc, null, null, 1.0),
                            deps.attacks.rollMeleeMaxHit(player, npc, null, null, 1.0),
                        )
                    }
                    NylocasStyle.Ranged -> {
                        assertTrue(
                            deps.attacks.rollRangedAccuracy(player, npc, null, null, null, 1.0)
                        )
                        random.next = 0
                        assertEquals(
                            deps.attacks.calculateRangedMaxHit(player, npc, null, null, 1.0, 0),
                            deps.attacks.rollRangedMaxHit(player, npc, null, null, 1.0, 0),
                        )
                    }
                    NylocasStyle.Magic -> {
                        assertTrue(deps.attacks.rollStaffAccuracy(player, npc, null, 1.0))
                        random.next = 0
                        assertEquals(
                            deps.attacks.calculateStaffMaxHit(player, npc, 30, 1.0),
                            deps.attacks.rollStaffMaxHit(player, npc, 30, 1.0),
                        )
                        assertEquals(
                            30,
                            deps.attacks.rollSpellMaxHit(
                                player,
                                npc,
                                objs.spell_ice_barrage,
                                Spellbook.Ancients,
                                30,
                                5,
                                false,
                            ),
                        )
                    }
                }
                val wrong = if (style == NylocasStyle.Melee) HitType.Ranged else HitType.Melee
                assertFalse(deps.attacks.canDamage(player, npc, wrong))
                val correct =
                    when (style) {
                        NylocasStyle.Melee -> HitType.Melee
                        NylocasStyle.Ranged -> HitType.Ranged
                        NylocasStyle.Magic -> HitType.Magic
                    }
                assertFalse(deps.attacks.alwaysMaxHit(player, npc, correct))
            }
            val otherRoom = createRegion(NylocasRoom.template)
            val other = spawnNylo(deps, otherRoom, NylocasStyle.Melee)
            assertFalse(deps.attacks.alwaysMaxHit(player, other, HitType.Melee))
            val outsider = registerPlayer(coords = player.coords)
            val fresh = spawnNylo(deps, room, NylocasStyle.Melee)
            assertFalse(deps.attacks.alwaysMaxHit(outsider, fresh, HitType.Melee))
        }

    @Test
    fun GameTestState
        .`slow max hits use five tick boundary and rapid speed with spells at cast speed`() =
        runDamageTest { deps, room ->
            deps.raid.start(player, TheatreRaidOptions(nyloMaxHit = NyloMaxHit.SlowWeapons))
            val ranged = spawnNylo(deps, room, NylocasStyle.Ranged)
            // Seercull: five ticks on Accurate, four on Rapid.
            player.righthand = InvObj(findObjType { it.id == 6724 }.single())
            VarPlayerIntMapSetter.set(player, varps.com_mode, 0)
            assertTrue(deps.attacks.alwaysMaxHit(player, ranged, HitType.Ranged))
            random.next = 0
            assertEquals(40, deps.attacks.rollDamage(player, ranged, HitType.Ranged, 40))
            VarPlayerIntMapSetter.set(player, varps.com_mode, 1)
            assertFalse(deps.attacks.alwaysMaxHit(player, ranged, HitType.Ranged))
            random.next = 0
            assertEquals(1, deps.attacks.rollDamage(player, ranged, HitType.Ranged, 40))
            // Godswords remain eligible at six ticks.
            val melee = spawnNylo(deps, room, NylocasStyle.Melee)
            player.righthand = InvObj(findObjType { it.id == 11802 }.single())
            assertTrue(deps.attacks.alwaysMaxHit(player, melee, HitType.Melee))
            // Cast speed governs spell damage even with a faster weapon equipped.
            val magic = spawnNylo(deps, room, NylocasStyle.Magic)
            player.righthand = InvObj(objs.black_chinchompa)
            assertFalse(deps.attacks.alwaysMaxHit(player, magic, HitType.Magic))
            assertTrue(
                deps.attacks.rollSpellAccuracy(
                    player,
                    magic,
                    objs.spell_ice_barrage,
                    Spellbook.Ancients,
                    false,
                )
            )
            random.next = 0
            assertEquals(
                30,
                deps.attacks.rollSpellMaxHit(
                    player,
                    magic,
                    objs.spell_ice_barrage,
                    Spellbook.Ancients,
                    30,
                    5,
                    false,
                ),
            )
            assertEquals(
                0,
                deps.attacks.rollSpellMaxHit(
                    player,
                    magic,
                    objs.spell_ice_barrage,
                    Spellbook.Ancients,
                    30,
                    4,
                    false,
                ),
            )
        }

    @Test
    fun GameTestState
        .`every thrall style attempts every nylo style and wrong styles land zero without nulling`() =
        runDamageTest(Thralls::class) { deps, room ->
            prepareThralls()
            val hits = mutableMapOf<Npc, MutableList<Hit>>()
            for (style in NylocasStyle.entries) {
                val type = nylocas_npcs.incoming(NylocasSize.Small, style)
                eventBus.subscribeKeyed(NpcHitEvents.Impact::class.java, type.id.toLong()) {
                    hits.getOrPut(npc, ::mutableListOf).add(hit)
                }
            }
            for ((button, thrallStyle) in
                listOf(
                    thrall_components.ghost to NylocasStyle.Magic,
                    thrall_components.skeleton to NylocasStyle.Ranged,
                    thrall_components.zombie to NylocasStyle.Melee,
                )) {
                val targets =
                    NylocasStyle.entries.associateWith { style ->
                        spawnNylo(deps, room, style).apply {
                            aiTimer(0)
                            baseHitpointsLvl = 1000
                            hitpoints = 1000
                        }
                    }
                player.telejump(targets.values.first().coords.translateZ(-1))
                player.ifButton(button)
                advance()
                for ((style, target) in targets) {
                    eventBus.publish(PlayerAttackTarget(player, target))
                    if (style == thrallStyle) random.next = 3
                    advance(ticks = 6)
                    val impacts = hits.getValue(target)
                    assertTrue(impacts.isNotEmpty())
                    if (style == thrallStyle) assertTrue(impacts.any { it.damage > 0 })
                    else {
                        assertTrue(impacts.all { it.damage == 0 })
                        assertEquals(1000, target.hitpoints)
                        assertTrue(roll(deps, target, style) > 0)
                    }
                }
                for (target in targets.values) deps.npcRepo.del(target, Int.MAX_VALUE)
            }
        }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `a wrong style thrall zero hit can speed up a nylo death`(
        thrall: Boolean,
        state: GameTestState,
    ) =
        state.runDamageTest(Thralls::class, NylocasDeaths::class) { deps, room ->
            prepareThralls()
            val target = spawnNylo(deps, room, NylocasStyle.Magic).apply { aiTimer(0) }
            player.telejump(target.coords.translateZ(-1))
            val impacts = mutableListOf<Int>()
            eventBus.subscribeKeyed(NpcHitEvents.Impact::class.java, target.id.toLong()) {
                impacts += hit.damage
            }
            val hp = target.hitpoints
            eventBus.publish(PlayerAttackTarget(player, target))
            if (thrall) player.ifButton(thrall_components.zombie)
            advance(ticks = 3)
            // Both hits land together, with the lethal player hit queued before the thrall zero.
            target.queueHit(player, delay = 2, HitType.Magic, damage = hp)
            advance()
            if (thrall)
                assertTrue(target.pendingHits().any { it.type == HitType.Melee && it.damage == 0 })
            advance()
            assertEquals(0, target.hitpoints)
            assertEquals(if (thrall) listOf(hp, 0) else listOf(hp), impacts)
            assertEquals(thrall, target.isDelayed)
        }

    private fun GameTestScope.prepareThralls() {
        player.setCurrentLevel(stats.prayer, 99)
        player.inv[1] = InvObj(findObjType { it.id == 25818 }.single())
        VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Arceuus.varValue)
        player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
    }

    @Test
    fun GameTestState.`thralls respect current style without nulling their owner`() =
        runDamageTest { deps, room ->
            for (style in NylocasStyle.entries) {
                val npc = spawnNylo(deps, room, style)
                val required =
                    when (style) {
                        NylocasStyle.Melee -> HitType.Melee
                        NylocasStyle.Ranged -> HitType.Ranged
                        NylocasStyle.Magic -> HitType.Magic
                    }
                for (attack in listOf(HitType.Melee, HitType.Ranged, HitType.Magic)) {
                    val check = PlayerAttackCheck(player, npc, attack, isThrall = true)
                    eventBus.publish(check)
                    assertEquals(attack == required, check.allowed)
                }
                assertTrue(roll(deps, npc, style) > 0)
            }
        }

    @ParameterizedTest
    @CsvSource("Small, false", "Small, true", "Big, false", "Big, true")
    fun `every form accepts only matching attacks and wrong styles permanently null the attacker`(
        size: NylocasSize,
        fighting: Boolean,
        state: GameTestState,
    ) =
        state.runDamageTest { deps, room ->
            val teammate = registerPlayer(player.coords)
            for (style in NylocasStyle.entries) {
                for (attack in NylocasStyle.entries) {
                    val npc = spawnNylo(deps, room, style, size, fighting)
                    val damage = roll(deps, npc, attack)
                    if (style == attack) {
                        assertTrue(damage > 0)
                    } else {
                        assertEquals(0, damage)
                        assertEquals(0, roll(deps, npc, style))
                        assertTrue(roll(deps, npc, style, teammate) > 0)
                        val fresh = spawnNylo(deps, room, style, size, fighting)
                        assertTrue(roll(deps, fresh, style) > 0)
                    }
                }
            }
        }

    @ParameterizedTest
    @CsvSource("Small", "Big")
    fun `only subsequent nulled attacks play cancellation feedback`(
        size: NylocasSize,
        state: GameTestState,
    ) =
        state.runDamageTest { deps, room ->
            val npc = spawnNylo(deps, room, NylocasStyle.Melee, size)
            val teammate = registerPlayer(player.coords)
            val teammateClient = teammate.client as CaptureClient
            client.clearOutgoing()
            teammateClient.clearOutgoing()
            npc.pendingSpotanims.clear()

            // An accurate-style attack and a normal miss do not indicate immunity.
            assertTrue(roll(deps, npc, NylocasStyle.Melee) > 0)
            random.next = 9_999
            assertFalse(deps.attacks.rollMeleeAccuracy(player, npc, null, null, null, 1.0))
            assertTrue(npc.pendingSpotanims.isEmpty())
            assertTrue(client.hasNone<SynthSound>())

            // The entire attack that first nulls the target is silent, including later splats.
            assertEquals(0, roll(deps, npc, NylocasStyle.Ranged))
            assertEquals(0, roll(deps, npc, NylocasStyle.Melee))
            assertTrue(client.hasNone<SynthSound>())
            assertTrue(npc.pendingSpotanims.isEmpty())

            advance()
            client.clearOutgoing()
            npc.pendingSpotanims.clear()
            assertEquals(0, roll(deps, npc, NylocasStyle.Melee))
            assertEquals(2663, client.single<SynthSound>().id)
            val orb = EntitySpotanim(npc.pendingSpotanims.single())
            assertEquals(1558, orb.id)
            assertEquals(constants.spotanim_slot_combat, orb.slot)
            assertEquals(0, orb.delay)
            assertEquals(0, orb.height)
            assertTrue(teammateClient.hasNone<SynthSound>())

            // Further splats on the same tick do not duplicate the cue or affect teammates.
            assertEquals(0, roll(deps, npc, NylocasStyle.Melee))
            assertEquals(1, client.countOf<SynthSound>())
            assertEquals(1, npc.pendingSpotanims.size)
            assertTrue(roll(deps, npc, NylocasStyle.Melee, teammate) > 0)
            assertTrue(teammateClient.hasNone<SynthSound>())

            advance()
            client.clearOutgoing()
            npc.pendingSpotanims.clear()
            assertEquals(0, roll(deps, npc, NylocasStyle.Melee))
            assertEquals(2663, client.single<SynthSound>().id)
            assertEquals(1558, EntitySpotanim(npc.pendingSpotanims.single()).id)
        }

    @Test
    fun GameTestState.`a correct style miss does not null the target`() =
        runDamageTest { deps, room ->
            val npc = spawnNylo(deps, room, NylocasStyle.Melee)
            random.next = 9_999
            assertFalse(deps.attacks.rollMeleeAccuracy(player, npc, null, null, null, 1.0))
            assertTrue(roll(deps, npc, NylocasStyle.Melee) > 0)
        }

    @Test
    fun GameTestState.`powered staff and spell attacks share magic restrictions`() =
        runDamageTest { deps, room ->
            for (style in NylocasStyle.entries) {
                val npc = spawnNylo(deps, room, style)
                random.next = 0
                val expected = style == NylocasStyle.Magic
                assertEquals(expected, deps.attacks.rollStaffAccuracy(player, npc, null, 1.0))
                assertEquals(
                    expected,
                    deps.attacks.rollSpellAccuracy(
                        player,
                        npc,
                        objs.spell_ice_barrage,
                        Spellbook.Ancients,
                        false,
                    ),
                )
                if (!expected) assertEquals(0, roll(deps, npc, style))
            }
        }

    @Test
    fun GameTestState.`nulling survives a flicker for only that player and NPC`() =
        runDamageTest { deps, room ->
            val npc = spawnNylo(deps, room, NylocasStyle.Magic, nextStyle = NylocasStyle.Melee)
            assertEquals(0, roll(deps, npc, NylocasStyle.Ranged))
            advance(ticks = 6)
            assertEquals(nylocas_npcs.small_melee.id, npc.visType.id)
            assertEquals(0, roll(deps, npc, NylocasStyle.Melee))
            val teammate = registerPlayer(player.coords)
            assertTrue(roll(deps, npc, NylocasStyle.Melee, teammate) > 0)
        }

    @Test
    fun GameTestState.`chinchompa AOE damages ranged targets and nulls other styles`() =
        runDamageTest { deps, room ->
            val targets = mixedGroup(deps, room)
            val primary = targets.getValue(NylocasStyle.Magic)
            random.next = 0
            throwChin(deps, primary)
            assertAreaDamage(deps, targets, NylocasStyle.Ranged)
        }

    @Test
    fun GameTestState.`bulwark sweep damages melee targets and nulls other styles`() =
        runDamageTest { deps, room ->
            val targets = mixedGroup(deps, room)
            val primary = targets.getValue(NylocasStyle.Ranged)
            bash(deps, primary)
            assertEquals(listOf(0, 0), primary.pendingHits().map { it.damage })
            assertTrue(client.hasNone<SynthSound>())
            assertTrue(targets.values.all { it.pendingSpotanims.isEmpty() })
            assertAreaDamage(deps, targets, NylocasStyle.Melee)

            // End the first special's continued combat before scheduling the next attack.
            deps.attacks.stopCombat(player)
            player.clearInteractionRoute()
            advance()
            client.clearOutgoing()
            for (npc in targets.values) npc.pendingSpotanims.clear()
            bash(deps, primary)
            assertEquals(2, client.countOf<SynthSound> { it.id == 2663 })
            assertEquals(
                1,
                primary.pendingSpotanims.map { EntitySpotanim(it) }.count { it.id == 1558 },
            )
            assertAreaDamage(deps, targets, NylocasStyle.Melee, warnings = true)
        }

    @ParameterizedTest
    @CsvSource(
        "WestNorth, Small",
        "WestNorth, Big",
        "EastNorth, Small",
        "EastNorth, Big",
        "SouthWest, Small",
        "SouthWest, Big",
    )
    fun `bulwark ignores lane nylos until they reach the room`(
        slot: NylocasSlot,
        size: NylocasSize,
        state: GameTestState,
    ) =
        state.runDamageTest { deps, room ->
            val lane =
                when (slot) {
                    NylocasSlot.WestNorth -> CoordGrid(3289, 4249)
                    NylocasSlot.EastNorth -> CoordGrid(3302, 4249)
                    else -> CoordGrid(3295, 4242)
                }
            val inside =
                when (slot) {
                    NylocasSlot.WestNorth -> lane.translateX(1)
                    NylocasSlot.EastNorth -> lane.translateX(-1)
                    else -> lane.translateZ(1)
                }
            player.telejump(room.normal[inside])
            val primary = spawnNylo(deps, room, NylocasStyle.Melee)
            val candidate = spawnNylo(deps, room, NylocasStyle.Magic, size)
            advance()
            primary.aiTimer(0)
            candidate.aiTimer(0)
            primary.telejump(deps.collision, room.normal[inside.translateZ(1)])
            candidate.telejump(deps.collision, room.normal[lane])
            for (tile in listOf(player.coords, primary.coords, candidate.coords)) {
                allocZoneCollision(tile)
                deps.areas.registerAll(
                    deps.regions.normalizeCoords(tile),
                    listOf(areas.multiway.id.toShort()).iterator(),
                )
            }
            bash(deps, primary)
            assertEquals(2, primary.pendingHits().size)
            assertTrue(primary.pendingHits().all { it.damage > 0 })
            assertTrue(candidate.pendingHits().isEmpty())
            assertTrue(candidate.pendingSpotanims.isEmpty())
            // Directly targeting the lane NPC must also skip all hits and nulling.
            bash(deps, candidate)
            assertTrue(candidate.pendingHits().isEmpty())
            // Excluding a lane target must not null it for a later matching-style attack.
            assertTrue(roll(deps, candidate, NylocasStyle.Magic) > 0)

            candidate.telejump(deps.collision, room.normal[inside])
            bash(deps, primary)
            assertEquals(listOf(0), candidate.pendingHits().map { it.damage })
            assertEquals(0, roll(deps, candidate, NylocasStyle.Magic))
        }

    @ParameterizedTest
    @CsvSource("ice", "blood")
    fun `barrage checks each style without freezing or healing from nulled targets`(
        spellName: String,
        state: GameTestState,
    ) =
        state.runDamageTest { deps, room ->
            val targets = mixedGroup(deps, room)
            // Null the matching mage as well: no target may now take damage or heal the caster.
            assertEquals(0, roll(deps, targets.getValue(NylocasStyle.Magic), NylocasStyle.Melee))
            player.setCurrentLevel(stats.hitpoints, 50)
            val spellObj =
                if (spellName == "ice") objs.spell_ice_barrage else objs.spell_blood_barrage
            cast(deps, targets.getValue(NylocasStyle.Melee), spellObj)
            assertTrue(targets.values.all { it.pendingHits().single().damage == 0 })
            assertTrue(targets.values.none { it.isFrozen() })
            assertEquals(50, player.stats[stats.hitpoints])
            for ((style, npc) in targets) assertEquals(0, roll(deps, npc, style))
        }

    @Test
    fun GameTestState.`barrage can damage a mage when the primary has the wrong style`() =
        runDamageTest { deps, room ->
            val targets = mixedGroup(deps, room)
            cast(deps, targets.getValue(NylocasStyle.Ranged), objs.spell_ice_barrage)
            assertAreaDamage(deps, targets, NylocasStyle.Magic)
        }

    @Test
    fun GameTestState.`a missed chinchompa nulls wrong styles without nulling ranged victims`() =
        runDamageTest { deps, room ->
            val targets = mixedGroup(deps, room)
            random.next = 9_999
            throwChin(deps, targets.getValue(NylocasStyle.Magic))
            for ((style, npc) in targets) {
                assertEquals(0, npc.pendingHits().single().damage)
                if (style == NylocasStyle.Ranged) assertTrue(roll(deps, npc, style) > 0)
                else assertEquals(0, roll(deps, npc, style))
            }
        }

    @Test
    fun GameTestState.`scythe sweep checks every Nylocas style`() = runDamageTest { deps, room ->
        val targets = mixedGroup(deps, room)
        player.telejump(targets.getValue(NylocasStyle.Ranged).coords.translateZ(-1))
        allocZoneCollision(player.coords)
        deps.areas.registerAll(
            deps.regions.normalizeCoords(player.coords),
            listOf(areas.multiway.id.toShort()).iterator(),
        )
        player.righthand = InvObj(objs.scythe_of_vitur)
        random.next = 0
        player.withProtectedAccess {
            val weapon = checkNotNull(deps.weapons.getMelee(player.righthand))
            weapon.attack(this, targets.getValue(NylocasStyle.Ranged), meleeAttack())
        }
        assertAreaDamage(deps, targets, NylocasStyle.Melee)
    }

    @Test
    fun GameTestState.`split children do not inherit their parent nulling`() =
        runDamageTest { deps, room ->
            val parent = spawnNylo(deps, room, NylocasStyle.Melee, NylocasSize.Big)
            assertEquals(0, roll(deps, parent, NylocasStyle.Magic))
            val before = deps.npcs.toSet()
            deps.behaviour.completeDeath(parent)
            val children = deps.npcs.filter { it !in before }
            assertEquals(2, children.size)
            for (child in children) {
                val style =
                    NylocasStyle.entries.single {
                        nylocas_npcs.incoming(NylocasSize.Small, it).id == child.visType.id
                    }
                assertTrue(roll(deps, child, style) > 0)
            }
        }

    private fun GameTestScope.bash(deps: Dependencies, target: Npc) {
        player.righthand = InvObj(objs.dinhs_bulwark)
        random.next = 0
        player.withProtectedAccess {
            val special =
                checkNotNull(deps.specials[InvObj(objs.dinhs_bulwark)]) as SpecialAttack.Melee
            special.attack(this, target, meleeAttack())
        }
    }

    private fun GameTestScope.throwChin(deps: Dependencies, target: Npc) {
        player.righthand = InvObj(objs.black_chinchompa, 20)
        player.withProtectedAccess {
            val weapon = checkNotNull(deps.weapons.getRanged(InvObj(objs.black_chinchompa)))
            weapon.attack(
                this,
                target,
                CombatAttack.Ranged(
                    checkNotNull(player.righthand),
                    RangedAttackType.Heavy,
                    RangedAttackStyle.Rapid,
                ),
            )
        }
    }

    private fun GameTestScope.cast(deps: Dependencies, target: Npc, spellObj: ObjType) {
        random.next = 0
        random.then = 10
        val spell = checkNotNull(deps.spells.getObjSpell(spellObj))
        val attack = CombatAttack.Spell(null, spell, false)
        player.withProtectedAccess {
            checkNotNull(deps.spellAttacks[spellObj]).attack(this, target, attack)
        }
    }

    private fun GameTestScope.assertAreaDamage(
        deps: Dependencies,
        targets: Map<NylocasStyle, Npc>,
        matching: NylocasStyle,
        warnings: Boolean = false,
    ) {
        for ((style, npc) in targets) {
            assertTrue(npc.pendingHits().isNotEmpty())
            if (style == matching) {
                assertTrue(npc.pendingHits().all { it.damage > 0 })
                assertTrue(
                    npc.pendingSpotanims
                        .map { EntitySpotanim(it) }
                        .none { it.id == nylocas_spots.shielded.id }
                )
            } else {
                assertTrue(npc.pendingHits().all { it.damage == 0 })
                val orbs =
                    npc.pendingSpotanims
                        .map { EntitySpotanim(it) }
                        .filter { it.id == nylocas_spots.shielded.id }
                assertEquals(if (warnings) 1 else 0, orbs.size)
                if (warnings) assertEquals(constants.spotanim_slot_combat, orbs.single().slot)
                assertEquals(0, roll(deps, npc, style))
            }
        }
    }

    private fun GameTestScope.mixedGroup(deps: Dependencies, room: Region): Map<NylocasStyle, Npc> {
        val targets = NylocasStyle.entries.associateWith { spawnNylo(deps, room, it) }
        advance()
        for (npc in targets.values) npc.aiTimer(0)
        for (tile in (targets.values.map { it.coords } + player.coords).distinct()) {
            allocZoneCollision(tile)
            deps.areas.registerAll(
                deps.regions.normalizeCoords(tile),
                listOf(areas.multiway.id.toShort()).iterator(),
            )
        }
        return targets
    }

    private fun GameTestScope.spawnNylo(
        deps: Dependencies,
        room: Region,
        style: NylocasStyle,
        size: NylocasSize = NylocasSize.Small,
        fighting: Boolean = false,
        nextStyle: NylocasStyle? = null,
    ): Npc {
        val type =
            if (fighting) nylocas_npcs.fighting(size, style) else nylocas_npcs.incoming(size, style)
        val npc = Npc(deps.npcTypes[type], room.normal[CoordGrid(3295, 4248)])
        npc.mode = NpcMode.None
        npc.setHunt(0)
        deps.behaviour.add(
            npc,
            room,
            NylocasSlot.WestNorth,
            NylocasSpawn(size, style, NylocasPillar.NorthWest, styleAt5 = nextStyle),
            1,
        )
        deps.npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        return npc
    }

    private fun GameTestScope.roll(
        deps: Dependencies,
        npc: Npc,
        style: NylocasStyle,
        source: Player = player,
    ): Int {
        source.setCurrentLevel(stats.attack, 99)
        source.setCurrentLevel(stats.strength, 99)
        source.setCurrentLevel(stats.ranged, 99)
        source.setCurrentLevel(stats.magic, 99)
        random.next = 0
        return when (style) {
            NylocasStyle.Melee -> deps.attacks.rollMeleeDamage(source, npc, meleeAttack())
            NylocasStyle.Ranged ->
                deps.attacks.rollRangedDamage(
                    source,
                    npc,
                    CombatAttack.Ranged(
                        InvObj(objs.black_chinchompa),
                        RangedAttackType.Heavy,
                        RangedAttackStyle.Rapid,
                    ),
                )
            NylocasStyle.Magic ->
                if (deps.attacks.rollStaffAccuracy(source, npc, null, 1.0))
                    deps.attacks.rollStaffMaxHit(source, npc, 30, 1.0)
                else 0
        }
    }

    private fun meleeAttack() =
        CombatAttack.Melee(
            null,
            MeleeAttackType.Crush,
            MeleeAttackStyle.Accurate,
            CombatStance.Stance1,
        )

    private fun Npc.pendingHits(): List<Hit> = buildList {
        val iterator = queueList.iterator() ?: return@buildList
        while (iterator.hasNext()) {
            val args = iterator.next().args
            if (args is Hit) add(args)
        }
        iterator.cleanUp()
    }

    private fun GameTestState.runDamageTest(
        vararg extraScripts: KClass<out PluginScript>,
        entryBarrier: Boolean = false,
        testBody: GameTestScope.(Dependencies, Region) -> Unit,
    ) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            NylocasEncounter::class,
            specialAttackScript,
            NpcHitScript::class,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
            WeaponAttackStylesScript::class,
            *extraScripts,
        ) { deps ->
            with(deps.chins) { deps.weaponRepo.register(deps.weaponManager) }
            with(deps.scythe) { deps.weaponRepo.register(deps.weaponManager) }
            with(deps.sulphurBlades) { deps.weaponRepo.register(deps.weaponManager) }
            with(deps.bulwark) { deps.specialRepo.register(deps.specialManager) }
            with(deps.ancients) { deps.spellRepo.register(deps.spellManager) }
            if (entryBarrier) placeMapLoc(CoordGrid(3295, 4255), nylocas_locs.barrier)
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[CoordGrid(3295, 4244)])
            for (stat in
                listOf(stats.hitpoints, stats.attack, stats.strength, stats.ranged, stats.magic)) {
                player.setBaseLevel(stat, 99)
                player.setCurrentLevel(stat, 99)
            }
            VarPlayerIntMapSetter.set(player, varbits.spellbook, 1)
            player.inv[0] = InvObj(objs.rune_pouch)
            testBody(deps, room)
        }

    internal class Dependencies
    @Inject
    constructor(
        val raid: TheatreRaid,
        val clock: MapClock,
        internal val combat: PvNCombat,
        val behaviour: NylocasBehaviour,
        val npcRepo: NpcRepository,
        val npcs: NpcList,
        val npcTypes: NpcTypeList,
        val attacks: PlayerAttackManager,
        val areas: AreaIndex,
        val collision: CollisionFlagMap,
        val regions: RegionRegistry,
        val chins: BlackChinchompaWeapons,
        val scythe: ScytheOfViturWeapons,
        val sulphurBlades: SulphurBladesWeapons,
        val weapons: WeaponRegistry,
        val weaponRepo: WeaponRepository,
        val weaponManager: WeaponAttackManager,
        val bulwark: DinhsBulwarkSpecialAttack,
        val specials: SpecialAttackRegistry,
        val specialRepo: SpecialAttackRepository,
        val specialManager: SpecialAttackManager,
        val ancients: AncientSpells,
        val spells: MagicSpellRegistry,
        val spellAttacks: SpellAttackRegistry,
        val spellRepo: SpellAttackRepository,
        val spellManager: SpellAttackManager,
    )

    private companion object {
        @Suppress("UNCHECKED_CAST")
        val specialAttackScript =
            Class.forName("org.rsmod.api.specials.scripts.SpecialAttackScript").kotlin
                as KClass<out PluginScript>
    }

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(MagicSpellModule())
            install(WeaponModule())
            install(org.rsmod.api.specials.SpecialAttackModule())
            bind(SpellAttackRegistry::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
            bind(ComboRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(CompactRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(FakeRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(StaffSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(RuneSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(UnlimitedRuneRepository::class.java).`in`(Scopes.SINGLETON)
        }
    }
}

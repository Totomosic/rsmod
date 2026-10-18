package org.rsmod.content.skills.magic.utility

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.openrs2.cache.Cache
import org.rsmod.api.cache.Js5Archives
import org.rsmod.api.combat.commons.PlayerAttackCheck
import org.rsmod.api.combat.commons.PlayerAttackTarget
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
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
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.seq.EntitySeq
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.seq.UnpackedSeqType
import org.rsmod.map.CoordGrid

class ThrallsTest {
    @Test
    fun GameTestState
        .`thrall stops launching attacks at exactly twelve ticks of player inactivity`() =
        runThrallTest { _ ->
            val target = spawnNpc(player.coords.translateZ(1), targetType)
            val launches = mutableListOf<Int>()
            eventBus.subscribeKeyed(PlayerAttackCheck::class.java, target.id.toLong()) {
                launches += mapClock.cycle
            }
            val start = mapClock.cycle
            eventBus.publish(PlayerAttackTarget(player, target))
            cast(thrall_components.zombie)
            advance(ticks = start + 12 - mapClock.cycle)
            assertEquals(listOf(start + 4, start + 8), launches)
            advance(ticks = 8)
            assertEquals(2, launches.size)
            eventBus.publish(PlayerAttackTarget(player, target))
            advanceUntil({ launches.size > 2 }, timeoutTicks = 8)
        }

    @Test
    fun GameTestState.`each player attack refreshes the twelve tick timeout`() =
        runThrallTest { _ ->
            val target = spawnNpc(player.coords.translateZ(1), targetType)
            val launches = mutableListOf<Int>()
            eventBus.subscribeKeyed(PlayerAttackCheck::class.java, target.id.toLong()) {
                launches += mapClock.cycle
            }
            val start = mapClock.cycle
            eventBus.publish(PlayerAttackTarget(player, target))
            cast(thrall_components.zombie)
            advance(ticks = start + 8 - mapClock.cycle)
            eventBus.publish(PlayerAttackTarget(player, target))
            advance(ticks = 12)
            assertEquals(listOf(start + 4, start + 8, start + 12, start + 16), launches)
        }

    @Test
    fun GameTestState.`attacks use animation variants matching the native thrall models`() =
        runThrallTest { deps ->
            val target = spawnNpc(player.coords.translateZ(1), targetType)
            eventBus.publish(PlayerAttackTarget(player, target))
            Cache.open(Path.of(".data/cache/vanilla")).use { cache ->
                for ((button, expectedAttack) in
                    listOf(
                        thrall_components.ghost to
                            5540, // Tendril ghost, like its native idle/walk.
                        thrall_components.skeleton to 5512, // Champion's ranged attack.
                        thrall_components.zombie to 5568, // Unarmed zombie.
                    )) {
                    eventBus.publish(PlayerAttackTarget(player, target))
                    cast(button)
                    val summoned = thrall(deps)
                    val attacks = mutableListOf<Int>()
                    eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                        val seq = summoned.pendingSequence
                        if (
                            summoned.isSlotAssigned &&
                                seq != EntitySeq.NULL &&
                                seq != EntitySeq.ZERO
                        ) {
                            attacks += seq.id
                        }
                    }
                    advance(ticks = 8)
                    val attack = attacks.first()
                    val idle = cacheTypes.seqs.getValue(summoned.type.readyAnim)
                    val animation = cacheTypes.seqs.getValue(attack)
                    assertEquals(frameRig(cache, idle), frameRig(cache, animation))
                    assertEquals(setOf(expectedAttack), attacks.toSet())
                    advance(ticks = 16)
                }
            }
        }

    @Test
    fun GameTestState.`summoning preserves the player attack cooldown`() = runThrallTest { _ ->
        for (delay in listOf(0, 2, 10)) {
            player.actionDelay = mapClock.cycle + delay
            val before = player.actionDelay
            cast()
            assertEquals(before, player.actionDelay)
            advance(ticks = 16)
        }
    }

    private fun frameRig(cache: Cache, sequence: UnpackedSeqType): Int {
        // SeqTypeDecoder stores the packed frame's high bits in frameIndex.
        val group = sequence.frameIndex.first().toInt() and 0xFFFF
        val index = sequence.frameGroup.first().toInt() and 0xFFFF
        val frame = cache.read(Js5Archives.ANIMS, group, index)
        return try {
            frame.readUnsignedShort()
        } finally {
            frame.release()
        }
    }

    @Test
    fun GameTestState.`three buttons summon matching greater thralls with six prayer cost`() =
        runThrallTest { deps ->
            for ((button, id) in
                listOf(
                    thrall_components.ghost to 10880,
                    thrall_components.skeleton to 10883,
                    thrall_components.zombie to 10886,
                )) {
                val prayer = player.prayerLvl
                cast(button)
                assertEquals(id, thrall(deps).id)
                assertEquals(prayer - 6, player.prayerLvl)
                assertEquals(1, player.vars[thrall_varbits.active])
                assertEquals(1, player.vars[thrall_varbits.cooldown])
                advance(ticks = 16)
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [76, 99, 112])
    fun `lifetime snapshots magic and expires after twice that many ticks`(
        magic: Int,
        state: GameTestState,
    ) =
        state.runThrallTest { deps ->
            player.setCurrentLevel(stats.magic, magic)
            val castCycle = mapClock.cycle
            cast()
            val summoned = thrall(deps)
            player.setCurrentLevel(stats.magic, 50)
            advance(ticks = castCycle + magic * 2 - 1 - mapClock.cycle)
            assertTrue(summoned.isSlotAssigned)
            advance(ticks = 1)
            assertFalse(summoned.isSlotAssigned)
            assertEquals(0, player.vars[thrall_varbits.active])
        }

    @Test
    fun GameTestState.`recast replaces existing thrall only after shared cooldown`() =
        runThrallTest { deps ->
            val cycle = mapClock.cycle
            cast()
            val first = thrall(deps)
            cast(thrall_components.zombie)
            assertTrue(first.isSlotAssigned)
            assertEquals(93, player.prayerLvl)
            advance(ticks = cycle + 16 - mapClock.cycle)
            cast(thrall_components.zombie)
            assertFalse(first.isSlotAssigned)
            assertEquals(10886, thrall(deps).id)
            assertEquals(87, player.prayerLvl)
            assertEquals(1, deps.npcs.count { it.id in THRALL_IDS })
        }

    @Test
    fun GameTestState.`failed casts consume no prayer or cooldown`() = runThrallTest { deps ->
        player.inv[1] = null
        cast()
        assertEquals(99, player.prayerLvl)
        player.inv[1] = InvObj(book)
        player.setCurrentLevel(stats.prayer, 5)
        cast()
        player.setCurrentLevel(stats.prayer, 99)
        player.setCurrentLevel(stats.magic, 75)
        cast()
        player.setCurrentLevel(stats.magic, 112)
        VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Standard.varValue)
        cast()
        VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Arceuus.varValue)
        player.inv[0] = null
        cast()
        assertEquals(0, deps.npcs.count { it.id in THRALL_IDS })
        assertEquals(0, player.vars[thrall_varbits.cooldown])
        assertEquals(99, player.prayerLvl)
    }

    @Test
    fun GameTestState.`book can be equipped and is never consumed`() = runThrallTest { deps ->
        player.inv[1] = null
        player.worn[Wearpos.LeftHand.slot] = InvObj(book)
        cast()
        assertEquals(10880, thrall(deps).id)
        assertEquals(book.id, player.worn[Wearpos.LeftHand.slot]?.id)
    }

    @Test
    fun GameTestState.`loose rune costs are consumed once and failed recast costs nothing`() =
        runThrallTest { deps ->
            player.inv[0] = null
            player.inv[1] = null
            val spell = checkNotNull(deps.spells.getObjSpell(objs.spell_resurrect_greater_ghost))
            for ((slot, req) in spell.objReqs.withIndex()) player.inv[slot] =
                InvObj(req.obj, req.count * 3)
            player.inv[10] = InvObj(book)
            cast()
            cast()
            assertEquals(10880, thrall(deps).id)
            for (req in spell.objReqs) assertEquals(req.count * 2, player.inv.count(req.obj))
            assertEquals(1, player.inv.count(book))
            assertEquals(93, player.prayerLvl)
        }

    @Test
    fun GameTestState.`idle thrall wanders near its location instead of following its owner`() =
        runThrallTest { deps ->
            cast(thrall_components.zombie)
            val summoned = thrall(deps)
            val centre = summoned.coords
            allocZoneCollision(centre)
            allocZoneCollision(centre.translate(-2, -2))
            allocZoneCollision(centre.translate(-2, 0))
            allocZoneCollision(centre.translate(0, -2))
            player.teleport(centre.translateX(10))
            random.next = 4
            random.then = -2
            random.then = 0
            advance(ticks = 4)
            assertEquals(centre.translateX(-1), summoned.coords)
            repeat(20) {
                advance()
                assertTrue(summoned.coords.chebyshevDistance(centre) <= 2)
            }
            assertTrue(summoned.distanceTo(player) >= 8)
        }

    @Test
    fun GameTestState.`thrall wanders where combat ended and resumes attacking a new target`() =
        runThrallTest { deps ->
            val first = spawnNpc(player.coords.translateX(8), targetType)
            allocZoneCollision(player.coords)
            allocZoneCollision(first.coords)
            eventBus.publish(PlayerAttackTarget(player, first))
            cast(thrall_components.zombie)
            val summoned = thrall(deps)
            advanceUntil({ summoned.distanceTo(first) <= 1 }, timeoutTicks = 12)
            val centre = summoned.coords
            npcRepo.del(first, Int.MAX_VALUE)
            player.teleport(centre.translateZ(10))
            advance()
            random.next = 4
            random.then = -2
            random.then = 0
            advance(ticks = 5)
            assertEquals(centre.translateX(-1), summoned.coords)
            assertFalse(summoned.isFacingNpc)
            val second = spawnNpc(summoned.coords.translateZ(1), targetType)
            eventBus.publish(PlayerAttackTarget(player, second))
            random.next = 3
            advanceUntil({ second.hitpoints < 1000 }, timeoutTicks = 8)
            assertEquals(997, second.hitpoints)
        }

    @Test
    fun GameTestState.`thrall waits for owner attack and hits every four ticks including zero`() =
        runThrallTest { deps ->
            val target = spawnNpc(player.coords.translateZ(1), targetType)
            val hits = mutableListOf<Pair<Int, Int>>()
            eventBus.subscribeKeyed(NpcHitEvents.Impact::class.java, target.id.toLong()) {
                hits += mapClock.cycle to hit.damage
            }
            cast(thrall_components.zombie)
            advance(ticks = 8)
            assertTrue(hits.isEmpty())
            target.telejump(thrall(deps).coords.translateZ(1))
            eventBus.publish(PlayerAttackTarget(player, target))
            random.next = 0
            advance(ticks = 2)
            assertEquals(0, hits.single().second)
            random.next = 3
            advance(ticks = 4)
            assertEquals(3, hits.last().second)
            assertEquals(4, hits.last().first - hits.first().first)
            assertEquals(997, target.hitpoints)
            assertEquals(NpcMode.None, target.mode)
            assertEquals(player, target.findHero(players))
        }

    @Test
    fun GameTestState.`damage style matches each summon without accuracy or combat XP`() =
        runThrallTest { deps ->
            val target = spawnNpc(player.coords.translateZ(1), targetType)
            val hits = mutableListOf<HitType>()
            eventBus.subscribeKeyed(NpcHitEvents.Impact::class.java, target.id.toLong()) {
                hits += hit.type
            }
            eventBus.publish(PlayerAttackTarget(player, target))
            for ((button, style) in
                listOf(
                    thrall_components.ghost to HitType.Magic,
                    thrall_components.skeleton to HitType.Ranged,
                    thrall_components.zombie to HitType.Melee,
                )) {
                eventBus.publish(PlayerAttackTarget(player, target))
                cast(button)
                val before = target.hitpoints
                val magicXp = player.statMap.getFineXP(stats.magic)
                val hpXp = player.statMap.getFineXP(stats.hitpoints)
                random.next = 3
                advance(ticks = 8)
                assertEquals(style, hits.last())
                assertEquals(magicXp, player.statMap.getFineXP(stats.magic))
                assertEquals(hpXp, player.statMap.getFineXP(stats.hitpoints))
                assertTrue(target.hitpoints < before)
                assertTrue(deps.npcs.any { it.id in THRALL_IDS })
                advance(ticks = 16)
            }
        }

    @Test
    fun GameTestState.`retargets to owners new NPC and ignores teammates targets`() =
        runThrallTest { deps ->
            val first = spawnNpc(player.coords.translateZ(1), targetType)
            val second = spawnNpc(player.coords.translateX(1), targetType)
            val other = registerPlayer(player.coords)
            cast()
            eventBus.publish(PlayerAttackTarget(player, first))
            random.next = 3
            advance(ticks = 8)
            assertTrue(first.hitpoints < 1000)
            eventBus.publish(PlayerAttackTarget(other, first))
            eventBus.publish(PlayerAttackTarget(player, second))
            advance(ticks = 8) // Drain already launched hits on the old target.
            val oldHp = first.hitpoints
            val newHp = second.hitpoints
            random.next = 3
            advance(ticks = 8)
            assertEquals(oldHp, first.hitpoints)
            assertTrue(second.hitpoints < newHp)
            assertEquals(1, deps.npcs.count { it.id in THRALL_IDS })
        }

    @Test
    fun GameTestState.`style restriction suppresses thrall damage`() = runThrallTest { deps ->
        val target = spawnNpc(player.coords.translateZ(1), targetType)
        eventBus.subscribeKeyed(PlayerAttackCheck::class.java, target.id.toLong()) {
            assertTrue(isThrall)
            allowed = false
        }
        eventBus.publish(PlayerAttackTarget(player, target))
        cast()
        advance(ticks = 16)
        assertEquals(1000, target.hitpoints)
    }

    @Test
    fun GameTestState.`melee thrall walks to target before attacking`() = runThrallTest { deps ->
        val target = spawnNpc(player.coords.translateZ(5), targetType)
        allocZoneCollision(player.coords)
        allocZoneCollision(target.coords)
        eventBus.publish(PlayerAttackTarget(player, target))
        cast(thrall_components.zombie)
        val summoned = thrall(deps)
        val start = summoned.coords
        val impacts = mutableListOf<Int>()
        eventBus.subscribeKeyed(NpcHitEvents.Impact::class.java, target.id.toLong()) {
            impacts += hit.damage
            assertTrue(summoned.distanceTo(target) <= 1)
        }
        advance(ticks = 12)
        assertTrue(summoned.coords != start)
        assertTrue(impacts.isNotEmpty())
    }

    @Test
    fun GameTestState.`ranged thrall attacks at six tiles and walks when beyond range`() =
        runThrallTest { deps ->
            val target = spawnNpc(player.coords.translateZ(6), targetType)
            allocZoneCollision(player.coords)
            allocZoneCollision(target.coords)
            eventBus.publish(PlayerAttackTarget(player, target))
            cast(thrall_components.skeleton)
            val summoned = thrall(deps)
            val start = summoned.coords
            random.next = 3
            advance(ticks = 8)
            assertEquals(start, summoned.coords)
            assertTrue(target.hitpoints < 1000)
            val distant = spawnNpc(player.coords.translateX(8), targetType)
            allocZoneCollision(distant.coords)
            eventBus.publish(PlayerAttackTarget(player, distant))
            advance(ticks = 12)
            assertTrue(summoned.coords != start)
            assertTrue(summoned.distanceTo(distant) <= 6)
        }

    @Test
    fun GameTestState.`summoned thralls cannot take damage`() = runThrallTest { deps ->
        cast()
        val summoned = thrall(deps)
        val hp = summoned.hitpoints
        summoned.queueHit(player, 1, HitType.Melee, 100)
        advance(ticks = 1)
        assertEquals(hp, summoned.hitpoints)
    }

    @Test
    fun GameTestState.`dead or deleted targets do not redirect to another NPC`() =
        runThrallTest { deps ->
            val target = spawnNpc(player.coords.translateZ(1), targetType)
            eventBus.publish(PlayerAttackTarget(player, target))
            cast()
            npcRepo.del(target, Int.MAX_VALUE)
            val other = spawnNpc(player.coords.translateZ(1), targetType)
            advance(ticks = 12)
            assertEquals(1000, other.hitpoints)
        }

    @Test
    fun GameTestState.`logout removes summon and clears client state`() = runThrallTest { deps ->
        cast()
        val summoned = thrall(deps)
        eventBus.publish(SessionStateEvent.Logout(player))
        assertFalse(summoned.isSlotAssigned)
        assertEquals(0, player.vars[thrall_varbits.active])
        assertEquals(0, player.vars[thrall_varbits.cooldown])
    }

    @Test
    fun GameTestState.`owner death and teleport away remove summon`() = runThrallTest { deps ->
        cast()
        val summoned = thrall(deps)
        player.teleport(player.coords.translateX(40))
        advance(ticks = 1)
        assertFalse(summoned.isSlotAssigned)
        advance(ticks = 16)
        cast()
        val second = thrall(deps)
        player.setCurrentLevel(stats.hitpoints, 0)
        advance(ticks = 1)
        assertFalse(second.isSlotAssigned)
    }

    private val GameTestScope.book: ObjType
        get() = checkNotNull(objTypes[25818])

    private fun GameTestScope.cast(button: ComponentType = thrall_components.ghost) {
        player.ifButton(button)
        advance(ticks = 1)
    }

    private fun GameTestScope.thrall(deps: Dependencies): Npc =
        deps.npcs.single { it.id in THRALL_IDS }

    class Dependencies @Inject constructor(val spells: MagicSpellRegistry, val npcs: NpcList)

    private fun GameTestState.runThrallTest(test: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            Thralls::class,
            NpcHitScript::class,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
        ) { deps ->
            // Use an empty map square so movement fixtures do not share packed-map collision.
            player.teleport(CoordGrid(8000, 8000, 0))
            player.setCurrentLevel(stats.magic, 112)
            player.setCurrentLevel(stats.prayer, 99)
            player.inv[1] = InvObj(book)
            player.inv[0] = InvObj(objs.rune_pouch)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Arceuus.varValue)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            test(deps)
        }

    private object TestModule : AbstractModule() {
        override fun configure() {
            install(MagicSpellModule())
            bind(ComboRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(CompactRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(FakeRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(StaffSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(RuneSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(UnlimitedRuneRepository::class.java).`in`(Scopes.SINGLETON)
        }
    }

    private companion object {
        val THRALL_IDS = setOf(10880, 10883, 10886)
        val targetType =
            npcTypeFactory.create {
                name = "Thrall target"
                size = 1
                hitpoints = 1000
                defence = 10000
                magic = 10000
                op[1] = "Attack"
                defaultMode = NpcMode.None
            }
    }
}

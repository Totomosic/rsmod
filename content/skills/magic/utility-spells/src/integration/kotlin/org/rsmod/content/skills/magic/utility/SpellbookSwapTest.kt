package org.rsmod.content.skills.magic.utility

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onAiOpPlayer2
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpNpc1
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
import org.rsmod.api.testing.factory.locTypeFactory
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.content.skills.magic.teleports.HomeTeleport
import org.rsmod.content.skills.magic.teleports.LUMBRIDGE_HOME
import org.rsmod.content.skills.magic.teleports.home_teleport_components
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.util.EntityFaceAngle
import org.rsmod.game.entity.util.EntityFaceTarget
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class SpellbookSwapTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
    fun `utility casts preserve pending movement and noncombat actions`(
        variant: Int,
        state: GameTestState,
    ) =
        state.runSwapTest(Thralls::class, NpcAction::class) {
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Arceuus.varValue)
            if (variant >= 4) player.swapActive = 1
            player.setCurrentLevel(stats.prayer, 99)
            player.inv[1] = InvObj(checkNotNull(objTypes[25818])) // Book of the dead.
            val npc = spawnNpc(player.coords.translateX(6), actionTarget)
            allocZoneCollision(player.coords)
            advance(ticks = 1)
            val start = player.coords
            player.opNpc1(npc)
            val buttons =
                listOf(
                    death_charge_components.cast,
                    thrall_components.ghost,
                    thrall_components.skeleton,
                    thrall_components.zombie,
                )
            player.ifButton(buttons[variant % 4])
            advance(ticks = 1)
            val active =
                if (variant % 4 == 0) death_charge_varbits.active else thrall_varbits.active
            assertEquals(1, player.vars[active])
            assertEquals(if (variant >= 4) 2 else 3, player.vars[varbits.spellbook])
            assertNotNull(player.interaction)
            assertNotEquals(start, player.coords)
            advanceUntil({ player.interaction == null }, timeoutTicks = 10)
            assertMessageSent("Action completed.")
        }

    @Test
    fun GameTestState.`movement clicked before SBS in the same tick resumes after the stall`() =
        runSwapTest {
            val start = player.coords
            val destination = start.translateX(5)
            allocZoneCollision(start)
            player.moveGameClick(destination)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            advance(ticks = 1)
            assertEquals(start, player.coords)
            advance(ticks = 2)
            assertEquals(start, player.coords)
            advance(ticks = 1)
            assertEquals(Spellbook.Standard.varValue, player.vars[varbits.spellbook])
            assertNotEquals(start, player.coords)
            advance(ticks = 10)
            assertEquals(destination, player.coords)
        }

    @Test
    fun GameTestState.`movement clicked after SBS in the same tick does not resume`() =
        runSwapTest {
            val start = player.coords
            allocZoneCollision(start)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            player.moveGameClick(start.translateX(5))
            advance(ticks = 10)
            assertEquals(Spellbook.Standard.varValue, player.vars[varbits.spellbook])
            assertEquals(start, player.coords)
        }

    @Test
    fun GameTestState.`movement from an earlier tick pauses during SBS and resumes afterwards`() =
        runSwapTest {
            val start = player.coords
            val destination = start.translateX(10)
            allocZoneCollision(start)
            player.moveGameClick(destination)
            advance(ticks = 1)
            val castPosition = player.coords
            assertNotEquals(start, castPosition)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            advance(ticks = 3)
            assertEquals(castPosition, player.coords)
            advance(ticks = 1)
            assertEquals(Spellbook.Standard.varValue, player.vars[varbits.spellbook])
            assertNotEquals(castPosition, player.coords)
            advance(ticks = 10)
            assertEquals(destination, player.coords)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `new red x stays still and faces its NPC throughout SBS even after previous movement`(
        walking: Boolean,
        state: GameTestState,
    ) =
        state.runSwapTest(NpcAction::class) {
            player.teleport(CoordGrid(0, 1, 1, 3, 3))
            val npc = spawnNpc(player.coords.translateX(6), actionTarget)
            if (walking) player.moveGameClick(player.coords.translateZ(6))
            advance(ticks = 1)
            val start = player.coords
            player.opNpc1(npc)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            repeat(3) {
                advance(ticks = 1)
                assertEquals(start, player.coords)
                assertNotNull(player.interaction)
                assertEquals(EntityFaceTarget(npc), player.faceEntity)
                assertMessageNotSent("Action completed.")
            }
            advanceUntil({ player.interaction == null }, timeoutTicks = 10)
            assertMessageSent("Action completed.")
        }

    @Test
    fun GameTestState.`a red x remains active against an overlapping NPC throughout SBS`() =
        runSwapTest(BarrierAction::class, NpcAttacker::class) {
            player.teleport(CoordGrid(0, 1, 1, 3, 3))
            val barrier = placeMapLoc(player.coords.translateX(6), barrierTarget)
            advance(ticks = 1)
            val start = player.coords
            val npc = spawnNpc(start, attackerTarget)
            npc.opPlayer2(player)
            player.opLoc1(barrier)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            repeat(3) {
                advance(ticks = 1)
                assertEquals(start, player.coords)
                assertEquals(start, npc.coords)
                assertMessageNotSent("Enemy attacked.")
                assertNotNull(player.interaction)
            }
        }

    @Test
    fun GameTestState.`an existing red x moves to its NPC during SBS but waits to interact`() =
        runSwapTest(NpcAction::class) {
            player.teleport(CoordGrid(0, 1, 1, 3, 3))
            val npc = spawnNpc(player.coords.translateX(3), actionTarget)
            advance(ticks = 1)
            player.opNpc1(npc)
            advance(ticks = 1)
            val castPosition = player.coords
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            advance(ticks = 1)
            assertNotEquals(castPosition, player.coords)
            val arrival = player.coords
            repeat(2) {
                assertNotNull(player.interaction)
                assertEquals(EntityFaceTarget(npc), player.faceEntity)
                assertMessageNotSent("Action completed.")
                advance(ticks = 1)
                assertEquals(arrival, player.coords)
            }
            assertMessageNotSent("Action completed.")
            advance(ticks = 1)
            assertMessageSent("Action completed.")
            assertNull(player.interaction)
        }

    @ParameterizedTest
    @ValueSource(ints = [1, 6])
    fun `new red x faces its world object every tick and cannot pass during SBS`(
        distance: Int,
        state: GameTestState,
    ) =
        state.runSwapTest(BarrierAction::class) {
            player.teleport(CoordGrid(0, 1, 1, 3, 3))
            val barrier = placeMapLoc(player.coords.translateX(distance), barrierTarget)
            val faces = mutableListOf<EntityFaceAngle>()
            eventBus.subscribeUnbound(GameLifecycle.UpdateInfo::class.java) {
                faces += player.pendingFaceAngle
            }
            advance(ticks = 1)
            faces.clear()
            val start = player.coords
            val expectedAngle =
                EntityFaceAngle.fromOrNull(
                    player.calculateAngle(
                        barrier.coords,
                        barrier.adjustedWidth,
                        barrier.adjustedLength,
                    )
                )
            player.opLoc1(barrier)
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            repeat(3) {
                advance(ticks = 1)
                assertEquals(start, player.coords)
                assertNotNull(player.interaction)
                assertMessageNotSent("Passed barrier.")
            }
            assertEquals(List(3) { expectedAngle }, faces)
            advanceUntil({ player.interaction == null }, timeoutTicks = 10)
            assertMessageSent("Passed barrier.")
            assertEquals(barrier.coords.translateX(1), player.coords)
        }

    @Test
    fun GameTestState
        .`an existing red x reaches a barrier during SBS but passes only afterwards`() =
        runSwapTest(BarrierAction::class) {
            player.teleport(CoordGrid(0, 1, 1, 3, 3))
            val barrier = placeMapLoc(player.coords.translateX(3), barrierTarget)
            advance(ticks = 1)
            player.opLoc1(barrier)
            advance(ticks = 1)
            val castPosition = player.coords
            player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
            advance(ticks = 1)
            assertNotEquals(castPosition, player.coords)
            val arrival = player.coords
            repeat(2) {
                assertNotNull(player.interaction)
                assertMessageNotSent("Passed barrier.")
                advance(ticks = 1)
                assertEquals(arrival, player.coords)
            }
            assertMessageNotSent("Passed barrier.")
            advance(ticks = 1)
            assertMessageSent("Passed barrier.")
            assertEquals(barrier.coords.translateX(1), player.coords)
            assertNull(player.interaction)
        }

    @Test
    fun GameTestState.`direct spellbook options skip the dialogue and swap after three ticks`() =
        runSwapTest {
            val choices =
                listOf(
                    Triple(IfButtonOp.Op2, Spellbook.Standard, "Standard"),
                    Triple(IfButtonOp.Op3, Spellbook.Ancients, "Ancient"),
                    Triple(IfButtonOp.Op4, Spellbook.Arceuus, "Arceuus"),
                )
            for ((op, book, label) in choices) {
                val initialXp = player.statMap.getXP(stats.magic)
                assertEquals(
                    label,
                    cacheTypes.components[spellbook_swap_components.cast].op[op.slot - 1],
                )
                player.ifButton(spellbook_swap_components.cast, op = op)
                advance(ticks = 1)
                assertFalse(player.ui.containsModal(interfaces.chatmenu))
                assertEquals(initialXp, player.statMap.getXP(stats.magic))
                advance(ticks = 2)
                assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
                assertEquals(initialXp, player.statMap.getXP(stats.magic))
                advance(ticks = 1)
                assertEquals(book.varValue, player.vars[varbits.spellbook])
                assertEquals(initialXp + 130, player.statMap.getXP(stats.magic))
                advance(ticks = 200)
                assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            }
        }

    @Test
    fun GameTestState.`direct casts still check level runes and current spellbook`() = runSwapTest {
        player.setCurrentLevel(stats.magic, 1)
        player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op2)
        advance(ticks = 4)
        assertEquals(0, player.swapActive)
        assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
        player.setCurrentLevel(stats.magic, 99)
        player.inv[0] = null
        player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op3)
        advance(ticks = 4)
        assertEquals(0, player.swapActive)
        assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
        player.inv[0] = InvObj(objs.rune_pouch)
        VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Standard.varValue)
        player.ifButton(spellbook_swap_components.cast, op = IfButtonOp.Op4)
        advance(ticks = 4)
        assertEquals(0, player.swapActive)
        assertEquals(Spellbook.Standard.varValue, player.vars[varbits.spellbook])
        assertFalse(player.ui.containsModal(interfaces.chatmenu))
    }

    @Test
    fun GameTestState.`all three books change exactly three ticks after the cast animation`() =
        runSwapTest {
            val animations = mutableListOf<Pair<Int, Int>>()
            eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (player.pendingSequence.id == 6299) {
                    animations += mapClock.cycle to player.pendingSequence.id
                }
            }
            for ((choice, book) in
                listOf(Spellbook.Standard, Spellbook.Ancients, Spellbook.Arceuus).withIndex()) {
                val initialXp = player.statMap.getXP(stats.magic)
                choose(choice + 1)
                assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
                assertEquals(6299, animations.last().second)
                assertEquals(initialXp, player.statMap.getXP(stats.magic))
                advance(ticks = 2)
                assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
                assertEquals(initialXp, player.statMap.getXP(stats.magic))
                advance(ticks = 1)
                assertEquals(book.varValue, player.vars[varbits.spellbook])
                assertEquals(initialXp + 130, player.statMap.getXP(stats.magic))
                advance(ticks = 200)
                assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            }
        }

    @Test
    fun GameTestState.`utility cast returns immediately and its effect still applies`() =
        runSwapTest {
            swap(3)
            player.ifButton(death_charge_components.cast)
            advance(ticks = 1)
            assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            assertEquals(0, player.swapActive)
            assertEquals(1, player.vars[death_charge_varbits.active])
            assertEquals(1, player.vars[death_charge_varbits.cooldown])
        }

    @Test
    fun GameTestState.`combat cast consumes the swap before damage and rejects a second cast`() =
        runSwapTest { deps ->
            swap(2)
            val spell = checkNotNull(deps.spells.getObjSpell(objs.spell_ice_barrage))
            // Combat hits and splashes both commit through this shared rune manager path.
            val result = deps.runes.attemptCast(player, spell)
            assertTrue(result is MagicRuneManager.CastResult.Success)
            assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            assertEquals(0, player.swapActive)
            assertEquals(
                MagicRuneManager.CastResult.Failure.IncorrectSpellbook,
                deps.runes.attemptCast(player, spell),
            )
        }

    @Test
    fun GameTestState.`failed casts preserve the swap until a valid cast`() = runSwapTest { deps ->
        swap(2)
        val spell = checkNotNull(deps.spells.getObjSpell(objs.spell_ice_barrage))
        player.setCurrentLevel(stats.magic, 1)
        assertEquals(
            MagicRuneManager.CastResult.Failure.MissingLevelRequirement,
            deps.runes.attemptCast(player, spell),
        )
        player.setCurrentLevel(stats.magic, 99)
        player.inv[0] = null
        assertEquals(
            MagicRuneManager.CastResult.Failure.MissingObjRequirements,
            deps.runes.attemptCast(player, spell),
        )
        assertEquals(Spellbook.Ancients.varValue, player.vars[varbits.spellbook])
        assertEquals(1, player.swapActive)
        player.inv[0] = InvObj(objs.rune_pouch)
        assertTrue(deps.runes.attemptCast(player, spell) is MagicRuneManager.CastResult.Success)
        assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
    }

    @Test
    fun GameTestState
        .`timeout is 200 ticks after the book changes and clears autocast while busy`() =
        runSwapTest {
            swap(2)
            VarPlayerIntMapSetter.set(player, varbits.autocast_set, 1)
            VarPlayerIntMapSetter.set(player, varbits.autocast_spell, 1)
            VarPlayerIntMapSetter.set(player, varbits.autocast_defmode, 1)
            player.delay(210)
            advance(ticks = 199)
            assertEquals(Spellbook.Ancients.varValue, player.vars[varbits.spellbook])
            advance(ticks = 1)
            assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            assertEquals(0, player.vars[varbits.autocast_set])
            assertEquals(0, player.vars[varbits.autocast_spell])
            assertEquals(0, player.vars[varbits.autocast_defmode])
        }

    @Test
    fun GameTestState.`home teleport returns immediately and completes its animation`() =
        runSwapTest {
            swap(1)
            player.ifButton(home_teleport_components.standard)
            advance(ticks = 1)
            assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            advance(ticks = 24)
            assertEquals(LUMBRIDGE_HOME, player.coords)
        }

    @Test
    fun GameTestState.`cancelled choice and invalid swap requirements do not change books`() =
        runSwapTest {
            player.ifButton(spellbook_swap_components.cast)
            advance(ticks = 1)
            player.ifClose()
            advance(ticks = 4)
            assertEquals(0, player.swapActive)
            player.inv[0] = null
            player.ifButton(spellbook_swap_components.cast)
            advance(ticks = 4)
            assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            assertEquals(0, player.swapActive)
            assertFalse(player.ui.containsModal(interfaces.chatmenu))
        }

    @Test
    fun GameTestState.`swap consumes cache rune requirements once after choosing`() =
        runSwapTest { deps ->
            player.inv[0] = null
            val spell = checkNotNull(deps.spells.getObjSpell(objs.spell_spellbook_swap))
            for ((slot, req) in spell.objReqs.withIndex()) {
                player.inv[slot] = InvObj(req.obj, req.count * 2)
            }
            swap(1)
            for (req in spell.objReqs) assertEquals(req.count, player.inv.count(req.obj))
        }

    @Test
    fun GameTestState.`logout and recovery login restore lunar without affecting ordinary books`() =
        runSwapTest {
            swap(2)
            eventBus.publish(SessionStateEvent.Logout(player))
            assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            player.swapActive = 1
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Arceuus.varValue)
            eventBus.publish(SessionStateEvent.Login(player))
            assertEquals(Spellbook.Lunars.varValue, player.vars[varbits.spellbook])
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Standard.varValue)
            eventBus.publish(SessionStateEvent.Login(player))
            assertEquals(Spellbook.Standard.varValue, player.vars[varbits.spellbook])
        }

    private fun GameTestScope.choose(choice: Int) {
        player.ifButton(spellbook_swap_components.cast)
        advance(ticks = 1)
        player.resumePauseButton(components.chatmenu_pbutton, choice)
        advance(ticks = 1)
    }

    private fun GameTestScope.swap(choice: Int) {
        choose(choice)
        advance(ticks = 3)
    }

    private fun GameTestState.runSwapTest(
        vararg extraScripts: KClass<out PluginScript>,
        test: GameTestScope.(Dependencies) -> Unit,
    ) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            SpellbookSwap::class,
            DeathCharge::class,
            HomeTeleport::class,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
            *extraScripts,
        ) { deps ->
            player.teleport(CoordGrid(3200, 3200, 0))
            player.setCurrentLevel(stats.magic, 99)
            player.inv[0] = InvObj(objs.rune_pouch)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Lunars.varValue)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            test(deps)
        }

    class Dependencies
    @Inject
    constructor(val spells: MagicSpellRegistry, val runes: MagicRuneManager)

    class NpcAction : PluginScript() {
        override fun ScriptContext.startup() {
            onOpNpc1(actionTarget) { mes("Action completed.") }
        }
    }

    class NpcAttacker : PluginScript() {
        override fun ScriptContext.startup() {
            onAiOpPlayer2(attackerTarget) { it.target.mes("Enemy attacked.") }
        }
    }

    class BarrierAction : PluginScript() {
        override fun ScriptContext.startup() {
            onOpLoc1(barrierTarget) {
                mes("Passed barrier.")
                teleport(it.loc.coords.translateX(1))
            }
        }
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
        private var Player.swapActive by intVarp(spellbook_swap_varps.active)
        private val attackerTarget =
            npcTypeFactory.create(id = 65533) {
                name = "SBS enemy"
                size = 1
                op[1] = "Attack"
            }
        private val barrierTarget =
            locTypeFactory.create {
                name = "SBS test barrier"
                op[0] = "Pass"
                width = 1
                length = 1
            }
        private val actionTarget =
            npcTypeFactory.create(id = 65534) {
                name = "SBS action target"
                size = 1
                op[0] = "Use"
            }
    }
}

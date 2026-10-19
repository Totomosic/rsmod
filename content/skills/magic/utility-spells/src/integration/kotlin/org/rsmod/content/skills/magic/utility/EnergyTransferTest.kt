package org.rsmod.content.skills.magic.utility

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.players.OpPlayerT
import net.rsprot.protocol.game.outgoing.misc.player.UpdateRunEnergy
import net.rsprot.protocol.game.outgoing.zone.payload.SoundArea
import net.rsprot.protocol.util.CombinedId
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.net.rsprot.handlers.OpPlayerTHandler
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.registry.zone.ZoneUpdateMap
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
import org.rsmod.api.testing.capture.CaptureClient
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.inv.InvObj
import org.rsmod.game.spot.EntitySpotanim
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class EnergyTransferTest {
    @Test
    fun GameTestState.`transfer spends HP special energy and runes and restores the recipient`() =
        runTransferTest { deps ->
            val target = recipient()
            val xp = player.statMap.getXP(stats.magic)
            val spell = checkNotNull(deps.spells.getObjSpell(objs.spell_energy_transfer))
            assertEquals(91, spell.levelReq)
            assertEquals(
                3,
                spell.objReqs.single { it.obj == energy_transfer_test_objs.astral_rune }.count,
            )
            assertEquals(2, spell.objReqs.single { it.obj == objs.law_rune }.count)
            assertEquals(1, spell.objReqs.single { it.obj == objs.nature_rune }.count)
            cast(deps, target)
            assertEquals(89, player.hitpoints)
            assertEquals(0, player.vars[varps.sa_energy])
            assertEquals(100, target.vars[varps.sa_energy])
            assertTrue(target.runEnergy < constants.run_max_energy)
            assertEquals(99, target.hitpoints)
            assertEquals(xp + 100, player.statMap.getXP(stats.magic))
            assertEquals(3, player.inv.count(energy_transfer_test_objs.astral_rune))
            assertEquals(2, player.inv.count(objs.law_rune))
            assertEquals(1, player.inv.count(objs.nature_rune))
            assertFalse(UpdateRunEnergy(constants.run_max_energy) in target.client as CaptureClient)
            advance(ticks = 2)
            assertEquals(100, target.vars[varps.sa_energy])
            assertTrue(target.runEnergy < constants.run_max_energy)
            advance()
            assertEquals(constants.sa_max_energy, target.vars[varps.sa_energy])
            assertEquals(constants.run_max_energy, target.runEnergy)
            assertTrue((target.client as CaptureClient).hasAny<UpdateRunEnergy>())
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 2, 10])
    fun `casting ignores existing attack cooldowns and does not stall the caster`(
        cooldown: Int,
        state: GameTestState,
    ) =
        state.runTransferTest { deps ->
            val target = recipient()
            player.actionDelay = mapClock.cycle + cooldown
            val nextAttack = player.actionDelay
            val nextAction = player.delay
            cast(deps, target)
            assertEquals(89, player.hitpoints)
            assertEquals(nextAttack, player.actionDelay)
            assertEquals(nextAction, player.delay)
            assertFalse(player.isDelayed)
            assertNull(player.interaction)
            advance(ticks = 3)
            assertEquals(constants.sa_max_energy, target.vars[varps.sa_energy])
        }

    @Test
    fun GameTestState
        .`cast sound is immediate and the receiving effect arrives after three ticks`() =
        runTransferTest { deps ->
            val target = recipient()
            val sounds = mutableListOf<SoundArea>()
            val effects = mutableListOf<Pair<Int, Int>>()
            eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                sounds +=
                    deps.zones[ZoneKey.from(player.coords)]?.filterIsInstance<SoundArea>().orEmpty()
                effects += target.pendingSpotanims.map { mapClock.cycle to EntitySpotanim(it).id }
            }
            val castTick = mapClock.cycle + 1
            cast(deps, target)
            val source = ZoneGrid.from(player.coords)
            assertEquals(listOf(SoundArea(2885, 0, 1, 10, 1, source.x, source.z)), sounds)
            assertTrue(effects.isEmpty())
            advance(ticks = 2)
            assertTrue(effects.isEmpty())
            advance()
            assertEquals(listOf(castTick + 3 to 734), effects)
            assertEquals(1, sounds.size)
        }

    @Test
    fun GameTestState.`delivery survives a new caster action and a delayed recipient`() =
        runTransferTest { deps ->
            val target = recipient()
            cast(deps, target)
            val npc = spawnNpc(player.coords.translateZ(1), actionTarget)
            player.opNpc1(npc)
            target.withProtectedAccess { delay(10) }
            advance()
            assertTrue(player.ui.containsModal(interfaces.chatmenu))
            advance(ticks = 2)
            assertEquals(constants.sa_max_energy, target.vars[varps.sa_energy])
            assertTrue(target.isDelayed)
            assertTrue(player.ui.containsModal(interfaces.chatmenu))
        }

    @Test
    fun GameTestState.`separate transfers keep their own three tick arrival`() =
        runTransferTest { deps ->
            val target = recipient()
            cast(deps, target)
            advance()
            player.setVarp(varps.sa_energy, constants.sa_max_energy)
            cast(deps, target)
            assertEquals(100, target.vars[varps.sa_energy])
            advance()
            assertEquals(constants.sa_max_energy, target.vars[varps.sa_energy])
            target.setVarp(varps.sa_energy, 100)
            advance()
            assertEquals(100, target.vars[varps.sa_energy])
            advance()
            assertEquals(constants.sa_max_energy, target.vars[varps.sa_energy])
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `delivery skips recipients who die or log out during the wait`(
        logout: Boolean,
        state: GameTestState,
    ) =
        state.runTransferTest { deps ->
            val target = recipient()
            cast(deps, target)
            val replacement =
                if (logout) {
                    unregisterPlayer(target)
                    recipient()
                } else {
                    target.setCurrentLevel(stats.hitpoints, 0)
                    null
                }
            advance(ticks = 3)
            assertEquals(100, target.vars[varps.sa_energy])
            assertTrue(target.runEnergy < constants.run_max_energy)
            if (replacement != null) assertEquals(100, replacement.vars[varps.sa_energy])
            assertEquals(89, player.hitpoints)
        }

    @Test
    fun GameTestState.`casting interrupts an existing NPC action and its movement`() =
        runTransferTest { deps ->
            val npc = spawnNpc(player.coords.translateX(8), actionTarget)
            player.opNpc1(npc)
            advance()
            assertNotNull(player.interaction)
            val position = player.coords
            val target = recipient()
            cast(deps, target)
            assertEquals(89, player.hitpoints)
            assertNull(player.interaction)
            advance(ticks = 10)
            assertEquals(position, player.coords)
            assertNull(player.interaction)
        }

    @Test
    fun GameTestState.`casting interrupts a suspended dialogue`() = runTransferTest { deps ->
        val npc = spawnNpc(player.coords.translateZ(1), actionTarget)
        player.opNpc1(npc)
        advance()
        assertTrue(player.ui.containsModal(interfaces.chatmenu))
        cast(deps, recipient())
        assertEquals(89, player.hitpoints)
        assertFalse(player.ui.containsModal(interfaces.chatmenu))
        assertNull(player.interaction)
    }

    @Test
    fun GameTestState.`eleven hitpoints can pay the cost without dying`() =
        runTransferTest { deps ->
            player.setCurrentLevel(stats.hitpoints, 11)
            cast(deps, recipient())
            assertEquals(1, player.hitpoints)
            assertEquals(0, player.vars[varps.sa_energy])
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    fun `failed requirements spend no resources and grant no energy`(
        failure: Int,
        state: GameTestState,
    ) =
        state.runTransferTest { deps ->
            val target = recipient()
            val sounds = mutableListOf<SoundArea>()
            eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                sounds +=
                    deps.zones[ZoneKey.from(player.coords)]?.filterIsInstance<SoundArea>().orEmpty()
            }
            when (failure) {
                0 -> player.setCurrentLevel(stats.hitpoints, 10)
                1 -> player.setCurrentLevel(stats.hitpoints, 1)
                2 -> player.setVarp(varps.sa_energy, 0)
                3 -> player.setVarp(varps.sa_energy, constants.sa_max_energy - 1)
                4 -> target.setVarBit(varbits.option_acceptaid, 0)
                5 -> player.setCurrentLevel(stats.magic, 90)
                6 -> player.setVarBit(varbits.spellbook, Spellbook.Standard.varValue)
                7 -> player.inv[0] = null
                8 -> player.inv[1] = null
                9 -> player.inv[2] = null
                10 -> target.setCurrentLevel(stats.hitpoints, 0)
            }
            val hp = player.hitpoints
            val energy = player.vars[varps.sa_energy]
            val xp = player.statMap.getXP(stats.magic)
            val runes =
                listOf(energy_transfer_test_objs.astral_rune, objs.law_rune, objs.nature_rune)
            val counts = runes.map { player.inv.count(it) }
            cast(deps, target)
            assertEquals(hp, player.hitpoints)
            assertEquals(energy, player.vars[varps.sa_energy])
            assertEquals(xp, player.statMap.getXP(stats.magic))
            assertEquals(counts, runes.map { player.inv.count(it) })
            assertEquals(100, target.vars[varps.sa_energy])
            assertTrue(target.runEnergy < constants.run_max_energy)
            if (failure <= 1) assertMessageSent("You need more hitpoints to cast this spell.")
            advance(ticks = 3)
            assertEquals(100, target.vars[varps.sa_energy])
            assertTrue(target.runEnergy < constants.run_max_energy)
            assertTrue(sounds.isEmpty())
        }

    @Test
    fun GameTestState.`a second cast cannot reuse the caster special energy`() =
        runTransferTest { deps ->
            val target = recipient()
            cast(deps, target)
            target.setVarp(varps.sa_energy, 100)
            val xp = player.statMap.getXP(stats.magic)
            cast(deps, target)
            assertEquals(89, player.hitpoints)
            assertEquals(100, target.vars[varps.sa_energy])
            assertEquals(3, player.inv.count(energy_transfer_test_objs.astral_rune))
            assertEquals(xp, player.statMap.getXP(stats.magic))
            advance(ticks = 2)
            assertEquals(constants.sa_max_energy, target.vars[varps.sa_energy])
            target.setVarp(varps.sa_energy, 100)
            advance(ticks = 3)
            assertEquals(100, target.vars[varps.sa_energy])
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    @OptIn(InternalApi::class)
    fun `wilderness transfer requires both players in multi combat`(
        singlePlayer: Int,
        state: GameTestState,
    ) =
        state.runTransferTest { deps ->
            player.telejump(CoordGrid(3200, 3800, 0))
            val target = recipient()
            if (singlePlayer != 0) {
                deps.areas.registerAll(
                    player.coords,
                    listOf(areas.multiway.id.toShort()).iterator(),
                )
            }
            if (singlePlayer != 1) {
                deps.areas.registerAll(
                    target.coords,
                    listOf(areas.multiway.id.toShort()).iterator(),
                )
            }
            cast(deps, target)
            assertEquals(if (singlePlayer == 2) 89 else 99, player.hitpoints)
            assertEquals(100, target.vars[varps.sa_energy])
            advance(ticks = 3)
            assertEquals(
                if (singlePlayer == 2) constants.sa_max_energy else 100,
                target.vars[varps.sa_energy],
            )
        }

    private fun GameTestScope.recipient(): Player =
        registerPlayer(player.coords.translateX(1)).apply {
            setBaseLevel(org.rsmod.api.config.refs.stats.hitpoints, 99)
            setCurrentLevel(org.rsmod.api.config.refs.stats.hitpoints, 99)
            setVarBit(varbits.option_acceptaid, 1)
            setVarp(varps.sa_energy, 100)
            runEnergy = 100
        }

    private fun GameTestScope.cast(deps: Dependencies, target: Player) {
        val component = energy_transfer_components.cast
        client.queue(
            deps.targetSpell,
            OpPlayerT(
                target.slotId,
                false,
                CombinedId(component.interfaceId, component.component),
                -1,
                -1,
            ),
        )
        advance()
    }

    private fun GameTestState.runTransferTest(test: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            EnergyTransfer::class,
            PendingAction::class,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
        ) { deps ->
            player.teleport(CoordGrid(3200, 3200, 0))
            player.stats[stats.hitpoints] = 99
            player.setCurrentLevel(stats.magic, 91)
            player.setVarp(varps.sa_energy, constants.sa_max_energy)
            player.setVarBit(varbits.spellbook, Spellbook.Lunars.varValue)
            player.inv[0] = InvObj(energy_transfer_test_objs.astral_rune, 6)
            player.inv[1] = InvObj(objs.law_rune, 4)
            player.inv[2] = InvObj(objs.nature_rune, 2)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            test(deps)
        }

    class Dependencies
    @Inject
    constructor(
        val spells: MagicSpellRegistry,
        val targetSpell: OpPlayerTHandler,
        val areas: AreaIndex,
        val zones: ZoneUpdateMap,
    )

    class PendingAction : PluginScript() {
        override fun ScriptContext.startup() {
            onOpNpc1(actionTarget) { choice2("Continue", true, "Cancel", false) }
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
        val actionTarget =
            npcTypeFactory.create(id = 60_000) {
                name = "Pending action"
                size = 1
                hitpoints = 100
                op[0] = "Use"
                defaultMode = NpcMode.None
            }
    }
}

internal object energy_transfer_test_objs : ObjReferences() {
    val astral_rune = find("astralrune")
}

package org.rsmod.content.skills.magic.utility

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.zone.payload.SoundArea
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.interact.PlayerTInteractions
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.registry.zone.ZoneUpdateMap
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
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

class VengeanceTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `successful casts play the matching sound at the recipient once`(
        other: Boolean,
        state: GameTestState,
    ) =
        state.runVengeanceTest { deps ->
            val recipient = if (other) registerPlayer(player.coords.translateX(1)) else player
            val sounds = captureSounds(deps.zones)
            if (other) castOther(deps, recipient) else cast()
            assertEquals(
                listOf(SoundArea(if (other) 2908 else 2907, 0, 1, 10, 1, if (other) 1 else 0, 0)),
                sounds,
            )
            sounds.clear()
            if (other) castOther(deps, recipient) else cast()
            assertTrue(sounds.isEmpty()) // Cooldown rejection must not replay the sound.
        }

    @Test
    fun GameTestState.`self cast reflects 75 percent rounded down once and keeps cooldown`() =
        runVengeanceTest {
            cast()
            assertEquals(1, player.vars[vengeance_varbits.active])
            val npc = spawnNpc(player.coords.translateZ(1), target)
            player.queueHit(npc, 1, HitType.Melee, 19)
            advance(ticks = 2)
            assertEquals(80, player.hitpoints)
            assertEquals(86, npc.hitpoints)
            assertEquals(0, player.vars[vengeance_varbits.active])
            assertEquals(1, player.vars[vengeance_varbits.cooldown])
            player.queueHit(npc, 1, HitType.Magic, 20)
            advance(ticks = 2)
            assertEquals(86, npc.hitpoints)
        }

    @Test
    fun GameTestState
        .`other cast buffs the recipient and reflects their damage to their attacker`() =
        runVengeanceTest { deps ->
            val recipient = registerPlayer(player.coords.translateX(1))
            recipient.stats[stats.hitpoints] = 99
            castOther(deps, recipient)
            assertEquals(0, player.vars[vengeance_varbits.active])
            assertEquals(1, recipient.vars[vengeance_varbits.active])
            assertEquals(1, player.vars[vengeance_varbits.cooldown])
            assertEquals(0, recipient.vars[vengeance_varbits.cooldown])
            val npc = spawnNpc(recipient.coords.translateZ(1), target)
            recipient.queueHit(npc, 1, HitType.Ranged, 20)
            advance(ticks = 2)
            assertEquals(85, npc.hitpoints)
            assertEquals(recipient, npc.findHero(deps.players))
            assertEquals(99, player.hitpoints)
            assertEquals(0, recipient.vars[vengeance_varbits.active])
        }

    @Test
    fun GameTestState.`both spell variants share the caster cooldown in both directions`() =
        runVengeanceTest { deps ->
            val recipient = registerPlayer(player.coords.translateX(1))
            cast()
            val expiry = player.vengeanceExpiry
            castOther(deps, recipient)
            assertEquals(0, recipient.vars[vengeance_varbits.active])
            assertEquals(expiry, player.vengeanceExpiry)
            advance(ticks = expiry - player.currentMapClock - 1)
            castOther(deps, recipient) // Exactly 50 ticks after the self cast.
            assertEquals(1, recipient.vars[vengeance_varbits.active])
            assertEquals(expiry + 50, player.vengeanceExpiry)
            VarPlayerIntMapSetter.set(player, vengeance_varbits.active, 0)
            cast()
            assertEquals(0, player.vars[vengeance_varbits.active])
            assertMessageSent("You can only cast Vengeance spells once every 30 seconds.")
        }

    @Test
    fun GameTestState.`cooldown clears at 50 ticks while the unused status remains active`() =
        runVengeanceTest {
            cast()
            val expiry = player.vengeanceExpiry
            advance(ticks = expiry - player.currentMapClock - 1)
            assertEquals(1, player.vars[vengeance_varbits.cooldown])
            advance(ticks = 1)
            assertEquals(0, player.vars[vengeance_varbits.cooldown])
            assertEquals(1, player.vars[vengeance_varbits.active])
        }

    @Test
    fun GameTestState.`zero npc hits player hits and environmental damage preserve vengeance`() =
        runVengeanceTest {
            cast()
            val npc = spawnNpc(player.coords.translateZ(1), target)
            val other = registerPlayer(player.coords.translateX(1))
            player.queueHit(npc, 1, HitType.Melee, 0)
            player.queueHit(other, 1, HitType.Magic, 8)
            player.queueHit(delay = 1, type = HitType.Typeless, damage = 8)
            advance(ticks = 2)
            assertEquals(1, player.vars[vengeance_varbits.active])
            assertEquals(100, npc.hitpoints)
            player.queueHit(npc, 1, HitType.Magic, 8)
            advance(ticks = 2)
            assertEquals(94, npc.hitpoints)
            assertEquals(0, player.vars[vengeance_varbits.active])
        }

    @Test
    fun GameTestState.`simultaneous npc hits only reflect the first hit`() = runVengeanceTest {
        cast()
        val first = spawnNpc(player.coords.translateZ(1), target)
        val second = spawnNpc(player.coords.translateX(1), target)
        player.queueHit(first, 1, HitType.Melee, 20)
        player.queueHit(second, 1, HitType.Melee, 40)
        advance(ticks = 2)
        assertEquals(85, first.hitpoints)
        assertEquals(100, second.hitpoints)
    }

    @Test
    fun GameTestState.`lethal damage reflects only the remaining hitpoints`() = runVengeanceTest {
        cast()
        player.stats[stats.hitpoints] = 10
        val npc = spawnNpc(player.coords.translateZ(1), target)
        player.queueHit(npc, 1, HitType.Melee, 40)
        advance(ticks = 2)
        assertEquals(0, player.hitpoints)
        assertEquals(93, npc.hitpoints)
        assertEquals(0, player.vars[vengeance_varbits.active])
    }

    @Test
    fun GameTestState.`one damage consumes the buff even though reflection rounds to zero`() =
        runVengeanceTest {
            cast()
            val npc = spawnNpc(player.coords.translateZ(1), target)
            player.queueHit(npc, 1, HitType.Melee, 1)
            advance(ticks = 2)
            assertEquals(100, npc.hitpoints)
            assertEquals(0, player.vars[vengeance_varbits.active])
        }

    @Test
    fun GameTestState.`blocked npc damage does not consume vengeance`() = runVengeanceTest {
        cast()
        VarPlayerIntMapSetter.set(player, varbits.protect_from_melee, 1)
        val npc = spawnNpc(player.coords.translateZ(1), target)
        player.queueHit(npc, 1, HitType.Melee, 20)
        advance(ticks = 2)
        assertEquals(99, player.hitpoints)
        assertEquals(100, npc.hitpoints)
        assertEquals(1, player.vars[vengeance_varbits.active])
    }

    @Test
    fun GameTestState.`failed self casts do not grant a buff cooldown or sound`() =
        runVengeanceTest { deps ->
            val sounds = captureSounds(deps.zones)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Standard.varValue)
            cast()
            assertEquals(0, player.vars[vengeance_varbits.active])
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Lunars.varValue)
            player.setCurrentLevel(stats.magic, 1)
            cast()
            assertEquals(0, player.vars[vengeance_varbits.active])
            player.setCurrentLevel(stats.magic, 99)
            player.inv[0] = null
            cast()
            assertEquals(0, player.vars[vengeance_varbits.active])
            assertEquals(0, player.vengeanceExpiry)
            assertEquals(0, player.vars[vengeance_varbits.cooldown])
            assertTrue(sounds.isEmpty())
        }

    @Test
    fun GameTestState.`both spells consume their own rune requirements once`() =
        runVengeanceTest { deps ->
            player.inv[0] = null
            val selfSpell = checkNotNull(deps.spells.getObjSpell(objs.spell_vengeance))
            for ((slot, req) in selfSpell.objReqs.withIndex()) {
                player.inv[slot] = InvObj(req.obj, req.count * 3)
            }
            cast()
            val recipient = registerPlayer(player.coords.translateX(1))
            castOther(deps, recipient)
            for (req in selfSpell.objReqs) {
                assertEquals(req.count * 2, player.inv.count(req.obj))
            }
            advance(ticks = player.vengeanceExpiry - player.currentMapClock)
            player.inv.fillNulls()
            val otherSpell = checkNotNull(deps.spells.getObjSpell(objs.spell_vengeance_other))
            for ((slot, req) in otherSpell.objReqs.withIndex()) {
                player.inv[slot] = InvObj(req.obj, req.count * 3)
            }
            castOther(deps, recipient)
            assertEquals(1, recipient.vars[vengeance_varbits.active])
            for (req in otherSpell.objReqs) {
                assertEquals(req.count * 2, player.inv.count(req.obj))
            }
        }

    @Test
    fun GameTestState
        .`active recipients reject further casts without spending runes or cooldown`() =
        runVengeanceTest { deps ->
            val recipient = registerPlayer(player.coords.translateX(1))
            VarPlayerIntMapSetter.set(recipient, vengeance_varbits.active, 1)
            val sounds = captureSounds(deps.zones)
            castOther(deps, recipient)
            assertEquals(0, player.vengeanceExpiry)
            assertEquals(0, player.vars[vengeance_varbits.cooldown])
            assertMessageSent("That player already has Vengeance active.")
            assertTrue(sounds.isEmpty())
        }

    private fun GameTestScope.captureSounds(zones: ZoneUpdateMap): MutableList<SoundArea> {
        val sounds = mutableListOf<SoundArea>()
        eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
            sounds += zones[ZoneKey.from(player.coords)]?.filterIsInstance<SoundArea>().orEmpty()
        }
        return sounds
    }

    private fun GameTestScope.cast() {
        player.ifButton(vengeance_components.cast)
        advance(ticks = 1)
    }

    private fun GameTestScope.castOther(deps: Dependencies, target: Player) {
        deps.interactions.interact(player, target, vengeance_components.cast_other, -1, null)
        advance(ticks = 1)
    }

    private fun GameTestState.runVengeanceTest(test: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            Vengeance::class,
            NpcHitScript::class,
            PlayerHitScript::class,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
        ) { deps ->
            player.teleport(CoordGrid(3200, 3200, 0))
            player.setCurrentLevel(stats.magic, 99)
            player.stats[stats.hitpoints] = 99
            player.inv[0] = InvObj(objs.rune_pouch)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Lunars.varValue)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            test(deps)
        }

    class Dependencies
    @Inject
    constructor(
        val spells: MagicSpellRegistry,
        val interactions: PlayerTInteractions,
        val players: PlayerList,
        val zones: ZoneUpdateMap,
    )

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
        private var Player.vengeanceExpiry by intVarp(vengeance_varps.expiry)
        private val target =
            npcTypeFactory.create {
                name = "Vengeance attacker"
                size = 1
                hitpoints = 100
                defaultMode = NpcMode.None
            }
    }
}

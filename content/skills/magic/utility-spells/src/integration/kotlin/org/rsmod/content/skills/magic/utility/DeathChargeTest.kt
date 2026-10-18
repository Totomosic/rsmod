package org.rsmod.content.skills.magic.utility

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.zone.payload.SoundArea
import org.junit.jupiter.api.Test
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
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
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.seq.EntitySeq
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

class DeathChargeTest {
    @Test
    fun GameTestState.`successful casts play the spell sound without replaying on cooldown`() =
        runDeathChargeTest { deps ->
            val sounds = captureSounds(deps.zones)
            cast()
            assertEquals(listOf(SoundArea(5034, 0, 1, 10, 1, 0, 0)), sounds)
            sounds.clear()
            cast()
            assertTrue(sounds.isEmpty())
            advance(ticks = player.deathChargeExpiry - player.currentMapClock - 1)
            cast()
            assertEquals(listOf(SoundArea(5034, 0, 1, 10, 1, 0, 0)), sounds)
        }

    @Test
    fun GameTestState
        .`cast uses the human animation rather than the death charge effect animation`() =
        runDeathChargeTest {
            val animations = mutableListOf<Int>()
            eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (player.pendingSequence != EntitySeq.NULL) {
                    animations += player.pendingSequence.id
                }
            }
            cast()
            // Sequence 8983 belongs to the effect model in spotanim 1854, not the player rig.
            val effectAnimation = checkNotNull(cacheTypes.spotanims[1854]).anim
            assertFalse(effectAnimation in animations)
            assertEquals(listOf(8970), animations) // human_cast_selfimbue
        }

    @Test
    fun GameTestState.`two kills restore energy and a third has no effect`() = runDeathChargeTest {
        cast()
        assertEquals(2, player.deathChargeStacks)
        kill()
        assertEquals(150, player.specialEnergy)
        assertEquals(1, player.deathChargeStacks)
        kill()
        assertEquals(300, player.specialEnergy)
        assertEquals(0, player.deathChargeStacks)
        kill()
        assertEquals(300, player.specialEnergy)
        assertEquals(0, player.vars[death_charge_varbits.active])
        assertEquals(1, player.vars[death_charge_varbits.cooldown])
    }

    @Test
    fun GameTestState.`energy caps at full and consumes a stack even when full`() =
        runDeathChargeTest {
            cast()
            player.specialEnergy = 950
            kill()
            assertEquals(1000, player.specialEnergy)
            kill()
            assertEquals(1000, player.specialEnergy)
            assertEquals(0, player.deathChargeStacks)
        }

    @Test
    fun GameTestState.`both stacks expire together exactly 100 ticks after casting`() =
        runDeathChargeTest {
            cast()
            val expiry = player.deathChargeExpiry
            advance(ticks = expiry - player.currentMapClock - 1)
            assertEquals(2, player.deathChargeStacks)
            advance(ticks = 1)
            assertEquals(0, player.deathChargeStacks)
            assertEquals(0, player.vars[death_charge_varbits.active])
            assertEquals(0, player.vars[death_charge_varbits.cooldown])
            kill()
            assertEquals(0, player.specialEnergy)
        }

    @Test
    fun GameTestState.`using a stack does not extend the expiry`() = runDeathChargeTest {
        cast()
        val expiry = player.deathChargeExpiry
        advance(ticks = 50)
        kill()
        assertEquals(1, player.deathChargeStacks)
        advance(ticks = expiry - player.currentMapClock)
        assertEquals(0, player.deathChargeStacks)
        kill()
        assertEquals(150, player.specialEnergy)
    }

    @Test
    fun GameTestState.`cooldown survives consuming both stacks and allows recast at expiry`() =
        runDeathChargeTest {
            cast()
            val expiry = player.deathChargeExpiry
            kill()
            kill()
            cast()
            assertEquals(0, player.deathChargeStacks)
            assertEquals(expiry, player.deathChargeExpiry)
            assertMessageSent("You can only cast Death Charge once every 60 seconds.")
            advance(ticks = expiry - player.currentMapClock - 1)
            cast() // Button processes on the exact expiry tick.
            assertEquals(2, player.deathChargeStacks)
            assertEquals(expiry + 100, player.deathChargeExpiry)
        }

    @Test
    fun GameTestState.`only the player with kill credit recovers energy`() = runDeathChargeTest {
        cast()
        val other = registerPlayer(player.coords.translateX(1))
        other.specialEnergy = 0
        kill(hero = other)
        assertEquals(0, player.specialEnergy)
        assertEquals(0, other.specialEnergy)
        assertEquals(2, player.deathChargeStacks)
    }

    @Test
    fun GameTestState.`multiple lethal splats on one npc only consume one stack`() =
        runDeathChargeTest {
            cast()
            val npc = spawnNpc(player.coords.translateZ(1), target)
            npc.heroPoints(player, 7)
            npc.queueHit(player, delay = 1, HitType.Melee, damage = 1)
            advance(ticks = 1)
            assertEquals(0, player.specialEnergy)
            assertEquals(2, player.deathChargeStacks)
            repeat(3) { npc.queueHit(player, delay = 1, HitType.Melee, damage = 7) }
            advance(ticks = 1)
            assertEquals(150, player.specialEnergy)
            assertEquals(1, player.deathChargeStacks)
        }

    @Test
    fun GameTestState.`separate npc kills on the same tick consume separate stacks`() =
        runDeathChargeTest {
            cast()
            repeat(3) { offset ->
                val npc = spawnNpc(player.coords.translateZ(offset + 1), target)
                npc.heroPoints(player, 7)
                npc.queueHit(player, delay = 1, HitType.Melee, damage = 7)
            }
            advance(ticks = 1)
            assertEquals(300, player.specialEnergy)
            assertEquals(0, player.deathChargeStacks)
        }

    @Test
    fun GameTestState.`failed casts grant no stacks cooldown or sound`() =
        runDeathChargeTest { deps ->
            val sounds = captureSounds(deps.zones)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Standard.varValue)
            cast()
            assertEquals(0, player.deathChargeStacks)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Arceuus.varValue)
            player.setCurrentLevel(stats.magic, 1)
            cast()
            assertEquals(0, player.deathChargeStacks)
            player.setCurrentLevel(stats.magic, 99)
            player.inv[0] = null
            cast()
            assertEquals(0, player.deathChargeStacks)
            assertEquals(0, player.deathChargeExpiry)
            assertTrue(sounds.isEmpty())
        }

    @Test
    fun GameTestState.`successful cast consumes cache rune requirements only once`() =
        runDeathChargeTest { deps ->
            player.inv[0] = null
            val spell = checkNotNull(deps.spells.getObjSpell(objs.spell_death_charge))
            for ((slot, req) in spell.objReqs.withIndex()) {
                player.inv[slot] = InvObj(req.obj, req.count * 3)
            }
            cast()
            assertEquals(2, player.deathChargeStacks)
            cast() // Rejected by cooldown before consuming runes.
            for (req in spell.objReqs) {
                assertEquals(req.count * 2, player.inv.count(req.obj))
            }
        }

    private fun GameTestState.runDeathChargeTest(test: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            TestModule,
            DeathCharge::class,
            NpcHitScript::class,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
        ) { deps ->
            player.teleport(CoordGrid(3200, 3200, 0))
            player.setCurrentLevel(stats.magic, 99)
            player.specialEnergy = 0
            player.inv[0] = InvObj(objs.rune_pouch)
            VarPlayerIntMapSetter.set(player, varbits.spellbook, Spellbook.Arceuus.varValue)
            player.ifOpenOverlay(interfaces.magic_spellbook, components.toplevel_target_side6)
            test(deps)
        }

    private fun GameTestScope.cast() {
        player.ifButton(death_charge_components.cast)
        advance(ticks = 1)
    }

    private fun GameTestScope.captureSounds(zones: ZoneUpdateMap): MutableList<SoundArea> {
        val sounds = mutableListOf<SoundArea>()
        eventBus.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
            sounds += zones[ZoneKey.from(player.coords)]?.filterIsInstance<SoundArea>().orEmpty()
        }
        return sounds
    }

    private fun GameTestScope.kill(hero: Player = player) {
        val npc = spawnNpc(player.coords.translateZ(1), target)
        npc.heroPoints(hero, 7)
        npc.queueHit(player, delay = 1, HitType.Melee, damage = 7)
        advance(ticks = 1)
    }

    class Dependencies
    @Inject
    constructor(val spells: MagicSpellRegistry, val zones: ZoneUpdateMap)

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
        private var Player.specialEnergy by intVarp(varps.sa_energy)
        private var Player.deathChargeStacks by intVarp(death_charge_varps.stacks)
        private var Player.deathChargeExpiry by intVarp(death_charge_varps.expiry)
        private val target =
            npcTypeFactory.create {
                name = "Death Charge target"
                size = 1
                hitpoints = 7
                defaultMode = NpcMode.None
            }
    }
}

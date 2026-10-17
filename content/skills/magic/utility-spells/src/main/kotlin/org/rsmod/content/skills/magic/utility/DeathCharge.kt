package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varps
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class DeathCharge
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(death_charge_components.cast) {
            protectedAccess.launch(player) { cast() }
        }
        onEvent<NpcHitEvents.Kill> { killer.recoverDeathCharge() }
        onPlayerSoftTimer(death_charge_timers.expire) {
            player.expireDeathCharge()
            player.clearSoftTimer(death_charge_timers.expire)
        }
        onPlayerLogin { player.expireDeathCharge() }
    }

    private fun ProtectedAccess.cast() {
        if (player.deathChargeExpiry > mapClock) {
            mes("You can only cast Death Charge once every 60 seconds.")
            return
        }
        val spell = checkNotNull(spells.getObjSpell(objs.spell_death_charge))
        if (runes.attemptCast(player, spell) !is MagicRuneManager.CastResult.Success) {
            return
        }
        player.deathChargeStacks = 2
        player.deathChargeExpiry = mapClock + DEATH_CHARGE_DURATION
        player.deathChargeActive = 1
        player.deathChargeCooldown = 1
        softTimer(death_charge_timers.expire, DEATH_CHARGE_DURATION)
        anim(death_charge_seqs.cast)
        spotanim(death_charge_spots.cast)
        statAdvance(stats.magic, spell.castXp)
        mes("You have two Death Charge stacks for 60 seconds.")
    }
}

private fun Player.recoverDeathCharge() {
    if (deathChargeStacks <= 0 || deathChargeExpiry <= currentMapClock) {
        return
    }
    specialEnergy = minOf(constants.sa_max_energy, specialEnergy + 150)
    deathChargeStacks--
    if (deathChargeStacks == 0) deathChargeActive = 0
    spotanim(death_charge_spots.recover)
    mes("Your Death Charge restores 15% special attack energy.")
}

private fun Player.expireDeathCharge() {
    deathChargeStacks = 0
    deathChargeExpiry = 0
    deathChargeActive = 0
    deathChargeCooldown = 0
}

private const val DEATH_CHARGE_DURATION = 100 // 60 seconds at 600 ms per tick.

private var Player.deathChargeStacks by intVarp(death_charge_varps.stacks)
private var Player.deathChargeExpiry by intVarp(death_charge_varps.expiry)
private var Player.specialEnergy by intVarp(varps.sa_energy)
private var Player.deathChargeActive by intVarBit(death_charge_varbits.active)
private var Player.deathChargeCooldown by intVarBit(death_charge_varbits.cooldown)

object death_charge_components : ComponentReferences() {
    val cast = find("magic_spellbook:death_charge")
}

object death_charge_varps : VarpReferences() {
    val stacks = find("death_charge_stacks")
    val expiry = find("death_charge_expiry")
}

internal object DeathChargeVarpBuilder : VarpBuilder() {
    init {
        build("death_charge_stacks") { temporary = true }
        build("death_charge_expiry") { temporary = true }
    }
}

object death_charge_varbits : VarBitReferences() {
    val active = find("arceuus_death_charge_active")
    val cooldown = find("arceuus_death_charge_cooldown")
}

internal object death_charge_seqs : SeqReferences() {
    // `death_charge` animates the spot effect model; it distorts the player when used here.
    val cast = find("human_cast_selfimbue")
}

internal object death_charge_spots : SpotanimReferences() {
    val cast = find("death_charge_upgrade_cast_spotanim")
    val recover = find("death_charge_end_upgrade_spotanim")
}

internal object death_charge_timers : TimerReferences() {
    val expire = find("death_charge_expire")
}

package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.area.checker.WildernessArea
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.output.UpdateRun
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onApPlayerT
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class EnergyTransfer
@Inject
constructor(private val spells: MagicSpellRegistry, private val runes: MagicRuneManager) :
    PluginScript() {
    override fun ScriptContext.startup() {
        // The targeted-spell packet interrupts the caster's pending action. Casting itself
        // neither checks nor changes actionDelay, so existing attack cooldowns keep ticking.
        onApPlayerT(energy_transfer_components.cast) { cast(it.target) }
    }

    private fun ProtectedAccess.cast(target: Player) {
        if (target === player || !target.isValidTarget()) return
        if (target.vars[varbits.option_acceptaid] == 0) {
            mes("That player is not accepting aid.")
            return
        }
        if (
            (WildernessArea.contains(player.coords) && !mapMultiway()) ||
                (WildernessArea.contains(target.coords) && !inArea(areas.multiway, target.coords))
        ) {
            mes("You cannot cast Energy Transfer in a single-way combat area of the Wilderness.")
            return
        }
        if (player.hitpoints <= HITPOINT_COST) {
            mes("You need more hitpoints to cast this spell.")
            return
        }
        if (player.specialEnergy < constants.sa_max_energy) {
            mes("You need 100% special attack energy to cast this spell.")
            return
        }
        val spell = checkNotNull(spells.getObjSpell(objs.spell_energy_transfer))
        if (runes.attemptCast(player, spell) !is MagicRuneManager.CastResult.Success) return

        // Pay HP immediately, so another cast cannot spend the same remaining hitpoints.
        player.specialEnergy = 0
        takeInstantHit(HitType.Typeless, HITPOINT_COST)
        target.specialEnergy = constants.sa_max_energy
        target.runEnergy = constants.run_max_energy
        UpdateRun.energy(target, target.runEnergy)
        anim(energy_transfer_seqs.cast)
        target.spotanim(energy_transfer_spots.receive)
        statAdvance(stats.magic, spell.castXp)
    }
}

private const val HITPOINT_COST = 10
private var Player.specialEnergy by intVarp(varps.sa_energy)

object energy_transfer_components : ComponentReferences() {
    val cast = find("magic_spellbook:energy_trans")
}

internal object energy_transfer_seqs : SeqReferences() {
    val cast = find("quest_lunar_pushing_magic_animation")
}

internal object energy_transfer_spots : SpotanimReferences() {
    val receive = find("quest_lunar_spellbook_energy_exchange_spot_anim")
}

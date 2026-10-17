package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onApPlayerT
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
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class Vengeance
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val npcs: NpcList,
    private val npcHitModifier: NpcHitModifier,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(vengeance_components.cast) {
            protectedAccess.launch(player) { cast(player, other = false) }
        }
        onApPlayerT(vengeance_components.cast_other) { cast(it.target, other = true) }
        onEvent<PlayerHitEvents.Impact> { reflect(this) }
        onPlayerSoftTimer(vengeance_timers.cooldown) {
            player.vengeanceCooldown = 0
            player.clearSoftTimer(vengeance_timers.cooldown)
        }
        onPlayerLogin {
            player.vengeanceActive = 0
            player.vengeanceCooldown = 0
            player.vengeanceExpiry = 0
        }
    }

    private fun ProtectedAccess.cast(target: Player, other: Boolean) {
        if (player.vengeanceExpiry > mapClock) {
            mes("You can only cast Vengeance spells once every 30 seconds.")
            return
        }
        if (target.hitpoints <= 0) return
        if (target.vengeanceActive != 0) {
            mes(
                "${if (target === player) "You already have" else "That player already has"} Vengeance active."
            )
            return
        }
        val spellObj = if (other) objs.spell_vengeance_other else objs.spell_vengeance
        val spell = checkNotNull(spells.getObjSpell(spellObj))
        if (runes.attemptCast(player, spell) !is MagicRuneManager.CastResult.Success) return

        target.vengeanceActive = 1
        player.vengeanceExpiry = mapClock + VENGEANCE_COOLDOWN
        player.vengeanceCooldown = 1
        softTimer(vengeance_timers.cooldown, VENGEANCE_COOLDOWN)
        anim(if (other) vengeance_seqs.cast_other else vengeance_seqs.cast)
        target.spotanim(if (other) vengeance_spots.cast_other else vengeance_spots.cast)
        statAdvance(stats.magic, spell.castXp)
    }

    private fun reflect(event: PlayerHitEvents.Impact) {
        val (player, hit, damage) = event
        if (player.vengeanceActive == 0 || damage <= 0 || !hit.isFromNpc) return
        // Consume before queuing the reflection so simultaneous hits can only trigger it once.
        player.vengeanceActive = 0
        player.say("Taste vengeance!")
        val attacker = hit.resolveNpcSource(npcs) ?: return
        if (attacker.hitpoints <= 0) return
        val reflected = damage * 3 / 4
        attacker.heroPoints(player, minOf(attacker.hitpoints, reflected))
        attacker.queueHit(player, 1, HitType.Typeless, reflected, npcHitModifier)
    }
}

private const val VENGEANCE_COOLDOWN = 50 // 30 seconds at 600 ms per tick.

private var Player.vengeanceActive by intVarBit(vengeance_varbits.active)
private var Player.vengeanceCooldown by intVarBit(vengeance_varbits.cooldown)
private var Player.vengeanceExpiry by intVarp(vengeance_varps.expiry)

object vengeance_components : ComponentReferences() {
    val cast = find("magic_spellbook:vengeance")
    val cast_other = find("magic_spellbook:vengeance_other")
}

object vengeance_varbits : VarBitReferences() {
    val active = find("vengeance_rebound")
    val cooldown = find("vengeance_timelimit")
}

object vengeance_varps : VarpReferences() {
    val expiry = find("vengeance_cooldown_expiry")
}

internal object VengeanceVarpBuilder : VarpBuilder() {
    init {
        build("vengeance_cooldown_expiry") { temporary = true }
    }
}

internal object vengeance_seqs : SeqReferences() {
    val cast = find("vengeance_spell_anim_nostalling")
    val cast_other = find("quest_lunar_pushing_magic_animation")
}

internal object vengeance_spots : SpotanimReferences() {
    // Cache names are reversed: RuneLite's VENGEANCE is 726 and VENGEANCE_OTHER is 725.
    val cast = find("quest_lunar_spellbook_vengeance_other_spot_anim")
    val cast_other = find("quest_lunar_spellbook_vengeance_spot_anim")
}

internal object vengeance_timers : TimerReferences() {
    val cooldown = find("vengeance_cooldown")
}

package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.magic.MagicSpellCast
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.enumVarBit
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class SpellbookSwap
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(spellbook_swap_components.cast) {
            protectedAccess.launch(player) {
                when (op) {
                    IfButtonOp.Op1 -> chooseBook()
                    IfButtonOp.Op2 -> cast(Spellbook.Standard)
                    IfButtonOp.Op3 -> cast(Spellbook.Ancients)
                    IfButtonOp.Op4 -> cast(Spellbook.Arceuus)
                    else -> Unit
                }
            }
        }
        onEvent<MagicSpellCast> {
            if (player.swapActive != 0 && spellbook != Spellbook.Lunars) {
                player.returnToLunar()
            }
        }
        onPlayerSoftTimer(spellbook_swap_timers.expire) { player.returnToLunar() }
        onPlayerLogout { player.returnToLunar() }
        onPlayerLogin { player.returnToLunar() }
    }

    private suspend fun ProtectedAccess.chooseBook() {
        val spell = checkNotNull(spells.getObjSpell(objs.spell_spellbook_swap))
        if (!runes.canCastSpell(player, spell)) return
        val book =
            choice3(
                "Standard",
                Spellbook.Standard,
                "Ancient",
                Spellbook.Ancients,
                "Arceuus",
                Spellbook.Arceuus,
                title = "Choose a spellbook",
            )
        // Recheck after the dialogue: requirements may have changed while choosing.
        cast(book)
    }

    private suspend fun ProtectedAccess.cast(book: Spellbook) {
        val spell = checkNotNull(spells.getObjSpell(objs.spell_spellbook_swap))
        if (runes.attemptCast(player, spell) !is MagicRuneManager.CastResult.Success) return
        // The engine keeps red-x targets live and distinguishes new and ongoing approaches.
        anim(spellbook_swap_seqs.cast)
        spotanim(spellbook_swap_spots.cast)
        delay(3)
        statAdvance(stats.magic, spell.castXp)
        player.clearAutocast()
        player.swapActive = 1
        player.spellbook = book
        softTimer(spellbook_swap_timers.expire, 200) // Two minutes at 600 ms per tick.
    }

    private fun Player.returnToLunar() {
        clearSoftTimer(spellbook_swap_timers.expire)
        if (swapActive == 0) return
        swapActive = 0
        // Returning books must preserve combat, movement, and other pending actions.
        clearAutocast()
        spellbook = Spellbook.Lunars
    }
}

private fun Player.clearAutocast() {
    autocastEnabled = false
    autocastSpell = 0
    defensiveCasting = false
}

private var Player.spellbook by enumVarBit<Spellbook>(varbits.spellbook)
private var Player.autocastEnabled by boolVarBit(varbits.autocast_set)
private var Player.autocastSpell by intVarBit(varbits.autocast_spell)
private var Player.defensiveCasting by boolVarBit(varbits.autocast_defmode)
private var Player.swapActive by intVarp(spellbook_swap_varps.active)

object spellbook_swap_components : ComponentReferences() {
    val cast = find("magic_spellbook:spellbook_swap")
}

object spellbook_swap_varps : VarpReferences() {
    val active = find("spellbook_swap_active")
}

internal object SpellbookSwapVarpBuilder : VarpBuilder() {
    init {
        // Persist the marker so a login after an unclean shutdown also restores Lunar.
        build("spellbook_swap_active") {}
    }
}

internal object spellbook_swap_seqs : SeqReferences() {
    val cast = find("dream_player_spellbook_swap")
}

internal object spellbook_swap_spots : SpotanimReferences() {
    val cast = find("dream_spellbook_spotanim")
}

internal object spellbook_swap_timers : TimerReferences() {
    val expire = find("spellbook_swap_expire")
}

package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Returns from Spellbook Swap without a buff, attack delay, or change to the player's target. */
class MarkOfDarkness
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(mark_of_darkness_components.cast) {
            protectedAccess.launch(player) {
                val spell = checkNotNull(spells.getObjSpell(objs.spell_mark_of_darkness))
                if (runes.attemptCast(player, spell) is MagicRuneManager.CastResult.Success) {
                    statAdvance(stats.magic, spell.castXp)
                }
            }
        }
    }
}

object mark_of_darkness_components : ComponentReferences() {
    val cast = find("magic_spellbook:mark_of_darkness")
}

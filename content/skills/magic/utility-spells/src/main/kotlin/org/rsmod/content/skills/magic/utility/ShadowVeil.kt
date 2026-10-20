package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.game.type.synth.SynthType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Returns from Spellbook Swap without a buff, cooldown, or change to the player's target. */
class ShadowVeil
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val world: WorldRepository,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(shadow_veil_components.cast) {
            protectedAccess.launch(player) {
                val spell = checkNotNull(spells.getObjSpell(objs.spell_shadow_veil))
                if (runes.attemptCast(player, spell) is MagicRuneManager.CastResult.Success) {
                    anim(shadow_veil_seqs.cast)
                    spotanim(shadow_veil_spots.cast)
                    world.soundArea(player, shadow_veil_synths.cast, radius = 10)
                    statAdvance(stats.magic, spell.castXp)
                }
            }
        }
    }
}

object shadow_veil_components : ComponentReferences() {
    val cast = find("magic_spellbook:shadow_veil")
}

internal object shadow_veil_seqs : SeqReferences() {
    val cast = find("human_spellcast_shadowveil")
}

internal object shadow_veil_spots : SpotanimReferences() {
    val cast = find("shadow_veil_cast_spotanim")
}

internal object shadow_veil_synths {
    // https://oldschool.runescape.wiki/w/File:Shadow_Veil.ogg
    val cast = SynthType(5032, "shadow_veil_cast")
}

package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.enumVarBit
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class SpellbookCommand @Inject constructor(private val combat: PlayerAttackManager) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onCommand(
            "spellbook",
            "Change your spellbook",
            {
                val book =
                    when (args.singleOrNull()?.lowercase()) {
                        "standard" -> Spellbook.Standard
                        "ancient" -> Spellbook.Ancients
                        "arceuus" -> Spellbook.Arceuus
                        "lunar" -> Spellbook.Lunars
                        else -> null
                    }
                if (book == null) {
                    player.mes("Use as ::spellbook standard|ancient|arceuus|lunar")
                } else if (player.spellbook != book) {
                    combat.stopCombat(player)
                    player.autocastEnabled = false
                    player.autocastSpell = 0
                    player.defensiveCasting = false
                    player.spellbook = book
                    player.mes("Spellbook changed to ${args.single().lowercase()}.")
                }
            },
        )
    }
}

private var Player.spellbook by enumVarBit<Spellbook>(varbits.spellbook)
private var Player.autocastEnabled by boolVarBit(varbits.autocast_set)
private var Player.autocastSpell by intVarBit(varbits.autocast_spell)
private var Player.defensiveCasting by boolVarBit(varbits.autocast_defmode)

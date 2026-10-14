package org.rsmod.content.interfaces.settings.scripts

import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.content.interfaces.settings.configs.keybind_varbits
import org.rsmod.game.entity.Player
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class KeybindSettingsScript : PluginScript() {
    // Setting ids from revision 233's settings_get_keybind clientscript (3963).
    private val bindings =
        mapOf(
            16 to Binding(keybind_varbits.combat, 1),
            17 to Binding(keybind_varbits.prayer, 6),
            18 to Binding(keybind_varbits.settings, 11),
            19 to Binding(keybind_varbits.stats, 2),
            20 to Binding(keybind_varbits.magic, 7),
            21 to Binding(keybind_varbits.emotes, 12),
            22 to Binding(keybind_varbits.journal, 3),
            23 to Binding(keybind_varbits.friends, 8),
            24 to Binding(keybind_varbits.friends_chat, 0),
            25 to Binding(keybind_varbits.inventory, 4),
            26 to Binding(keybind_varbits.account, 9),
            27 to Binding(keybind_varbits.music, 0),
            28 to Binding(keybind_varbits.equipment, 5),
            29 to Binding(keybind_varbits.logout, 10),
        )

    override fun ScriptContext.startup() {
        onPlayerLogin { player.initializeKeybinds() }
        onCommand("localscape_keybind") {
            desc = "Save an in-game side-panel keybind"
            cheat {
                if (args.size == 2) {
                    val setting = args[0].toIntOrNull()
                    val key = args[1].toIntOrNull()
                    if (setting != null && key != null) {
                        player.selectKeybind(setting, key)
                    }
                }
            }
        }
        onCommand("localscape_keybind_reset") {
            desc = "Restore default side-panel keybinds"
            cheat {
                if (args.isEmpty() && player.ui.containsOverlay(interfaces.settings)) {
                    player.restoreKeybinds()
                }
            }
        }
    }

    private fun Player.initializeKeybinds() {
        // A stored zero means deliberately unbound. Initialize only absent varps.
        val missing = bindings.values.map { it.varbit.baseVar }.filter { it !in vars }.toSet()
        for (binding in bindings.values) {
            if (binding.varbit.baseVar in missing) {
                VarPlayerIntMapSetter.set(this, binding.varbit, binding.defaultKey)
            }
        }
    }

    private fun Player.selectKeybind(setting: Int, key: Int) {
        if (!ui.containsOverlay(interfaces.settings) || key !in 0..13) {
            return
        }
        val selected = bindings[setting] ?: return
        // None (0) can be shared; F1-F12 (1-12) and Escape (13) select one tab.
        if (key != 0) {
            for (other in bindings.values) {
                if (other != selected && vars[other.varbit] == key) {
                    VarPlayerIntMapSetter.set(this, other.varbit, 0)
                }
            }
        }
        VarPlayerIntMapSetter.set(this, selected.varbit, key)
    }

    private fun Player.restoreKeybinds() {
        for (binding in bindings.values) {
            VarPlayerIntMapSetter.set(this, binding.varbit, binding.defaultKey)
        }
    }

    private data class Binding(val varbit: VarBitType, val defaultKey: Int)
}

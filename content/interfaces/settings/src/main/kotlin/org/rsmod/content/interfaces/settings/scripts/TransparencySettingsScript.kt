package org.rsmod.content.interfaces.settings.scripts

import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onCommand
import org.rsmod.content.interfaces.settings.configs.setting_varbits
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class TransparencySettingsScript : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("localscape_transparency") {
            desc = "Save the in-game inventory or chatbox transparency preference"
            cheat {
                if (args.size != 2) {
                    return@cheat
                }
                val preference =
                    when (args[0]) {
                        "inventory" -> setting_varbits.side_transparency
                        "chatbox" -> varbits.chatbox_transparency
                        else -> return@cheat
                    }
                val value = args[1].toIntOrNull() ?: return@cheat
                if (value !in 0..1) {
                    return@cheat
                }
                // Native scripts already applied the toggle locally. Save its exact value,
                // including zero, and sync the packed varp without changing adjacent settings.
                // Settings may have closed before this queued client command arrives.
                VarPlayerIntMapSetter.set(player, preference, value)
            }
        }
    }
}

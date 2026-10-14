package org.rsmod.content.interfaces.settings.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.resyncVar
import org.rsmod.api.script.onIfClose
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The quest list's settings (cog) button opens All Settings already searched for the quest list
 * settings (sorting, filters, text size/shadows and colours), as in OSRS.
 *
 * The settings interface shows search results instead of a category while `floater_is_searching` is
 * set, using the search text in varc `settings_search_string`. The server sets that text with
 * clientscript 5968 (`settings_search_override`), which sits next to `settings_questlist_sort` in
 * the cache. Picking a category tab in the client clears the search again.
 */
class QuestListSettingsScript
@Inject
constructor(private val protectedAccess: ProtectedAccessLauncher) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(questlist_settings_components.settings_button) {
            player.openQuestListSettings()
        }
        onIfClose(interfaces.settings) { player.floaterSearching = false }
    }

    private fun Player.openQuestListSettings() {
        val opened =
            protectedAccess.launch(this) {
                player.floaterSearching = true
                player.floaterSearchKeyboard = false
                // The client clears these bits locally (e.g. when a category tab is picked), so
                // always resend the base varp instead of relying on a server-side diff.
                player.resyncVar(questlist_settings_varbits.floater_is_searching)
                runClientScript(SETTINGS_SEARCH_OVERRIDE, QUEST_LIST_SEARCH)
                ifOpenOverlay(interfaces.settings)
            }
        if (!opened) {
            mes("Please finish what you are doing before opening the settings menu.")
        }
    }

    private companion object {
        /** `[clientscript,settings_search_override](string)`: sets `%settings_search_string`. */
        const val SETTINGS_SEARCH_OVERRIDE = 5968

        /** Every quest list setting has "quest list" among its search keywords. */
        const val QUEST_LIST_SEARCH = "quest list"
    }
}

private var Player.floaterSearching by boolVarBit(questlist_settings_varbits.floater_is_searching)
private var Player.floaterSearchKeyboard by
    boolVarBit(questlist_settings_varbits.floater_search_listen_for_keyboard)

typealias questlist_settings_components = QuestListSettingsComponents

typealias questlist_settings_varbits = QuestListSettingsVarBits

object QuestListSettingsComponents : ComponentReferences() {
    val settings_button = find("questlist:settings_button")
}

object QuestListSettingsVarBits : VarBitReferences() {
    val floater_is_searching = find("floater_is_searching")
    val floater_search_listen_for_keyboard = find("floater_search_listen_for_keyboard")
}

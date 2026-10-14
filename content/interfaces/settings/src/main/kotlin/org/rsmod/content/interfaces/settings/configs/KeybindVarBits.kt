package org.rsmod.content.interfaces.settings.configs

import org.rsmod.api.type.refs.varbit.VarBitReferences

typealias keybind_varbits = KeybindVarBits

object KeybindVarBits : VarBitReferences() {
    val combat = find("stone_combat_key")
    val stats = find("stone_stats_key")
    val journal = find("stone_journal_key")
    val inventory = find("stone_inv_key")
    val equipment = find("stone_worn_key")
    val prayer = find("stone_prayer_key")
    val magic = find("stone_magic_key")
    val friends_chat = find("stone_clanchat_key")
    val friends = find("stone_friends_key")
    val account = find("stone_account_key")
    val settings = find("stone_options1_key")
    val emotes = find("stone_options2_key")
    val music = find("stone_music_key")
    val logout = find("stone_logout_key")
}

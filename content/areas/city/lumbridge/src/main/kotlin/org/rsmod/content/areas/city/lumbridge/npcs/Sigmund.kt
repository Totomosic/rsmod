package org.rsmod.content.areas.city.lumbridge.npcs

import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpc1
import org.rsmod.content.areas.city.lumbridge.configs.lumbridge_npcs
import org.rsmod.game.entity.Npc
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Dialogue follows https://oldschool.runescape.wiki/w/Transcript:Sigmund ("Before Rune Mysteries";
 * neither Rune Mysteries nor The Lost Tribe are implemented yet).
 *
 * `lost_tribe_sigmund` is a multinpc; the handler is registered on the base type so that it fires
 * for whichever variant the player sees (op events are keyed by the npc's base type id).
 *
 * The visible variant's "Pickpocket" option (op3) is handled by the Thieving module.
 */
class Sigmund : PluginScript() {
    override fun ScriptContext.startup() {
        onOpNpc1(lumbridge_npcs.sigmund) { startDialogue(it.npc) }
    }

    private suspend fun ProtectedAccess.startDialogue(npc: Npc) =
        startDialogue(npc) { sigmundDialogue() }
}

private suspend fun Dialogue.sigmundDialogue() {
    chatNpc(neutral, "Can I help you?")
    val option = choice2("Do you have any quests for me?", ::anyQuests, "Who are you?", ::whoAreYou)
    option()
}

private suspend fun Dialogue.anyQuests() {
    chatPlayer(quiz, "Do you have any quests for me?")
    chatNpc(
        neutral,
        "I hear the Duke has a task for an adventurer. Otherwise, if you want to make " +
            "yourself useful, there are always evil monsters to slay.",
    )
    chatPlayer(happy, "Okay, I might just do that.")
}

private suspend fun Dialogue.whoAreYou() {
    chatPlayer(quiz, "Who are you?")
    chatNpc(neutral, "I'm the Duke's advisor.")
    chatPlayer(quiz, "Can you give me any advice then?")
    chatNpc(
        neutral,
        "I only advise the Duke. But if you want to make yourself useful, there are evil " +
            "goblins to slay on the other side of the river.",
    )
}

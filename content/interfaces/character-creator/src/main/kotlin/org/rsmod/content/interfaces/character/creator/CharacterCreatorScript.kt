package org.rsmod.content.interfaces.character.creator

import jakarta.inject.Inject
import org.rsmod.api.player.ui.ifClose
import org.rsmod.api.player.ui.ifOpenMainModal
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.content.interfaces.character.creator.configs.creator_components
import org.rsmod.content.interfaces.character.creator.configs.creator_interfaces
import org.rsmod.content.interfaces.character.creator.configs.creator_varbits
import org.rsmod.content.interfaces.character.creator.configs.creator_varps
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.idk.IdentKitTypeList
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Character Creator (`player_design`) shown to new players on their first login.
 *
 * Players start in Lumbridge rather than on Tutorial Island, so the creator opens on login until
 * the player confirms their design. Behaviour follows the OSRS Wiki:
 * - Each body part cycles through the identity kits available for the selected body type.
 * - Each colour cycles through the colours listed on the wiki's makeover pages.
 * - Selecting body type A or B changes the pronouns to "He/him" or "She/her" respectively, unless
 *   "They/them" is selected ([Makeover#Body types](https://oldschool.runescape.wiki/w/Makeover)).
 */
class CharacterCreatorScript
@Inject
constructor(private val eventBus: EventBus, private val identKits: IdentKitTypeList) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { player.onLogin() }

        for (bodyPart in DesignBodyPart.entries) {
            onIfModalButton(bodyPart.left) { player.cycleKit(bodyPart, step = -1) }
            onIfModalButton(bodyPart.right) { player.cycleKit(bodyPart, step = 1) }
        }

        for (colour in DesignColour.entries) {
            onIfModalButton(colour.left) { player.cycleColour(colour, step = -1) }
            onIfModalButton(colour.right) { player.cycleColour(colour, step = 1) }
        }

        onIfModalButton(creator_components.body_type_a) { player.selectBodyType(BODY_TYPE_A) }
        onIfModalButton(creator_components.body_type_b) { player.selectBodyType(BODY_TYPE_B) }
        onIfModalButton(creator_components.pronoun_options) { player.selectPronoun(it.comsub - 1) }
        onIfModalButton(creator_components.confirm) { player.confirmDesign() }
    }

    private fun Player.onLogin() {
        pronounVar = appearance.pronoun
        if (tutorial < TUTORIAL_COMPLETE) {
            openCharacterCreator()
        }
    }

    private fun Player.openCharacterCreator() {
        bodyTypeVar = appearance.bodyType
        pronounVar = appearance.pronoun
        ifOpenMainModal(creator_interfaces.player_design, eventBus)
        ifSetEvents(creator_components.pronoun_options, PRONOUN_OPTIONS, IfEvent.Op1)
    }

    private fun Player.cycleKit(bodyPart: DesignBodyPart, step: Int) {
        val available = availableKits(appearance.bodyType, bodyPart.part)
        if (available.isEmpty()) {
            return
        }
        val current = appearance.identKitSnapshot()[bodyPart.part].toInt()
        val index = available.indexOf(current)
        val next =
            if (index == -1) {
                available.first()
            } else {
                available[(index + step).mod(available.size)]
            }
        appearance.setIdentKit(bodyPart.part, next)
    }

    private fun Player.cycleColour(colour: DesignColour, step: Int) {
        val current = appearance.coloursSnapshot()[colour.slot].toInt() and 0xFF
        val next = (current + step).mod(colour.count)
        appearance.setColour(colour.slot, next)
    }

    private fun Player.selectBodyType(bodyType: Int) {
        if (appearance.bodyType == bodyType) {
            return
        }
        appearance.bodyType = bodyType
        bodyTypeVar = bodyType

        // Switching body type resets each body part to the first kit available for the new type.
        for (bodyPart in DesignBodyPart.entries) {
            val kit = availableKits(bodyType, bodyPart.part).firstOrNull() ?: -1
            appearance.setIdentKit(bodyPart.part, kit)
        }

        if (appearance.pronoun != PRONOUN_THEY_THEM) {
            val pronoun = if (bodyType == BODY_TYPE_A) PRONOUN_HE_HIM else PRONOUN_SHE_HER
            setPronoun(pronoun)
        }
    }

    private fun Player.selectPronoun(pronoun: Int) {
        if (pronoun !in PRONOUN_HE_HIM..PRONOUN_THEY_THEM) {
            return
        }
        setPronoun(pronoun)
    }

    private fun Player.setPronoun(pronoun: Int) {
        appearance.pronoun = pronoun
        pronounVar = pronoun
    }

    private fun Player.confirmDesign() {
        tutorial = TUTORIAL_COMPLETE
        ifClose(eventBus)
    }

    private fun availableKits(bodyType: Int, part: Int): List<Int> =
        identKits.selectable(part + bodyType * BODY_PARTS_PER_TYPE)

    private companion object {
        /** Dropdown option sub-components, created client-side starting at sub-id 1. */
        val PRONOUN_OPTIONS = 1..3
    }
}

private var Player.tutorial by intVarp(creator_varps.tutorial)
private var Player.bodyTypeVar by intVarBit(creator_varbits.body_type)
private var Player.pronounVar by intVarBit(creator_varbits.pronouns)

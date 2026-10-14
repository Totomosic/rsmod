package org.rsmod.content.other.ironman

import jakarta.inject.Inject
import org.rsmod.api.player.ironman.isIronman
import org.rsmod.api.player.ui.ifOpenMainModal
import org.rsmod.api.script.onIfClose
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.content.other.ironman.configs.ironman_interfaces
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * New accounts choose their Ironman mode right after designing their character, as on Tutorial
 * Island: closing the character creator on a fresh account opens the Ironman setup interface.
 */
class IronmanStartScript
@Inject
constructor(private val downgrades: IronmanDowngrades, private val eventBus: EventBus) :
    PluginScript() {
    override fun ScriptContext.startup() {
        // The setup modal cannot open while the creator's modals are still closing; wait a tick.
        onIfClose(ironman_start_interfaces.player_design) {
            player.softTimer(ironman_start_timers.offer, 1)
        }
        onPlayerSoftTimer(ironman_start_timers.offer) {
            player.clearSoftTimer(ironman_start_timers.offer)
            player.offerIronmanModes()
        }
    }

    private fun Player.offerIronmanModes() {
        if (pendingLogout || isIronman || !downgrades.isFreshAccount(this)) {
            return
        }
        refreshSetupVars(downgrades)
        ifOpenMainModal(ironman_interfaces.ironman_setup, eventBus)
    }
}

typealias ironman_start_interfaces = IronmanStartInterfaces

typealias ironman_start_timers = IronmanStartTimers

object IronmanStartTimers : TimerReferences() {
    val offer = find("ironman_offer")
}

object IronmanStartInterfaces : InterfaceReferences() {
    val player_design = find("player_design")
}

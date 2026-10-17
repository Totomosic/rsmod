package org.rsmod.api.specials.scripts

import kotlin.math.min
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.timers
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

public class SpecialEnergyScript : PluginScript() {
    private val lightbearers = mutableSetOf<Player>()
    private val Player.newAccount by boolVarBit(varbits.new_player_account)
    private var Player.specialAttackEnergy by intVarp(varps.sa_energy)

    override fun ScriptContext.startup() {
        onPlayerLogin { player.initRegenTimer() }
        onPlayerSoftTimer(timers.spec_regen) { player.specRegen() }
        onEvent<HeldEquipEvents.WearposChange> {
            if (wearpos == Wearpos.Ring) player.updateRegenTimer()
        }
        onPlayerLogout { lightbearers.remove(player) }
    }

    private fun Player.initRegenTimer() {
        if (newAccount) {
            specialAttackEnergy = constants.sa_max_energy
        }
        if (wearingLightbearer()) lightbearers.add(this) else lightbearers.remove(this)
        softTimer(timers.spec_regen, regenInterval())
    }

    @OptIn(org.rsmod.annotations.InternalApi::class)
    private fun Player.updateRegenTimer() {
        val equipped = wearingLightbearer()
        if (equipped == (this in lightbearers)) return
        if (equipped) lightbearers.add(this) else lightbearers.remove(this)
        val packed = softTimerMap[timers.spec_regen.id.toShort()]
        val remaining = packed?.let { softTimerMap.extractExpiry(it) - currentMapClock }
        val interval = regenInterval()
        // Equipping never postpones a restore already due within 25 ticks.
        if (equipped && remaining != null && remaining <= interval) return
        softTimer(timers.spec_regen, interval)
    }

    private fun Player.wearingLightbearer(): Boolean =
        worn[Wearpos.Ring.slot].isType(objs.lightbearer)

    private fun Player.regenInterval(): Int =
        if (wearingLightbearer()) constants.spec_regen_interval / 2
        else constants.spec_regen_interval

    private fun Player.specRegen() {
        val increased = min(constants.sa_max_energy, specialAttackEnergy + 100)
        if (increased > specialAttackEnergy) {
            specialAttackEnergy = increased
        }
        softTimer(timers.spec_regen, regenInterval())
    }
}

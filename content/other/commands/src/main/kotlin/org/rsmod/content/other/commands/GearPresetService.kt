package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.player.output.UpdateRun
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.statRestoreAll
import org.rsmod.api.player.vars.intVarp
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.stat.StatTypeList

class GearPresetService
@Inject
constructor(
    private val store: GearPresetStore,
    private val objects: ObjTypeList,
    private val combat: PlayerAttackManager,
    private val events: EventBus,
    private val statTypes: StatTypeList,
) {
    private var Player.specialAttackEnergy by intVarp(varps.sa_energy)

    fun applyDefault(player: Player) {
        val id = store.defaultSetupId(checkNotNull(player.uuid)) ?: return
        val preset = store.list().firstOrNull { it.id == id }
        if (preset == null) {
            player.mes(
                "Your default gear setup is no longer available. Choose another in Gear setups."
            )
            return
        }
        try {
            restore(player, preset)
            player.mes("Equipped default setup: ${preset.name}.")
        } catch (e: IllegalArgumentException) {
            player.mes("Could not apply your default setup: ${e.message}")
        }
    }

    fun restore(player: Player, preset: GearPreset) {
        // Validate the complete snapshot before touching either live container.
        val worn = validate(preset.equipment, player.worn.size)
        val inventory = validate(preset.inventory, player.inv.size)
        for ((slot, item) in worn.withIndex()) {
            if (item == null) continue
            val type = objects[item]
            val wearpos = Wearpos[slot]
            require(wearpos != null && !wearpos.isClientOnly && type.wearpos1 == slot) {
                "This setup contains invalid equipment."
            }
            for (blocked in listOf(type.wearpos2, type.wearpos3)) {
                require(worn.getOrNull(blocked) == null) {
                    "This setup contains conflicting equipment."
                }
            }
        }
        val previous = player.worn.toList()
        combat.stopCombat(player)
        player.clearInteractionRoute()
        for (slot in player.inv.indices) player.inv[slot] = inventory[slot]
        for (slot in player.worn.indices) player.worn[slot] = worn[slot]
        for (slot in player.worn.indices) {
            if (previous[slot] == worn[slot]) continue
            val wearpos = Wearpos[slot] ?: continue
            val item = worn[slot] ?: previous[slot] ?: continue
            events.publish(HeldEquipEvents.WearposChange(player, wearpos, objects[item]))
        }
        player.rebuildAppearance()
        player.statRestoreAll(statTypes.values)
        player.specialAttackEnergy = constants.sa_max_energy
        player.runEnergy = constants.run_max_energy
        UpdateRun.energy(player, player.runEnergy)
    }

    private fun validate(items: List<GearPresetItem?>, size: Int): List<InvObj?> {
        require(items.size == size) { "This setup has an incompatible container size." }
        return items.map { item ->
            if (item == null) return@map null
            val type = objects[item.id]
            require(type != null && item.count > 0 && (type.isStackable || item.count == 1)) {
                "This setup contains an invalid item or quantity."
            }
            InvObj(type, item.count, item.vars)
        }
    }
}

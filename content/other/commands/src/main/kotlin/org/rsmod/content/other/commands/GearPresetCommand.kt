package org.rsmod.content.other.commands

import jakarta.inject.Inject
import java.util.HexFormat
import java.util.UUID
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.UpdateRun
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statRestoreAll
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onCommand as registerCommand
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class GearPresetCommand
@Inject
constructor(
    private val store: GearPresetStore,
    private val objects: ObjTypeList,
    private val combat: PlayerAttackManager,
    private val events: EventBus,
    private val statTypes: StatTypeList,
) : PluginScript() {
    private var Player.specialAttackEnergy by intVarp(varps.sa_energy)

    override fun ScriptContext.startup() {
        registerCommand("gearsetups") {
            desc = "Save, list, or restore development equipment and inventory setups"
            cheat {
                val request = args.getOrNull(1) ?: return@cheat
                if (!request.matches(Regex("[0-9a-f]{8}"))) return@cheat
                try {
                    val character = checkNotNull(player.uuid)
                    when (args.firstOrNull()) {
                        "list" -> {
                            require(args.size == 2) { "Invalid setup request." }
                            player.sendList(
                                request,
                                store.list(),
                                "Choose a setup or save your current gear.",
                            )
                        }
                        "save" -> {
                            require(args.size == 3 && args[2].length <= 48) {
                                "Invalid setup name."
                            }
                            val name =
                                String(HexFormat.of().parseHex(args[2]), Charsets.UTF_8).trim()
                            val preset =
                                store.save(
                                    character,
                                    player.displayName,
                                    name,
                                    player.worn.snapshot(),
                                    player.inv.snapshot(),
                                )
                            player.sendList(request, store.list(), "Saved ${preset.name}.")
                        }
                        "load" -> {
                            require(args.size == 3) { "Choose a saved setup." }
                            val id = UUID.fromString(args[2]).toString()
                            val preset = store.list().firstOrNull { it.id == id }
                            requireNotNull(preset) {
                                "That setup is no longer available. Refresh the list."
                            }
                            require(!player.isDelayed && player.hitpoints > 0) {
                                "Finish your current action before applying a setup."
                            }
                            restore(player, preset)
                            player.reply(request, "ok", "Equipped ${preset.name}.")
                        }
                        else -> player.reply(request, "error", "Unknown setup action.")
                    }
                } catch (e: IllegalArgumentException) {
                    player.reply(request, "error", e.message ?: "Invalid gear setup.")
                } catch (e: Exception) {
                    player.reply(request, "error", "Could not complete the setup request.")
                    throw e
                }
            }
        }
    }

    private fun restore(player: Player, preset: GearPreset) {
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

    private fun Inventory.snapshot(): List<GearPresetItem?> = map {
        it?.let { GearPresetItem(it.id, it.count, it.vars) }
    }

    private fun Player.sendList(request: String, setups: List<GearPreset>, message: String) {
        mes("LGP1 $request begin", ChatType.Console)
        for (preset in setups) mes(
            "LGP1 $request item ${preset.id} ${hex(preset.name)}",
            ChatType.Console,
        )
        reply(request, "end", message)
    }

    private fun Player.reply(request: String, action: String, message: String) =
        mes("LGP1 $request $action ${hex(message)}", ChatType.Console)

    private fun hex(text: String): String =
        HexFormat.of().formatHex(text.toByteArray(Charsets.UTF_8))
}

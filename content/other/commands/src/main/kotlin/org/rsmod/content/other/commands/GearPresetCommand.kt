package org.rsmod.content.other.commands

import jakarta.inject.Inject
import java.util.HexFormat
import java.util.UUID
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.script.onCommand as registerCommand
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.Inventory
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class GearPresetCommand
@Inject
constructor(private val store: GearPresetStore, private val gear: GearPresetService) :
    PluginScript() {
    override fun ScriptContext.startup() {
        registerCommand("gearsetups") {
            desc = "Save, list, restore, or choose default equipment and inventory setups"
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
                        "default" -> {
                            require(args.size == 3) { "Choose a default setup or none." }
                            val id =
                                if (args[2] == "none") null else UUID.fromString(args[2]).toString()
                            store.setDefault(character, id)
                            player.sendList(
                                request,
                                store.list(),
                                if (id == null) "Default setup cleared."
                                else
                                    "Default setup saved. It will apply when creating or joining a raid.",
                            )
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
                            gear.restore(player, preset)
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

    private fun Inventory.snapshot(): List<GearPresetItem?> = map {
        it?.let { GearPresetItem(it.id, it.count, it.vars) }
    }

    private fun Player.sendList(request: String, setups: List<GearPreset>, message: String) {
        mes("LGP1 $request begin", ChatType.Console)
        val defaultId = store.defaultSetupId(checkNotNull(uuid)) ?: "none"
        mes("LGP1 $request default $defaultId", ChatType.Console)
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

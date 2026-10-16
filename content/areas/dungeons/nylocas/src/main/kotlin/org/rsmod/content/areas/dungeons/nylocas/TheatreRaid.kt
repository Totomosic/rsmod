package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.config.refs.components
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.region.Region
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.square.MapSquareKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

@Singleton
class TheatreRaid
@Inject
constructor(
    private val regions: RegionRegistry,
    private val players: PlayerList,
    private val eventBus: EventBus,
) : PluginScript() {
    private val raids = mutableMapOf<Int, Raid>()

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.EndCycle> { process() }
        onPlayerLogin { clear(player) }
        onPlayerLogout { leave(player) }
    }

    fun start(player: Player) {
        // Clear an old room's state before starting again after a development teleport.
        process()
        val region = regions[player.coords]
        val source = regions.normalizeCoords(player.coords)
        if (region == null || MapSquareKey.from(source).id !in setOf(13122, 13125)) {
            player.mes("Teleport to the Bloat exit or Nylocas bridge before starting the raid.")
            return
        }
        val existing = raids[region.uid]
        if (existing != null) {
            player.mes("This raid has already started. Use a fresh instance for a new raid.")
            return
        }
        val members = players.filter { regions[it.coords]?.uid == region.uid }.toMutableList()
        if (members.size > PARTY_SIZE) {
            player.mes("The Theatre of Blood supports up to five players per raid.")
            return
        }
        val raid = Raid(region, members)
        raids[region.uid] = raid
        sync(raid)
        player.mes("Raid started. Pass the Nylocas barrier to begin the first wave.")
    }

    fun isStarted(player: Player, region: Region): Boolean =
        raids[region.uid]?.members?.contains(player) == true

    fun teamSize(region: Region): Int =
        checkNotNull(raids[region.uid]) { "Raid has not started: ${region.uid}" }.teamSize

    fun beginRoom(region: Region) {
        val raid = raids[region.uid] ?: return
        // ToB QoL observes room status 1 during the countdown before the first wave.
        raid.progressType = 1
        sync(raid)
    }

    fun waveSpawned(region: Region, wave: Int = 1) {
        val raid = raids[region.uid] ?: return
        raid.progressType = 2
        raid.progressValue = wave
        sync(raid)
    }

    private fun process() {
        val iterator = raids.values.iterator()
        while (iterator.hasNext()) {
            val raid = iterator.next()
            val departed =
                raid.members.filter {
                    players[it.slotId] !== it || regions[it.coords]?.uid != raid.region.uid
                }
            for (member in departed) {
                clear(member)
                raid.members.remove(member)
            }
            if (raid.region.slot == Region.INVALID_SLOT) {
                raid.members.forEach(::clear)
                iterator.remove()
            } else {
                sync(raid)
            }
        }
    }

    private fun leave(player: Player) {
        for (raid in raids.values) {
            if (raid.members.remove(player)) {
                sync(raid)
            }
        }
        clear(player)
    }

    private fun sync(raid: Raid) {
        val names = raid.members.map { it.displayName.ifBlank { it.username } }
        val namesChanged = raid.names != names
        raid.names = names
        for ((slot, viewer) in raid.members.withIndex()) {
            if (namesChanged) {
                // Native script 2301 sets party varc strings 330..334 for the HUD and plugins.
                viewer.runClientScript(2301, List(PARTY_SIZE) { names.getOrElse(it) { "" } })
            }
            viewer.set(tob_varbits.party_status, 2)
            viewer.set(tob_varbits.party_slot, slot + 1)
            for (index in 0 until PARTY_SIZE) {
                val member = raid.members.getOrNull(index)
                val hp = if (member == null) 0 else orbHealth(member)
                viewer.set(tob_varbits.party_hp[index], hp)
            }
            viewer.set(tob_varbits.progress_type, raid.progressType)
            viewer.set(tob_varbits.progress_value, raid.progressValue)
            viewer.set(tob_varbits.progress_max, 31)
            if (!viewer.ui.containsOverlay(tob_interfaces.hud)) {
                viewer.ifOpenOverlay(
                    tob_interfaces.hud,
                    components.toplevel_target_overlay_hud,
                    eventBus,
                )
            }
        }
    }

    private fun clear(player: Player) {
        player.set(tob_varbits.party_status, 0)
        player.set(tob_varbits.party_slot, 0)
        tob_varbits.party_hp.forEach { player.set(it, 0) }
        player.set(tob_varbits.progress_type, 0)
        player.set(tob_varbits.progress_value, 0)
        player.set(tob_varbits.progress_max, 0)
        player.runClientScript(2301, List(PARTY_SIZE) { "" })
        player.ifCloseOverlay(tob_interfaces.hud, eventBus)
    }

    private fun Player.set(type: VarBitType, value: Int) {
        if (vars[type] != value) {
            VarPlayerIntMapSetter.set(this, type, value)
        }
    }

    private fun orbHealth(player: Player): Int {
        val base = player.baseHitpointsLvl.coerceAtLeast(1)
        val hp = player.hitpoints.coerceIn(0, base)
        // The native HUD hides 0, treats 1 as zero HP, and renders 1..27 as 0..26 pixels.
        return 1 + (hp * 26 + base - 1) / base
    }

    private class Raid(
        val region: Region,
        val members: MutableList<Player>,
        var names: List<String>? = null,
        var progressType: Int = 0,
        var progressValue: Int = 0,
    ) {
        // Keep encounter scaling stable if a member disconnects or leaves the instance.
        val teamSize: Int = members.size
    }

    private companion object {
        const val PARTY_SIZE = 5
    }
}

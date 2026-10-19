package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.ui.ifClose
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.map.Direction
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NylocasEncounter
@Inject
constructor(
    private val regions: RegionRegistry,
    private val npcRepo: NpcRepository,
    private val npcTypes: NpcTypeList,
    private val npcs: NpcList,
    private val clock: MapClock,
    private val raid: TheatreRaid,
    private val waves: NylocasWaveTable,
    private val behaviour: NylocasBehaviour,
    private val boss: NylocasBoss,
    private val eventBus: EventBus,
    private val report: NylocasWaveReport,
) : PluginScript() {
    private val encounters = mutableMapOf<Int, Encounter>()
    private val pendingEntries = mutableMapOf<Player, PendingEntry>()

    override fun ScriptContext.startup() {
        onOpLoc1(nylocas_locs.barrier) { pass(it.loc) }
        onPlayerQueueWithArgs<PendingEntry>(nylocas_queues.enter) {
            val entry = it.args
            if (player.coords == entry.origin && raid.isStarted(player, entry.region)) {
                teleport(entry.destination)
                delay(1)
            }
        }
        onEvent<GameLifecycle.StartCycle> {
            behaviour.processLifetimes()
            boss.process()
        }
        // A corpse removed by this tick's NPC processing frees its cap slot before admission.
        onEvent<GameLifecycle.NpcsProcessed> { processEncounters() }
        onEvent<GameLifecycle.EndCycle> { checkCleanup() }
        behaviour.register(this)
        boss.register(this)
        report.register(this)
    }

    private suspend fun ProtectedAccess.pass(barrier: BoundLocInfo) {
        val region = regions[barrier.coords] ?: return
        val source = regions.normalizeCoords(barrier.coords)
        // This barrier type is shared by other Theatre rooms. Only handle the Nylocas entrance.
        if (source.level != 0 || source.z != 4255 || source.x !in 3295..3296) {
            return
        }
        val entering = player.coords.z > barrier.coords.z
        if (entering && !raid.isStarted(player, region)) {
            mes("Click Start Raid before passing the Nylocas barrier.")
            return
        }
        val destination = region.normal[source.translateZ(if (entering) -1 else 1)]
        if (entering && region.uid !in encounters && !confirmEntry(region, destination)) {
            return
        }
        // Nearby teleports interpolate as walking while bypassing the barrier's collision.
        teleport(destination)
        if (entering) {
            encounters.getOrPut(region.uid) {
                raid.beginRoom(region)
                // The first crossing is room tick zero, independent of time spent outside.
                Encounter(
                    region,
                    waveCycle = clock + waves.waves.first().ticksAfterPrevious,
                    teamSize = raid.teamSize(region),
                    waveIndex = raid.options(region).startingWave - 1,
                    startCycle = clock.cycle,
                )
            }
            enterWaitingPlayers(region)
        }
        delay(1)
    }

    private suspend fun ProtectedAccess.confirmEntry(
        region: Region,
        destination: CoordGrid,
    ): Boolean {
        val entry = PendingEntry(region, player.coords, destination)
        pendingEntries[player] = entry
        val confirmed =
            try {
                choice2("Enter", true, "Cancel", false, title = "Start the Nylocas encounter?")
            } finally {
                // Walking, closing the dialogue, and logging out cancel the suspended script.
                pendingEntries.remove(player, entry)
            }
        return confirmed && player.coords == entry.origin && raid.isStarted(player, region)
    }

    private fun enterWaitingPlayers(region: Region) {
        // Closing each dialogue removes its entry, so iterate over a snapshot.
        val waiting = pendingEntries.filterValues { it.region.uid == region.uid }
        for ((player, entry) in waiting) {
            if (player.coords != entry.origin || !raid.isStarted(player, region)) continue
            player.ifClose(eventBus)
            // Run after every player's input has updated their clock and previous coordinates.
            // A one-cycle queue runs in this tick's player processing phase.
            player.queue(nylocas_queues.enter, cycles = 1, args = entry)
        }
    }

    private fun processEncounters() {
        val iterator = encounters.values.iterator()
        while (iterator.hasNext()) {
            val encounter = iterator.next()
            if (encounter.region.slot == Region.INVALID_SLOT) {
                iterator.remove()
                continue
            }
            val bossCycle = encounter.bossCycle
            if (bossCycle != null && clock >= bossCycle && !encounter.bossSpawned) {
                boss.spawn(encounter.region, encounter.teamSize, encounter.startCycle)
                encounter.bossSpawned = true
            }
            if (
                encounter.waveIndex >= waves.waves.size ||
                    clock < encounter.waveCycle ||
                    regions.isEmpty(encounter.region)
            ) {
                continue
            }
            // waveIndex is the next wave's zero-based index, including skipped waves.
            // Wave 20 uses the old cap; starting at wave 21 already uses the raised cap.
            // Count bodies through removal, regardless of HP.
            val cap = if (encounter.waveIndex < 20) 12 else 24
            val population =
                npcs.count { npc ->
                    npc.id in 8342..8353 && regions[npc.coords]?.uid == encounter.region.uid
                }
            report.admission(encounter.region, encounter.waveIndex + 1, population, cap)
            if (population >= cap) {
                encounter.waveCycle = clock + 4
                continue
            }
            // Admission checks the existing population once, so a whole batch may
            // overshoot the cap. A stall never advances the wave or its HUD value.
            for ((slot, spawn) in waves.waves[encounter.waveIndex].spawns) {
                spawn(encounter, slot, spawn)
            }
            encounter.waveIndex++
            raid.waveSpawned(encounter.region, encounter.waveIndex)
            report.waveSpawned(encounter.region, encounter.waveIndex)
            waves.waves.getOrNull(encounter.waveIndex)?.let {
                encounter.waveCycle = clock + it.ticksAfterPrevious
            }
        }
    }

    private fun checkCleanup() {
        for (encounter in encounters.values) {
            if (encounter.waveIndex < 31 || encounter.bossCycle != null) continue
            if (encounter.region.slot == Region.INVALID_SLOT) continue
            // Dying bigs still split; cleanup starts only after the final body's removal.
            if (npcs.any { it.id in 8342..8353 && regions[it.coords]?.uid == encounter.region.uid })
                continue
            val phase = Math.floorMod(encounter.startCycle, 4)
            // EndCycle follows MapClock.tick(); deaths ran on the preceding cycle value.
            val cleanupCycle = clock.cycle - 1
            encounter.bossCycle = cleanupCycle + 16 + Math.floorMod(phase - cleanupCycle, 4)
        }
    }

    private fun spawn(encounter: Encounter, slot: NylocasSlot, spawn: NylocasSpawn) {
        val type = nylocas_npcs.incoming(spawn.size, spawn.style)
        val npc = Npc(npcTypes[type], encounter.region.normal[spawn.source(slot)])
        npc.respawnDir =
            when (slot) {
                NylocasSlot.WestNorth,
                NylocasSlot.WestSouth -> Direction.East
                NylocasSlot.SouthWest,
                NylocasSlot.SouthEast -> Direction.North
                NylocasSlot.EastNorth,
                NylocasSlot.EastSouth -> Direction.West
            }
        npc.baseHitpointsLvl = spawn.size.hitpoints(encounter.teamSize)
        npc.hitpoints = npc.baseHitpointsLvl
        npc.mode = NpcMode.None
        npc.setHunt(0)
        npc.aiTimer = 0
        behaviour.add(npc, encounter.region, slot, spawn, encounter.teamSize)
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
    }

    private class PendingEntry(
        val region: Region,
        val origin: CoordGrid,
        val destination: CoordGrid,
    )

    private class Encounter(
        val region: Region,
        var waveCycle: Int,
        val teamSize: Int,
        var waveIndex: Int = 0,
        val startCycle: Int,
        var bossCycle: Int? = null,
        var bossSpawned: Boolean = false,
    )
}

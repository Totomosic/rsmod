package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NylocasEncounter
@Inject
constructor(
    private val regions: RegionRegistry,
    private val npcRepo: NpcRepository,
    private val npcTypes: NpcTypeList,
    private val clock: MapClock,
    private val raid: TheatreRaid,
    private val waves: NylocasWaveTable,
    private val behaviour: NylocasBehaviour,
) : PluginScript() {
    private val encounters = mutableMapOf<Int, Encounter>()

    override fun ScriptContext.startup() {
        onOpLoc1(nylocas_locs.barrier) { pass(it.loc) }
        onEvent<GameLifecycle.StartCycle> { processEncounters() }
        behaviour.register(this)
    }

    private fun ProtectedAccess.pass(barrier: BoundLocInfo) {
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
        telejump(region.normal[source.translateZ(if (entering) -1 else 1)])
        if (entering) {
            encounters.getOrPut(region.uid) {
                raid.beginRoom(region)
                Encounter(
                    region,
                    waveCycle = clock + waves.waves.first().ticksAfterPrevious,
                    teamSize = raid.teamSize(region),
                )
            }
        }
    }

    private fun processEncounters() {
        behaviour.prune()
        val iterator = encounters.values.iterator()
        while (iterator.hasNext()) {
            val encounter = iterator.next()
            if (encounter.region.slot == Region.INVALID_SLOT) {
                iterator.remove()
                continue
            }
            if (
                encounter.waveIndex >= waves.waves.size ||
                    clock < encounter.waveCycle ||
                    regions.isEmpty(encounter.region)
            ) {
                continue
            }
            for ((slot, spawn) in waves.waves[encounter.waveIndex].spawns) {
                spawn(encounter, slot, spawn)
            }
            encounter.waveIndex++
            raid.waveSpawned(encounter.region, encounter.waveIndex)
            waves.waves.getOrNull(encounter.waveIndex)?.let {
                encounter.waveCycle = clock + it.ticksAfterPrevious
            }
        }
    }

    private fun spawn(encounter: Encounter, slot: NylocasSlot, spawn: NylocasSpawn) {
        val type = nylocas_npcs.incoming(spawn.size, spawn.style)
        val npc = Npc(npcTypes[type], encounter.region.normal[slot.source])
        npc.baseHitpointsLvl = spawn.size.hitpoints(encounter.teamSize)
        npc.hitpoints = npc.baseHitpointsLvl
        npc.mode = NpcMode.None
        npc.setHunt(0)
        npc.aiTimer = 0
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        behaviour.add(npc, encounter.region, slot, spawn)
    }

    private class Encounter(
        val region: Region,
        var waveCycle: Int,
        val teamSize: Int,
        var waveIndex: Int = 0,
    )
}

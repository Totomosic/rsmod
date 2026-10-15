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
import org.rsmod.game.type.npc.NpcType
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
    private val clock: MapClock,
    private val raid: TheatreRaid,
) : PluginScript() {
    private val encounters = mutableMapOf<Int, Encounter>()

    override fun ScriptContext.startup() {
        onOpLoc1(nylocas_locs.barrier) { pass(it.loc) }
        onEvent<GameLifecycle.StartCycle> { processEncounters() }
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
                val smallHitpoints =
                    when (val size = raid.teamSize(region)) {
                        1,
                        2,
                        3 -> 8
                        4 -> 9
                        5 -> 11
                        else -> error("Invalid raid team size: $size")
                    }
                Encounter(region, waveCycle = clock + 4, smallHitpoints = smallHitpoints)
            }
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
            if (
                encounter.spawned ||
                    clock < encounter.waveCycle ||
                    regions.isEmpty(encounter.region)
            ) {
                continue
            }
            spawn(encounter, nylocas_npcs.small_magic, CoordGrid(3295, 4233, 0))
            spawn(encounter, nylocas_npcs.small_ranged, CoordGrid(3281, 4249, 0))
            spawn(encounter, nylocas_npcs.small_melee, CoordGrid(3310, 4248, 0))
            encounter.spawned = true
            raid.waveSpawned(encounter.region)
        }
    }

    private fun spawn(encounter: Encounter, type: NpcType, source: CoordGrid) {
        val npc = Npc(npcTypes[type], encounter.region.normal[source])
        npc.baseHitpointsLvl = encounter.smallHitpoints
        npc.hitpoints = encounter.smallHitpoints
        // Movement and combat are a later milestone; retain official incoming IDs for plugins.
        npc.mode = NpcMode.None
        npc.setHunt(0)
        npc.aiTimer = 0
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
    }

    private class Encounter(
        val region: Region,
        val waveCycle: Int,
        val smallHitpoints: Int,
        var spawned: Boolean = false,
    )
}

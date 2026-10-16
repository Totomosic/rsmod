package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.queues
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onNpcHit
import org.rsmod.api.type.editors.npc.NpcEditor
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.square.MapSquareKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

internal object combat_dummy_npcs : NpcReferences() {
    val dummy = find("poh_combat_dummy_npc")
}

internal object CombatDummyNpcEdits : NpcEditor() {
    init {
        edit(combat_dummy_npcs.dummy) {
            hitpoints = 65535
            defaultMode = NpcMode.None
            moveRestrict = MoveRestrict.NoMove
            wanderRange = 0
            giveChase = false
            param[params.poison_immunity] = 1
            param[params.venom_immunity] = 1
            param[params.burn_immunity] = 1
        }
    }
}

class NylocasCombatDummy
@Inject
constructor(
    private val regions: RegionRegistry,
    private val players: PlayerList,
    private val npcTypes: NpcTypeList,
    private val npcs: NpcRepository,
) : PluginScript() {
    private val dummies = mutableMapOf<Int, Pair<Region, Npc>>()

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> { populate() }
        onNpcHit(combat_dummy_npcs.dummy) {
            // Heal each impact separately, including multiple splats in one cycle.
            npc.hitpoints = npc.baseHitpointsLvl
            npc.queueList.removeAll(queues.death)
        }
    }

    private fun populate() {
        dummies.entries.removeIf { (_, spawn) ->
            val (region, npc) = spawn
            if (region.slot == Region.INVALID_SLOT) {
                if (npc.isSlotAssigned) npcs.del(npc, Int.MAX_VALUE)
                true
            } else {
                !npc.isSlotAssigned
            }
        }
        for (player in players) {
            val source = regions.normalizeCoords(player.coords)
            if (source.level != 0 || MapSquareKey.from(source).id != 13122) continue
            val region = regions[player.coords] ?: continue
            if (region.uid in dummies) continue
            val npc = Npc(npcTypes[combat_dummy_npcs.dummy], region.normal[NylocasRoom.combatDummy])
            npcs.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            npc.setHunt(0)
            npc.aiTimer(0)
            dummies[region.uid] = region to npc
        }
    }
}

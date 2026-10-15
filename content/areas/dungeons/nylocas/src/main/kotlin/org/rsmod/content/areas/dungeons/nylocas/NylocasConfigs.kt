package org.rsmod.content.areas.dungeons.nylocas

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences

object nylocas_locs : LocReferences() {
    val barrier = find("tob_arena_barrier")
    val passage = find("tob_dungeon_walkway_exit_clickbox")
}

object nylocas_npcs : NpcReferences() {
    val small_magic = find("tob_nylocas_incoming_magic")
    val small_ranged = find("tob_nylocas_incoming_ranged")
    val small_melee = find("tob_nylocas_incoming_melee")
}

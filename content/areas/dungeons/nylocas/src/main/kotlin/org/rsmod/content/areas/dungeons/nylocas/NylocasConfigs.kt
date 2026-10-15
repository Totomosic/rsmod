package org.rsmod.content.areas.dungeons.nylocas

import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences

object nylocas_locs : LocReferences() {
    val barrier = find("tob_arena_barrier")
    val passage = find("tob_dungeon_walkway_exit_clickbox")
}

object nylocas_npcs : NpcReferences() {
    val small_magic = find("tob_nylocas_incoming_magic")
    val small_ranged = find("tob_nylocas_incoming_ranged")
    val small_melee = find("tob_nylocas_incoming_melee")
}

object tob_interfaces : InterfaceReferences() {
    val hud = find("tob_hud")
}

object tob_varbits : VarBitReferences() {
    val party_status = find("tob_client_partystatus")
    val party_slot = find("tob_client_partyslot")
    val party_hp =
        listOf(
            find("tob_client_p0"),
            find("tob_client_p1"),
            find("tob_client_p2"),
            find("tob_client_p3"),
            find("tob_client_p4"),
        )
    val progress_type = find("tob_client_waveprogress_type")
    val progress_value = find("tob_client_waveprogress_val")
    val progress_max = find("tob_client_waveprogress_max")
}

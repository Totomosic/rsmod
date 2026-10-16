package org.rsmod.content.areas.dungeons.nylocas

import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences

object nylocas_locs : LocReferences() {
    val barrier = find("tob_arena_barrier")
    val passage = find("tob_dungeon_walkway_exit_clickbox")
}

object nylocas_npcs : NpcReferences() {
    val small_magic = find("tob_nylocas_incoming_magic")
    val small_ranged = find("tob_nylocas_incoming_ranged")
    val small_melee = find("tob_nylocas_incoming_melee")
    val big_magic = find("tob_nylocas_big_incoming_magic")
    val big_ranged = find("tob_nylocas_big_incoming_ranged")
    val big_melee = find("tob_nylocas_big_incoming_melee")
    val small_fighting_magic = find("tob_nylocas_fighting_magic")
    val small_fighting_ranged = find("tob_nylocas_fighting_ranged")
    val small_fighting_melee = find("tob_nylocas_fighting_melee")
    val big_fighting_magic = find("tob_nylocas_big_fighting_magic")
    val big_fighting_ranged = find("tob_nylocas_big_fighting_ranged")
    val big_fighting_melee = find("tob_nylocas_big_fighting_melee")

    fun incoming(size: NylocasSize, style: NylocasStyle) =
        when (style) {
            NylocasStyle.Melee -> if (size == NylocasSize.Small) small_melee else big_melee
            NylocasStyle.Ranged -> if (size == NylocasSize.Small) small_ranged else big_ranged
            NylocasStyle.Magic -> if (size == NylocasSize.Small) small_magic else big_magic
        }

    fun fighting(size: NylocasSize, style: NylocasStyle) =
        when (style) {
            NylocasStyle.Melee ->
                if (size == NylocasSize.Small) small_fighting_melee else big_fighting_melee
            NylocasStyle.Ranged ->
                if (size == NylocasSize.Small) small_fighting_ranged else big_fighting_ranged
            NylocasStyle.Magic ->
                if (size == NylocasSize.Small) small_fighting_magic else big_fighting_magic
        }
}

object nylocas_seqs : SeqReferences() {
    val magic_death = find("top_spider_magic_death")
    val ranged_death = find("top_spider_ranged_death")
    val melee_death = find("top_spider_melee_death")
    val magic_detonate = find("top_spider_magic_death_detonate")
    val ranged_detonate = find("top_spider_ranged_death_detonate")
    val melee_detonate = find("top_spider_melee_death_detonate")

    fun expiry(size: NylocasSize, style: NylocasStyle) =
        when (style) {
            NylocasStyle.Melee -> if (size == NylocasSize.Small) melee_death else melee_detonate
            NylocasStyle.Ranged -> if (size == NylocasSize.Small) ranged_death else ranged_detonate
            NylocasStyle.Magic -> if (size == NylocasSize.Small) magic_death else magic_detonate
        }
}

object nylocas_spots : SpotanimReferences() {
    val melee_detonate = find("tob_nylocas_death_melee_detonate")
    val ranged_detonate = find("tob_nylocas_death_ranged_detonate")
    val magic_detonate = find("tob_nylocas_death_magic_detonate")

    fun explosion(style: NylocasStyle) =
        when (style) {
            NylocasStyle.Melee -> melee_detonate
            NylocasStyle.Ranged -> ranged_detonate
            NylocasStyle.Magic -> magic_detonate
        }
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

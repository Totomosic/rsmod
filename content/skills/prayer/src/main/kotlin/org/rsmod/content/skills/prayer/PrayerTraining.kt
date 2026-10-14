package org.rsmod.content.skills.prayer

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Prayer training and altars, following the OSRS Wiki:
 * - Bones are buried and demonic ashes scattered for the experience listed in [BONES_XP] and
 *   [ASHES_XP].
 * - "Pray-at" on an altar recharges Prayer points to the base level.
 * - The Chaos Temple altar in level 38 Wilderness grants 350% experience per bone offered, with a
 *   50% chance for the bone not to be consumed ([Chaos
 *   Temple](https://oldschool.runescape.wiki/w/Chaos_Temple_(church))).
 */
class PrayerTraining
@Inject
constructor(private val objTypes: ObjTypeList, private val locTypes: LocTypeList) : PluginScript() {
    private val bones = mutableMapOf<Int, Double>()

    override fun ScriptContext.startup() {
        for (type in objTypes.values) {
            BONES_XP[type.internalName]?.let { xp ->
                bones[type.id] = xp
                onOpHeld1(type) { bury(it.slot, type, xp) }
            }
            ASHES_XP[type.internalName]?.let { xp ->
                onOpHeld1(type) { scatter(it.slot, type, xp) }
            }
        }
        for (type in locTypes.values) {
            val op1 = type.op.getOrNull(0) ?: continue
            if (op1 == "Pray-at" || op1 == "Pray") {
                onOpLoc1(type) { prayAtAltar() }
            }
        }
        onOpLocU(prayer_locs.chaos_altar) { offerBones(it.loc, it.objType.id, it.invSlot) }
    }

    private suspend fun ProtectedAccess.bury(slot: Int, type: ObjType, xp: Double) {
        if (player.buryDelay > mapClock) {
            return
        }
        if (player.inv[slot]?.id != type.id) {
            return
        }
        player.buryDelay = mapClock + BURY_DELAY
        mes("You dig a hole in the ground...")
        anim(prayer_seqs.bury)
        invDel(player.inv, type, count = 1, slot = slot)
        statAdvance(stats.prayer, xp)
        delay(1)
        mes("You bury the bones.")
    }

    private suspend fun ProtectedAccess.scatter(slot: Int, type: ObjType, xp: Double) {
        if (player.buryDelay > mapClock) {
            return
        }
        if (player.inv[slot]?.id != type.id) {
            return
        }
        player.buryDelay = mapClock + BURY_DELAY
        anim(prayer_seqs.scatter)
        invDel(player.inv, type, count = 1, slot = slot)
        statAdvance(stats.prayer, xp)
        mes("You scatter the ashes.")
    }

    private suspend fun ProtectedAccess.prayAtAltar() {
        if (player.stat(stats.prayer) >= player.statBase(stats.prayer)) {
            mes("You already have full Prayer points.")
            return
        }
        anim(prayer_seqs.pray)
        delay(2)
        player.statHeal(stats.prayer, constant = player.statBase(stats.prayer), percent = 0)
        mes("You recharge your Prayer points.")
    }

    private suspend fun ProtectedAccess.offerBones(loc: BoundLocInfo, obj: Int, slot: Int) {
        val xp = bones[obj]
        // The chaos altar loc is used in several places, but only the one in the Wilderness Chaos
        // Temple accepts bone offerings.
        if (xp == null || !loc.coords.inWildernessChaosTemple()) {
            mes("You fear the wrath of the gods!")
            return
        }
        faceSquare(loc.coords)
        anim(prayer_seqs.offer)
        delay(1)
        if (player.inv[slot]?.id != obj) {
            return
        }
        statAdvance(stats.prayer, xp * CHAOS_ALTAR_MULTIPLIER)
        if (random.of(1, 2) == 1) {
            mes("The Dark Lord spares your sacrifice but still rewards you for your efforts.")
        } else {
            invDel(player.inv, objTypes[obj]!!, count = 1, slot = slot)
            mes("The gods are pleased with your offering.")
        }
    }

    private fun org.rsmod.map.CoordGrid.inWildernessChaosTemple(): Boolean =
        level == 0 && x in 2944..2958 && z in 3816..3826

    private companion object {
        const val BURY_DELAY = 2
        const val CHAOS_ALTAR_MULTIPLIER = 3.5
    }
}

private var Player.buryDelay by intVarp(prayer_varps.bury_delay)

object prayer_locs : LocReferences() {
    val chaos_altar = find("chaosaltar")
}

internal object prayer_seqs : SeqReferences() {
    val bury = find("human_pickupfloor")
    val scatter = find("farming_ingredient_sprinkle")
    val pray = find("human_pray")
    val offer = find("human_bone_sacrifice")
}

internal object prayer_varps : VarpReferences() {
    /** Map clock until which another bone cannot be buried (or ashes scattered). */
    val bury_delay = find("prayer_bury_delay")
}

internal object PrayerVarpBuilder : VarpBuilder() {
    init {
        build("prayer_bury_delay") { temporary = true }
    }
}

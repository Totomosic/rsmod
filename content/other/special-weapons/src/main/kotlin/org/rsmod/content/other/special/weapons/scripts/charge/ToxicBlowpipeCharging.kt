package org.rsmod.content.other.special.weapons.scripts.charge

import jakarta.inject.Inject
import org.rsmod.api.config.refs.objs
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.worn.Blowpipe
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpWorn2
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class ToxicBlowpipeCharging @Inject constructor(private val objRepo: ObjRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        for (pipe in
            listOf(
                objs.toxic_blowpipe,
                objs.toxic_blowpipe_uncharged,
                objs.blazing_blowpipe,
                objs.blazing_blowpipe_uncharged,
            )) {
            if (pipe == objs.toxic_blowpipe_uncharged || pipe == objs.blazing_blowpipe_uncharged) {
                onOpHeld2(pipe) { checkCharges(it.inventory[it.slot]) }
                onOpHeld3(pipe) {
                    mes("Use Zulrah's scales and unpoisoned darts on your blowpipe to load it.")
                }
            } else {
                onOpHeld3(pipe) { checkCharges(it.inventory[it.slot]) }
            }
            onOpHeld4(pipe) { unload(it.inventory, it.slot) }
            onOpWorn2(pipe) { checkCharges(player.righthand) }
            for (material in Blowpipe.dartTypes + objs.zulrahs_scales) {
                onOpHeldU(pipe, material) { load(inv, it.firstSlot, it.secondSlot, material) }
            }
        }
    }

    private fun ProtectedAccess.load(
        inventory: Inventory,
        slot: Int,
        materialSlot: Int,
        material: ObjType,
    ) {
        val obj = inventory[slot] ?: return
        val source = inventory[materialSlot] ?: return
        val scales = Blowpipe.scales(obj)
        val darts = Blowpipe.darts(obj)
        val dart = Blowpipe.dartType(obj)
        val loadingScales = material == objs.zulrahs_scales
        if (!loadingScales && darts > 0 && dart?.id != material.id) {
            mes("Unload your blowpipe before loading a different type of dart.")
            return
        }
        val amount = minOf(source.count, Blowpipe.CAPACITY - if (loadingScales) scales else darts)
        if (amount == 0) {
            mes("Your blowpipe cannot hold any more ${if (loadingScales) "scales" else "darts"}.")
            return
        }
        if (invDel(inventory, material, amount, slot = materialSlot).failure) return
        inventory[slot] =
            if (loadingScales) {
                Blowpipe.withAmmo(obj, scales + amount, dart, darts)
            } else {
                Blowpipe.withAmmo(obj, scales, material, darts + amount)
            }
        mes("You load $amount ${ocName(material)} into your blowpipe.")
    }

    private fun ProtectedAccess.checkCharges(obj: InvObj?) {
        val dart = Blowpipe.dartType(obj)
        val ammo = if (dart == null) "no darts" else "${Blowpipe.darts(obj)} ${ocName(dart)}"
        mes("Your blowpipe contains ${Blowpipe.scales(obj)} Zulrah's scales and $ammo.")
    }

    private suspend fun ProtectedAccess.unload(inventory: Inventory, slot: Int) {
        val obj = inventory[slot] ?: return
        if (Blowpipe.scales(obj) == 0 && Blowpipe.darts(obj) == 0) {
            mes("Your blowpipe is already empty.")
            return
        }
        val choice =
            choice3(
                "Unload darts.",
                1,
                "Remove all darts and scales.",
                2,
                "Cancel.",
                0,
                title = "Unload your blowpipe",
            )
        if (choice != 0 && inventory[slot] == obj) refund(inventory, slot, scales = choice == 2)
    }

    private fun ProtectedAccess.refund(inventory: Inventory, slot: Int, scales: Boolean) {
        val obj = inventory[slot] ?: return
        val dart = Blowpipe.dartType(obj)
        val darts = Blowpipe.darts(obj)
        val scaleCount = if (scales) Blowpipe.scales(obj) else 0
        val resources = buildList {
            if (dart != null && darts > 0) add(dart)
            if (scaleCount > 0) add(objs.zulrahs_scales)
        }
        if (inv.freeSpace() < resources.count { it !in inv }) {
            mes("You don't have enough inventory space for the scales or darts.")
            return
        }
        inventory[slot] = Blowpipe.withAmmo(obj, Blowpipe.scales(obj) - scaleCount, null, 0)
        if (dart != null && darts > 0) invAddOrDrop(objRepo, dart, darts)
        if (scaleCount > 0) invAddOrDrop(objRepo, objs.zulrahs_scales, scaleCount)
        mes(
            if (scales) "You remove the remaining scales and darts from your blowpipe."
            else "You unload the darts from your blowpipe."
        )
    }
}

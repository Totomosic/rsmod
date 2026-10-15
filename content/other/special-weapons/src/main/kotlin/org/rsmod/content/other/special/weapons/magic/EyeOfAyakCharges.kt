package org.rsmod.content.other.special.weapons.magic

import jakarta.inject.Inject
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.content.other.special.weapons.configs.EyeOfAyakVars
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory

class EyeOfAyakCharges @Inject constructor(private val charges: ObjChargeManager) {
    enum class Currency {
        Runes,
        DemonTears,
    }

    fun count(obj: InvObj?): Int = charges.getCharges(obj, EyeOfAyakVars.charges)

    fun currency(obj: InvObj?): Currency =
        if (charges.getCharges(obj, EyeOfAyakVars.demon_tears) == 1) Currency.DemonTears
        else Currency.Runes

    fun canAdd(obj: InvObj?, currency: Currency): Boolean =
        count(obj) == 0 || currency(obj) == currency

    fun add(inventory: Inventory, slot: Int, amount: Int, currency: Currency) {
        val obj = inventory.getValue(slot)
        require(canAdd(obj, currency)) { "Cannot mix Eye of ayak charge types" }
        require(amount in 1..(MAX_CHARGES - count(obj)))
        val vars =
            if (currency == Currency.DemonTears) obj.vars or DEMON_TEAR_FLAG
            else obj.vars and DEMON_TEAR_FLAG.inv()
        inventory[slot] = obj.copy(vars = vars)
        charges.addCharges(inventory, slot, amount, EyeOfAyakVars.charges, MAX_CHARGES)
    }

    fun removeAll(inventory: Inventory, slot: Int): Refund {
        val currency = currency(inventory[slot])
        val removed = charges.removeAllCharges(inventory, slot, EyeOfAyakVars.charges)
        val obj = inventory.getValue(slot)
        inventory[slot] = obj.copy(vars = obj.vars and DEMON_TEAR_FLAG.inv())
        return Refund(removed, currency)
    }

    data class Refund(val charges: Int, val currency: Currency)

    companion object {
        const val MAX_CHARGES = 50_000
        const val DEATH_PER_CHARGE = 2
        const val CHAOS_PER_CHARGE = 1
        private const val DEMON_TEAR_FLAG = 1 shl 16
    }
}

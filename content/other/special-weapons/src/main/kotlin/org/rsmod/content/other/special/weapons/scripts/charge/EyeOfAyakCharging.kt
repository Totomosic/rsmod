package org.rsmod.content.other.special.weapons.scripts.charge

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.objs
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpWorn2
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakCharges
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakCharges.Companion.CHAOS_PER_CHARGE
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakCharges.Companion.DEATH_PER_CHARGE
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakCharges.Companion.MAX_CHARGES
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakCharges.Currency
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class EyeOfAyakCharging
@Inject
constructor(private val charges: EyeOfAyakCharges, private val objRepo: ObjRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld2(special_weapon_objs.eye_of_ayak_uncharged) {
            mes(
                "Your Eye of ayak has no charges. Charge it with death and chaos runes or demon tears."
            )
        }
        onOpHeld3(special_weapon_objs.eye_of_ayak_uncharged) { charge(it.inventory, it.slot) }
        onOpHeld3(special_weapon_objs.eye_of_ayak) { checkCharges(it.inventory[it.slot]) }
        onOpHeld4(special_weapon_objs.eye_of_ayak) { uncharge(it.inventory, it.slot) }
        onOpWorn2(special_weapon_objs.eye_of_ayak) { checkCharges(player.righthand) }

        for (eye in
            listOf(special_weapon_objs.eye_of_ayak, special_weapon_objs.eye_of_ayak_uncharged)) {
            onOpHeldU(eye, objs.death_rune) { charge(inv, it.firstSlot, Currency.Runes) }
            onOpHeldU(eye, objs.chaos_rune) { charge(inv, it.firstSlot, Currency.Runes) }
            onOpHeldU(eye, special_weapon_objs.demon_tear) {
                charge(inv, it.firstSlot, Currency.DemonTears)
            }
        }
    }

    private suspend fun ProtectedAccess.charge(
        inventory: Inventory,
        slot: Int,
        requestedCurrency: Currency? = null,
    ) {
        val obj = inventory[slot] ?: return
        val current = charges.count(obj)
        if (current >= MAX_CHARGES) {
            mes("Your Eye of ayak is fully charged.")
            return
        }
        val currency = requestedCurrency ?: chooseCurrency(obj)
        if (!charges.canAdd(obj, currency)) {
            mes("You must uncharge your Eye of ayak before changing its charge type.")
            return
        }
        val available = min(MAX_CHARGES - current, availableCharges(currency))
        if (available == 0) {
            mes(
                if (currency == Currency.Runes)
                    "You need two death runes and one chaos rune per charge."
                else "You need one demon tear per charge."
            )
            return
        }
        val requested =
            countDialog("How many charges do you want to apply? (Up to $available)")
                .coerceIn(0, available)
        if (requested == 0 || inventory[slot] != obj) return

        val removed =
            when (currency) {
                Currency.Runes ->
                    invDel(
                        inv,
                        objs.death_rune,
                        requested * DEATH_PER_CHARGE,
                        objs.chaos_rune,
                        requested * CHAOS_PER_CHARGE,
                    )
                Currency.DemonTears -> invDel(inv, special_weapon_objs.demon_tear, requested)
            }
        if (removed.failure) return
        charges.add(inventory, slot, requested, currency)
        mes("You apply $requested charges to your Eye of ayak.")
    }

    private suspend fun ProtectedAccess.chooseCurrency(obj: InvObj): Currency {
        if (charges.count(obj) > 0) return charges.currency(obj)
        val runes = availableCharges(Currency.Runes) > 0
        val tears = availableCharges(Currency.DemonTears) > 0
        return when {
            runes && tears ->
                choice2(
                    "Runes.",
                    Currency.Runes,
                    "Demon tears.",
                    Currency.DemonTears,
                    title = "Charge your Eye of ayak with...",
                )
            tears -> Currency.DemonTears
            else -> Currency.Runes
        }
    }

    private fun ProtectedAccess.availableCharges(currency: Currency): Int =
        when (currency) {
            Currency.Runes ->
                min(
                    invTotal(inv, objs.death_rune) / DEATH_PER_CHARGE,
                    invTotal(inv, objs.chaos_rune) / CHAOS_PER_CHARGE,
                )
            Currency.DemonTears -> invTotal(inv, special_weapon_objs.demon_tear)
        }

    private fun ProtectedAccess.checkCharges(obj: InvObj?) {
        val material = if (charges.currency(obj) == Currency.DemonTears) "demon tears" else "runes"
        mes("Your Eye of ayak has ${charges.count(obj)} charges remaining, using $material.")
    }

    private suspend fun ProtectedAccess.uncharge(inventory: Inventory, slot: Int) {
        val obj = inventory[slot] ?: return
        val count = charges.count(obj)
        if (count == 0) {
            charges.removeAll(inventory, slot)
            return
        }
        val currency = charges.currency(obj)
        val resources =
            if (currency == Currency.DemonTears) listOf(special_weapon_objs.demon_tear)
            else listOf(objs.death_rune, objs.chaos_rune)
        val spaceRequired = resources.count { it !in inv }
        if (inv.freeSpace() < spaceRequired) {
            mes("You don't have enough inventory space for the refunded materials.")
            return
        }
        val confirmed =
            choice2(
                "Proceed.",
                true,
                "Cancel.",
                false,
                title = "Uncharge all the charges from your Eye of ayak?",
            )
        if (!confirmed || inventory[slot] != obj) return
        val refund = charges.removeAll(inventory, slot)
        when (refund.currency) {
            Currency.Runes -> {
                invAddOrDrop(objRepo, objs.death_rune, refund.charges * DEATH_PER_CHARGE)
                invAddOrDrop(objRepo, objs.chaos_rune, refund.charges * CHAOS_PER_CHARGE)
            }
            Currency.DemonTears ->
                invAddOrDrop(objRepo, special_weapon_objs.demon_tear, refund.charges)
        }
        mes("You uncharge your Eye of ayak and recover its remaining charging materials.")
    }
}

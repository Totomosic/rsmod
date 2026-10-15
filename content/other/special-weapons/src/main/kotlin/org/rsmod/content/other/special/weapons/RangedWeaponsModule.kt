package org.rsmod.content.other.special.weapons

import org.rsmod.api.weapons.WeaponMap
import org.rsmod.content.other.special.weapons.ranged.DarkBowWeapons
import org.rsmod.content.other.special.weapons.ranged.ToxicBlowpipeWeapons
import org.rsmod.plugin.module.PluginModule

class RangedWeaponsModule : PluginModule() {
    override fun bind() {
        addSetBinding<WeaponMap>(DarkBowWeapons::class.java)
        addSetBinding<WeaponMap>(ToxicBlowpipeWeapons::class.java)
    }
}

package org.rsmod.content.other.special.attacks

import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.content.other.special.attacks.boost.StatBoostSpecialAttacks
import org.rsmod.content.other.special.attacks.melee.AbyssalWhipSpecialAttack
import org.rsmod.content.other.special.attacks.melee.DragonClawsSpecialAttack
import org.rsmod.content.other.special.attacks.melee.DragonDaggerSpecialAttack
import org.rsmod.content.other.special.attacks.melee.DragonLongswordSpecialAttack
import org.rsmod.content.other.special.attacks.melee.DragonWarhammerSpecialAttack
import org.rsmod.content.other.special.attacks.melee.GodswordSpecialAttacks
import org.rsmod.content.other.special.attacks.melee.SingleHitMeleeSpecialAttacks
import org.rsmod.content.other.special.attacks.ranged.DarkBowSpecialAttack
import org.rsmod.content.other.special.attacks.ranged.MagicShortbowSpecialAttack
import org.rsmod.plugin.module.PluginModule

class SpecialAttackModule : PluginModule() {
    override fun bind() {
        addSetBinding<SpecialAttackMap>(StatBoostSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(DarkBowSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(DragonLongswordSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(DragonDaggerSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(AbyssalWhipSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(SingleHitMeleeSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(DragonWarhammerSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(GodswordSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(DragonClawsSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(MagicShortbowSpecialAttack::class.java)
    }
}

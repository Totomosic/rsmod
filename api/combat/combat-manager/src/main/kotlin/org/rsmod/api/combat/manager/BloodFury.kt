package org.rsmod.api.combat.manager

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.random.GameRandom
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isType
import org.rsmod.game.type.obj.Wearpos

/** Melee attack effects run once for each queued player splat, including secondary targets. */
public class BloodFury @Inject constructor(private val random: GameRandom) {
    public fun onHit(source: Player, damage: Int) {
        if (damage <= 0 || source.hitpoints <= 0 || source.hitpoints >= source.baseHitpointsLvl)
            return
        if (!source.worn[Wearpos.Front.slot].isType(blood_fury_objs.amulet)) return
        if (random.of(5) != 0) return
        source.statHeal(stats.hitpoints, constant = damage * 3 / 10, percent = 0)
    }
}

internal object blood_fury_objs : ObjReferences() {
    val amulet = find("blood_amulet")
}

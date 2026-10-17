package org.rsmod.api.combat.manager

import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import org.rsmod.api.area.checker.WildernessArea
import org.rsmod.api.config.refs.areas
import org.rsmod.api.hunt.NpcSearch
import org.rsmod.api.hunt.PlayerSearch
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.hunt.HuntVis

/** Shared 3x3 targeting for chinchompas and area spells, captured when an attack launches. */
public class CombatSplashTargets
@Inject
constructor(private val npcs: NpcSearch, private val players: PlayerSearch) {
    /** Includes [primary] first, followed by eligible targets of the same kind in the square. */
    public fun find(access: ProtectedAccess, primary: PathingEntity): List<PathingEntity> =
        with(access) {
            if (!mapMultiway() || !inArea(areas.multiway, primary.coords)) {
                return listOf(primary)
            }

            // Search southwest coordinates (including stacks), with LOS from the primary's tile.
            val additional =
                when (primary) {
                    is Npc ->
                        npcs
                            .findAllAny(primary.coords, distance = 1, vis = HuntVis.LineOfSight)
                            .filter {
                                it != primary &&
                                    it.isValidTarget() &&
                                    it.visType.hasOp(InteractionOp.Op2) &&
                                    inArea(areas.multiway, it.coords)
                            }
                            .toList()
                    is Player ->
                        players
                            .findAll(primary.coords, maxDistance = 1, vis = HuntVis.LineOfSight)
                            .filter {
                                it != primary &&
                                    it != player &&
                                    it.isValidTarget() &&
                                    inArea(areas.multiway, it.coords) &&
                                    canSplashPlayer(it)
                            }
                            .toList()
                }
            listOf(primary) + additional
        }

    private fun ProtectedAccess.canSplashPlayer(target: Player): Boolean {
        if (!WildernessArea.contains(coords) || !WildernessArea.contains(target.coords)) {
            return false
        }
        val difference = abs(player.combatLevel - target.combatLevel)
        val levels = min(WildernessArea.level(coords), WildernessArea.level(target.coords))
        return difference <= levels
    }
}

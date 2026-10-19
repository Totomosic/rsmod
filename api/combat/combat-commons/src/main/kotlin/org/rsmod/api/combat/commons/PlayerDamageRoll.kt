package org.rsmod.api.combat.commons

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.DamageRoll

/** Allows encounter reporting to attach the actual roll to its queued hit. */
public class PlayerDamageRoll(
    public val source: Player,
    public val target: PathingEntity,
    public val damage: Int,
    public val distribution: DamageRoll,
) : UnboundEvent

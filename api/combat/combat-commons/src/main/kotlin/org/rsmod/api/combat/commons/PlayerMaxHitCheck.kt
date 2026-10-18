package org.rsmod.api.combat.commons

import org.rsmod.events.KeyedEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType

/** Allows encounter rules to guarantee accuracy and the upper bound of a player's damage roll. */
public class PlayerMaxHitCheck(
    public val source: Player,
    public val target: Npc,
    public val style: HitType,
    public val attackRate: Int? = null,
) : KeyedEvent {
    override val id: Long = target.visType.id.toLong()
    public var forced: Boolean = false
}

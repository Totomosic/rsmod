package org.rsmod.api.combat.commons

import org.rsmod.events.KeyedEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType

/** Checked for each target before accuracy, damage, XP, or successful-hit effects are rolled. */
public class PlayerAttackCheck(
    public val source: Player,
    public val target: Npc,
    public val style: HitType,
    public val isThrall: Boolean = false,
) : KeyedEvent {
    override val id: Long = target.visType.id.toLong()
    /** Whether the attack may deal damage; thralls still queue a zero hit when this is false. */
    public var allowed: Boolean = true
}

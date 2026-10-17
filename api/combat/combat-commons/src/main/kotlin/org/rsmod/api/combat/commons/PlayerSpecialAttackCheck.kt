package org.rsmod.api.combat.commons

import org.rsmod.events.KeyedEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj

/** Allows encounters to exclude an NPC before a special selects it or rolls any hits. */
public class PlayerSpecialAttackCheck(
    public val source: Player,
    public val target: Npc,
    public val weapon: InvObj,
) : KeyedEvent {
    override val id: Long = target.visType.id.toLong()
    public var allowed: Boolean = true
}

package org.rsmod.api.npc.events

import org.rsmod.events.KeyedEvent
import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitBuilder

public class NpcHitEvents {
    /** Observational event after HP changes, including zero hits on a corpse. */
    public data class Applied(public val npc: Npc, public val hit: Hit) : UnboundEvent

    /** Published once when a hit kills an NPC, before its death animation or drops. */
    public data class Kill(public val npc: Npc, public val killer: Player) : UnboundEvent

    public data class Modify(
        public val npc: Npc,
        public val hit: HitBuilder,
        override val id: Long = npc.visType.id.toLong(),
    ) : KeyedEvent

    public data class Impact(
        public val npc: Npc,
        public val hit: Hit,
        override val id: Long = npc.visType.id.toLong(),
    ) : KeyedEvent
}

package org.rsmod.api.player.events

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit

public class PlayerHitEvents {
    /** Published after a standard hit lands; [damage] is the actual Hitpoints lost. */
    public data class Impact(
        public val player: Player,
        public val hit: Hit,
        public val damage: Int,
    ) : UnboundEvent
}

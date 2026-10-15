package org.rsmod.api.net.rsprot

import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfo
import org.rsmod.game.entity.Player

internal fun NpcInfo.updateView(player: Player, worldId: Int) {
    setViewDistance(player.npcViewDistance)
    updateCoord(worldId, player.level, player.x, player.z)
}

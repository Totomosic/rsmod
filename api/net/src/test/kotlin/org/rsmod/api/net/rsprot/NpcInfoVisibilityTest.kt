package org.rsmod.api.net.rsprot

import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfo
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfoLargeV5
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfoSmallV5
import net.rsprot.protocol.message.OutgoingGameMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

class NpcInfoVisibilityTest {
    @Test
    fun `large viewport retains every lane across the room and resets on departure`() {
        val protocols = NpcInfoTestFactory.create()
        val protocol = protocols.npcInfoProtocol
        val info = protocol.alloc(1, OldSchoolClientType.DESKTOP)
        // Use the same 104-tile scene around the room as the real client.
        info.updateBuildArea(-1, 406, 525)
        val lanes =
            listOf(CoordGrid(3295, 4233, 0), CoordGrid(3281, 4249, 0), CoordGrid(3310, 4248, 0))
        val avatars =
            lanes.mapIndexed { index, coords ->
                protocols.npcAvatarFactory.alloc(
                    index + 1,
                    8344 - index,
                    0,
                    coords.x,
                    coords.z,
                    0,
                    0,
                )
            }
        val player = Player()
        try {
            player.coords = CoordGrid(3295, 4248, 0)
            info.updateView(player, -1)
            protocol.update()
            assertEquals(setOf(1, 2, 3), info.getHighResolutionIndices(-1).toSet())
            info.checkPacket(NpcInfoSmallV5::class.java)

            player.coords = CoordGrid(3286, 4248, 0)
            info.updateView(player, -1)
            protocol.update()
            assertEquals(setOf(1, 2), info.getHighResolutionIndices(-1).toSet())
            info.checkPacket(NpcInfoSmallV5::class.java)

            player.npcViewDistance = 32
            for (x in listOf(3286, 3305, 3286)) {
                player.coords = CoordGrid(x, 4248, 0)
                info.updateView(player, -1)
                protocol.update()
                assertEquals(setOf(1, 2, 3), info.getHighResolutionIndices(-1).toSet())
                info.checkPacket(NpcInfoLargeV5::class.java)
            }

            player.npcViewDistance = 15
            info.updateView(player, -1)
            protocol.update()
            assertEquals(setOf(1, 2), info.getHighResolutionIndices(-1).toSet())
            info.checkPacket(NpcInfoSmallV5::class.java)
        } finally {
            protocol.dealloc(info)
            avatars.forEach(protocols.npcAvatarFactory::release)
        }
    }

    private fun NpcInfo.checkPacket(type: Class<out OutgoingGameMessage>) {
        val packet = toPacket(-1)
        try {
            assertInstanceOf(type, packet)
        } finally {
            packet.markConsumed()
            packet.safeRelease()
        }
    }
}

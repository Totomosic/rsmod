package org.rsmod.content.areas.dungeons.nylocas

import org.junit.jupiter.api.Test
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.client.ClientCycle
import org.rsmod.game.client.NoopClientCycle
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

class NylocasVisibilityTest {
    @Test
    fun GameTestState.`expanded radius follows source room and resets before client updates`() =
        runGameTest(NylocasVisibility::class) {
            val room = createRegion(BloatExitRoom.template)
            val observed = mutableListOf<Int>()
            player.clientCycle =
                object : ClientCycle by NoopClientCycle {
                    override fun update(player: Player) {
                        observed.add(player.npcViewDistance)
                    }
                }

            player.telejump(room.normal[BloatExitRoom.doorstep])
            advance()
            assertEquals(15, observed.last())

            // This applies before Start Raid, including arrival from the Bloat door.
            player.telejump(room.normal[NylocasRoom.bridgeStart])
            advance()
            assertEquals(32, observed.last())
            for (source in listOf(CoordGrid(3286, 4248, 0), CoordGrid(3305, 4248, 0))) {
                player.telejump(room.normal[source])
                advance()
                assertEquals(32, observed.last())
            }

            player.telejump(room.normal[BloatExitRoom.doorstep])
            advance()
            assertEquals(15, observed.last())
            player.telejump(room.normal[NylocasRoom.arena])
            advance()
            assertEquals(32, observed.last())
            player.telejump(CoordGrid(3222, 3218, 0))
            advance()
            assertEquals(15, observed.last())
        }

    @Test
    fun GameTestState.`visibility is independent for players in different instances and levels`() =
        runGameTest(NylocasVisibility::class) {
            val first = createRegion(NylocasRoom.template)
            player.telejump(first.normal[NylocasRoom.arena])
            advance()
            val second = createRegion(NylocasRoom.template)
            val other = registerPlayer(coords = second.normal[NylocasRoom.arena])
            val outside = registerPlayer(coords = CoordGrid(3222, 3218, 0))
            advance()
            assertEquals(32, player.npcViewDistance)
            assertEquals(32, other.npcViewDistance)
            assertEquals(15, outside.npcViewDistance)

            player.telejump(first.normal[CoordGrid(3295, 4248, 1)])
            advance()
            assertEquals(15, player.npcViewDistance)
            assertEquals(32, other.npcViewDistance)
        }
}

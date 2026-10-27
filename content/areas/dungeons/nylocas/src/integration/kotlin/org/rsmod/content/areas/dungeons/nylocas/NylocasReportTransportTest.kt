package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.hit.DamageRoll

class NylocasReportTransportTest {
    class Dependencies @Inject constructor(val raid: TheatreRaid)

    @Test
    fun GameTestState.`reported wave 28 snapshot stays within the actual chat packet size`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            val region = createRegion(NylocasRoom.template)
            player.telejump(region.normal[NylocasRoom.bridge])
            advance()
            deps.raid.start(player)
            deps.raid.sendWaveReport(region, REPORTED)
            val messages =
                client.filterIsInstance<MessageGame>().filter {
                    it.message.startsWith("LNW4 ") || it.message.startsWith("LNF1 ")
                }
            assertEquals(263, MessageGame(99, REPORTED).estimateSize())
            assertTrue(messages.isNotEmpty())
            messages.forEach { message ->
                assertTrue(
                    message.estimateSize() <= 255,
                    "MESSAGE_GAME length out of bounds: ${message.estimateSize()}",
                )
            }
            assertEquals(REPORTED, reassemble(messages.map { it.message }))
        }

    @Test
    fun `packet limit includes console framing and preserves short reports`() {
        val atLimit = "LNW4 62105b94-b7ee-47ba-bc30-b43e2586783b waves ".padEnd(252, '0')
        assertEquals(255, MessageGame(99, atLimit).estimateSize())
        assertEquals(listOf(atLimit), NylocasReportMessages.encode(atLimit))
        val overLimit = atLimit + "0"
        val messages = NylocasReportMessages.encode(overLimit)
        assertTrue(messages.size > 1)
        messages.forEach { assertTrue(MessageGame(99, it).estimateSize() <= 255) }
        assertEquals(overLimit, reassemble(messages))
        val check = "LNC1 62105b94-b7ee-47ba-bc30-b43e2586783b 70 200 28 1 24 24"
        assertEquals(listOf(check), NylocasReportMessages.encode(check))
    }

    @Test
    fun `full five player reports preserve every luck counter and encoded name`() {
        val stats = NylocasWaveStats()
        for (index in 1..5) stats.member(index, "Raider $index 名")
        for (wave in listOf(1, 21, 29)) {
            stats.wave = wave
            repeat(50) { index ->
                stats.impact(
                    NylocasWaveStats.Damage(
                        if (index % 2 == 0) NylocasSize.Small else NylocasSize.Big
                    ),
                    NylocasStyle.entries[index % 3],
                    16,
                    0,
                    false,
                    index,
                    DamageRoll(0..30),
                    source = index % 5 + 1,
                )
            }
        }
        val report =
            "LNW4 62105b94-b7ee-47ba-bc30-b43e2586783b " +
                stats.payload(20, "cleanup") +
                " " +
                stats.playerPayload()
        assertTrue(MessageGame(99, report).estimateSize() > 255)
        val messages = NylocasReportMessages.encode(report)
        messages.forEach { assertTrue(MessageGame(99, it).estimateSize() <= 255) }
        assertEquals(report, reassemble(messages))
        assertTrue(NylocasReportMessages.encode(report).first() != messages.first())
    }

    private fun reassemble(messages: List<String>): String {
        if (messages.size == 1 && messages.single().startsWith("LNW4 ")) return messages.single()
        val fragments = messages.map { it.split(" ", limit = 6) }
        assertTrue(fragments.all { it.size == 6 && it[0] == "LNF1" })
        assertEquals(fragments.first()[4].toInt(), fragments.size)
        assertEquals(1, fragments.map { it[1] to it[2] }.distinct().size)
        return fragments.sortedBy { it[3].toInt() }.joinToString("") { it[5] }
    }

    companion object {
        const val REPORTED =
            "LNW4 62105b94-b7ee-47ba-bc30-b43e2586783b waves " +
                "28 26 17 1 0 69 60 15 9 23 34 27 15 2 4 9 18 7 5 10 7 0 0 0 " +
                "69 60 54011924 14 7 8103637 0 0 0 0 0 0 4 902663 2 " +
                "QQ 33 31 27694478 8 5 5041096 0 0 0 0 0 0 3 911442 " +
                "Qg 36 29 26317446 6 2 3062542 0 0 0 0 0 0 1 704350"
    }
}

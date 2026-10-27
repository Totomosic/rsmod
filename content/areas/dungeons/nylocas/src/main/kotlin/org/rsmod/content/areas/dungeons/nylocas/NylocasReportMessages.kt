package org.rsmod.content.areas.dungeons.nylocas

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** MESSAGE_GAME has a byte-sized payload length, including three console framing bytes. */
object NylocasReportMessages {
    private const val MAX_TEXT = 252
    private const val CHUNK_SIZE = 180
    private const val MAX_PARTS = 32
    private val sequence = AtomicLong()

    fun encode(payload: String): List<String> {
        // Names are base64url UTF-8; every wire character is one CP1252 byte.
        require(payload.all { it in ' '..'~' }) { "Nylocas reports must be printable ASCII" }
        if (payload.length <= MAX_TEXT) return listOf(payload)
        require(payload.startsWith("LNW4 ")) { "Only wave snapshots use multipart transport" }
        require(payload.length <= CHUNK_SIZE * MAX_PARTS) { "Nylocas snapshot is too large" }
        val room = UUID.fromString(payload.split(' ', limit = 3)[1]).toString()
        val serial = sequence.incrementAndGet()
        val chunks = payload.chunked(CHUNK_SIZE)
        return chunks.mapIndexed { index, chunk ->
            "LNF1 $room $serial $index ${chunks.size} $chunk"
                .also {
                    check(it.length <= MAX_TEXT) { "Nylocas fragment exceeds MESSAGE_GAME budget" }
                }
        }
    }
}

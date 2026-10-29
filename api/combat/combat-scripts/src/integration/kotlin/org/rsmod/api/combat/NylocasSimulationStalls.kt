package org.rsmod.api.combat

/** Count authoritative admission failures, including retries, by the incoming wave. */
internal class NylocasSimulationStalls {
    private val counts = IntArray(32)

    val preCap: Int
        get() = (1..20).sumOf { counts[it] }

    val postCap: Int
        get() = (21..31).sumOf { counts[it] }

    val pre29: Int
        get() = (1..28).sumOf { counts[it] }

    val wave29: Int
        get() = counts[29]

    fun observe(message: String) {
        if (!message.startsWith("LNC1 ")) return
        // LNC1 room-id sequence tick incoming-wave attempt population cap.
        val fields = message.split(' ')
        if (fields[6].toInt() >= fields[7].toInt()) counts[fields[4].toInt()]++
    }
}

package com.bodyscalereader.app

import android.util.Log

object DataParser {

    private const val TAG = "DataParser"

    data class RawData(
        val weight: Float? = null,
        val impedance: Float? = null,
        val heartRate: Int? = null,
        val sessionId: Int? = null
    )

   fun parseFrame(frame: String): RawData {
    return runCatching {
        val parts = frame.split("-")
        if (parts.size < 11) return RawData()

        // Flag byte at position [9] tells us what type of frame this is
        val flag = parts[9].toInt(16)

        return when (flag) {
            0x01 -> parseWeightOnly(parts)       // weight stabilising, no impedance
            0x02 -> parseWeightWithImpedance(parts)  // full measurement
            0x03 -> parseHeartRate(parts)        // HR reading
            else -> RawData()
        }
    }.getOrElse { e ->
        Log.w(TAG, "Failed to parse frame '$frame': ${e.message}")
        RawData()
    }
}

private fun parseWeightOnly(parts: List<String>): RawData {
    // Weight is little-endian at bytes [3][4]
    val w = (parts[4].toInt(16) shl 8) or parts[3].toInt(16)
    return RawData(weight = w / 100f)
}

private fun parseWeightWithImpedance(parts: List<String>): RawData {
    val w = (parts[4].toInt(16) shl 8) or parts[3].toInt(16)
    val imp = (parts[7].toInt(16) shl 8) or parts[6].toInt(16)
    return RawData(weight = w / 100f, impedance = imp / 100f)
}

private fun parseHeartRate(parts: List<String>): RawData {
    // HR is a single byte at position [3]
    return RawData(heartRate = parts[3].toInt(16))
}

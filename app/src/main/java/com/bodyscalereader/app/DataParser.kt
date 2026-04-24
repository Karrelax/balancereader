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

            // Flag byte at position [9] tells us the frame type
            val flag = parts[9].toInt(16)

            when (flag) {
                0x01 -> parseWeightOnly(parts)          // weight stabilising, no impedance yet
                0x02 -> parseWeightWithImpedance(parts) // full measurement — use this one
                0x03 -> parseHeartRate(parts)           // heart rate reading
                else -> RawData()
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to parse frame '$frame': ${e.message}")
            RawData()
        }
    }

    // Weight only (scale still stabilising — ignore for final measurement)
    private fun parseWeightOnly(parts: List<String>): RawData {
        // Weight is little-endian at bytes [3][4]
        val w = (parts[4].toInt(16) shl 8) or parts[3].toInt(16)
        return RawData(weight = w / 100f)
    }

    // Full measurement: weight + impedance
    private fun parseWeightWithImpedance(parts: List<String>): RawData {
        // Weight: little-endian at bytes [3][4]
        val w = (parts[4].toInt(16) shl 8) or parts[3].toInt(16)
        // Impedance: little-endian at bytes [6][7]
        val imp = (parts[7].toInt(16) shl 8) or parts[6].toInt(16)
        return RawData(weight = w / 100f, impedance = imp / 100f)
    }

    // Heart rate: single byte at position [3]
    private fun parseHeartRate(parts: List<String>): RawData {
        return RawData(heartRate = parts[3].toInt(16))
    }
}

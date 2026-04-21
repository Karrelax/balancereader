package com.bodyscalereader.app

object DataParser {
    
    data class RawData(
        val weight: Float? = null,
        val impedance: Float? = null,
        val heartRate: Int? = null,
        val sessionId: Int? = null
    )
    
    fun parseFrame(frame: String): RawData {
        val parts = frame.split("-")
        if (parts.size < 4) return RawData()
        
        val type = parts[3].toInt(16)
        
        return when (type) {
            0x87 -> parseWeightFrame(parts)
            0x4C -> parseHeartRateFrame(parts)
            0x19 -> parseSessionFrame(parts)
            else -> RawData()
        }
    }
    
    private fun parseWeightFrame(parts: List<String>): RawData {
        // Little-endian: byte4 (bajo) + byte5 (alto)
        val weightValue = (parts[5].toInt(16) shl 8) or parts[4].toInt(16)
        val weight = weightValue / 100.0f
        
        var impedance: Float? = null
        if (parts.size >= 8 && (parts[6] != "00" || parts[7] != "00")) {
            val impValue = (parts[7].toInt(16) shl 8) or parts[6].toInt(16)
            impedance = impValue / 100.0f
        }
        
        return RawData(weight = weight, impedance = impedance)
    }
    
    private fun parseHeartRateFrame(parts: List<String>): RawData {
        val hr = parts[4].toInt(16)
        return RawData(heartRate = hr)
    }
    
    private fun parseSessionFrame(parts: List<String>): RawData {
        val sessionId = parts[4].toInt(16)
        return RawData(sessionId = sessionId)
    }
}
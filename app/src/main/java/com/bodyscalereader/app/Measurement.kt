package com.bodyscalereader.app.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "measurements")
data class Measurement(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Date,
    val weight: Float,
    val impedance: Float,
    val heartRate: Int,
    val bmi: Float,
    val bodyWater: Float,
    val bodyFat: Float,
    val muscleMass: Float,
    val visceralFat: Float,
    val boneMass: Float,
    val bmr: Int,
    val protein: Float,
    val subcutaneousFat: Float,
    val physicalAge: Int,
    val leanMass: Float,
    val standardWeight: Float,
    val skeletalMuscle: Float,
    val muscleRatio: Float,
    val bodyType: String,
    val rawFrames: String
)
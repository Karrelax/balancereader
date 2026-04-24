package com.bodyscalereader.app

object BodyCompositionCalculator {

    data class UserProfile(
        val heightM: Float,
        val age: Int,
        val sex: String  // "MASCULINO" or "FEMENINO"
    )

    data class Result(
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
        val bodyType: String
    )

    fun calculate(weight: Float, impedance: Float, heartRate: Int, profile: UserProfile): Result {
        val bmi = weight / (profile.heightM * profile.heightM)

        val bodyFat = calculateBodyFat(weight, impedance, profile)
        val leanMass = weight * (1 - bodyFat / 100)
        val muscleMass = leanMass * 0.93f
        val bodyWater = calculateBodyWater(weight, impedance, profile)
        val visceralFat = (0.5f * bodyFat + 0.1f * profile.age - 1.95f).coerceAtLeast(0f)
        val boneMass = leanMass * 0.048f
        val bmr = calculateBMR(weight, profile)
        val protein = (muscleMass * 0.22f / weight) * 100
        val subcutaneousFat = (bodyFat - visceralFat * 0.46f).coerceAtLeast(0f)
        val standardWeight = 21.75f * (profile.heightM * profile.heightM)
        val skeletalMuscle = (muscleMass * 0.55f / weight) * 100
        val idealMuscleMass = if (profile.sex == "FEMENINO") 0.4f * profile.heightM * 100
                              else 0.45f * profile.heightM * 100
        val muscleRatio = (muscleMass / idealMuscleMass) * 100
        val physicalAge = calculatePhysicalAge(bmr, profile.age)
        val bodyType = determineBodyType(bodyFat, muscleMass)

        return Result(
            bmi = bmi,
            bodyWater = bodyWater,
            bodyFat = bodyFat,
            muscleMass = muscleMass,
            visceralFat = visceralFat,
            boneMass = boneMass,
            bmr = bmr,
            protein = protein,
            subcutaneousFat = subcutaneousFat,
            physicalAge = physicalAge,
            leanMass = leanMass,
            standardWeight = standardWeight,
            skeletalMuscle = skeletalMuscle,
            muscleRatio = muscleRatio,
            bodyType = bodyType
        )
    }

    // FIX: body fat now uses BOTH weight and impedance, plus sex-specific offsets
    // Deurenberg formula adapted for bioelectrical impedance
    private fun calculateBodyFat(weight: Float, impedance: Float, profile: UserProfile): Float {
        val heightCm = profile.heightM * 100
        // Resistance index = height² / impedance
        val ri = (heightCm * heightCm) / impedance
        return if (profile.sex == "FEMENINO") {
            (0.756f * (weight / ri) + 0.110f * weight - 0.058f * profile.age - 1.6f)
                .coerceIn(3f, 60f)
        } else {
            (0.756f * (weight / ri) + 0.110f * weight - 0.058f * profile.age - 10.8f)
                .coerceIn(3f, 60f)
        }
    }

    // FIX: body water uses height and sex, not just impedance
    private fun calculateBodyWater(weight: Float, impedance: Float, profile: UserProfile): Float {
        val heightCm = profile.heightM * 100
        // Watson formula adapted for BIA
        return if (profile.sex == "FEMENINO") {
            ((0.495f * heightCm * heightCm / impedance) + 1.845f)
                .coerceIn(30f, 80f)
        } else {
            ((0.396f * heightCm * heightCm / impedance) + 3.791f)
                .coerceIn(30f, 80f)
        }
    }

    // FIX: BMR uses profile data, not hardcoded constants
    // Mifflin-St Jeor equation
    private fun calculateBMR(weight: Float, profile: UserProfile): Int {
        val heightCm = profile.heightM * 100
        return if (profile.sex == "FEMENINO") {
            (10 * weight + 6.25f * heightCm - 5 * profile.age - 161).toInt()
        } else {
            (10 * weight + 6.25f * heightCm - 5 * profile.age + 5).toInt()
        }
    }

    private fun calculatePhysicalAge(bmr: Int, chronologicalAge: Int): Int {
        val baseBMR = 1600
        val diff = baseBMR - bmr
        return (chronologicalAge + diff / 15).coerceIn(20, 80)
    }

    private fun determineBodyType(bodyFat: Float, muscleMass: Float): String {
        return when {
            bodyFat < 18 && muscleMass > 55 -> "Muscular"
            bodyFat < 20 -> "Delgado"
            bodyFat > 30 -> "Sobrepeso"
            else -> "Estándar"
        }
    }

    fun formatStats(result: Result): String {
        return """
            |📊 ESTADÍSTICAS CORPORALES
            |
            |📏 BMI: ${String.format("%.1f", result.bmi)}
            |💧 Agua corporal: ${String.format("%.1f", result.bodyWater)}%
            |🍔 Grasa corporal: ${String.format("%.1f", result.bodyFat)}%
            |💪 Masa muscular: ${String.format("%.1f", result.muscleMass)} kg
            |🎯 Grasa visceral: ${String.format("%.1f", result.visceralFat)}%
            |🦴 Masa ósea: ${String.format("%.1f", result.boneMass)} kg
            |🔥 BMR: ${result.bmr} kcal/día
            |🥚 Proteína: ${String.format("%.1f", result.protein)}%
            |📉 Grasa subcutánea: ${String.format("%.1f", result.subcutaneousFat)}%
            |🎂 Edad física: ${result.physicalAge} años
            |⚖️ Peso magro: ${String.format("%.1f", result.leanMass)} kg
            |⭐ Peso estándar: ${String.format("%.1f", result.standardWeight)} kg
            |🏋️ Músculo esquelético: ${String.format("%.1f", result.skeletalMuscle)}%
            |📈 Relación muscular: ${String.format("%.0f", result.muscleRatio)}%
            |👤 Tipo de cuerpo: ${result.bodyType}
        """.trimMargin()
    }
}

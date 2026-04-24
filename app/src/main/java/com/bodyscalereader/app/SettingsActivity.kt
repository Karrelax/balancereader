package com.bodyscalereader.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bodyscalereader.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Configuración"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadValues()

        binding.btnSave.setOnClickListener { saveValues() }
    }

    private fun loadValues() {
        binding.etHeight.setText(prefs.getFloat(KEY_HEIGHT, 1.70f).toString())
        binding.etAge.setText(prefs.getInt(KEY_AGE, 30).toString())
        val isFemale = prefs.getString(KEY_SEX, "FEMENINO") == "FEMENINO"
        binding.rbFemale.isChecked = isFemale
        binding.rbMale.isChecked = !isFemale
        binding.switchAutoConnect.isChecked = prefs.getBoolean(KEY_AUTO_CONNECT, false)
    }

    private fun saveValues() {
        val heightStr = binding.etHeight.text.toString()
        val ageStr = binding.etAge.text.toString()

        if (heightStr.isBlank() || ageStr.isBlank()) {
            Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show()
            return
        }

        val height = heightStr.toFloatOrNull()
        val age = ageStr.toIntOrNull()

        if (height == null || height < 1.0f || height > 2.5f) {
            Toast.makeText(this, "Altura inválida (ej: 1.65)", Toast.LENGTH_SHORT).show()
            return
        }
        if (age == null || age < 10 || age > 120) {
            Toast.makeText(this, "Edad inválida", Toast.LENGTH_SHORT).show()
            return
        }

        val sex = if (binding.rbFemale.isChecked) "FEMENINO" else "MASCULINO"

        prefs.edit()
            .putFloat(KEY_HEIGHT, height)
            .putInt(KEY_AGE, age)
            .putString(KEY_SEX, sex)
            .putBoolean(KEY_AUTO_CONNECT, binding.switchAutoConnect.isChecked)
            .apply()

        Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val PREFS_NAME = "user_profile"
        const val KEY_HEIGHT = "height"
        const val KEY_AGE = "age"
        const val KEY_SEX = "sex"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_SAVED_DEVICE = "saved_device_address"

        fun getUserProfile(context: Context): BodyCompositionCalculator.UserProfile {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return BodyCompositionCalculator.UserProfile(
                heightM = prefs.getFloat(KEY_HEIGHT, 1.70f),
                age = prefs.getInt(KEY_AGE, 30),
                sex = prefs.getString(KEY_SEX, "FEMENINO") ?: "FEMENINO"
            )
        }

        fun getSavedDeviceAddress(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAVED_DEVICE, null)
        }

        fun saveDeviceAddress(context: Context, address: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SAVED_DEVICE, address).apply()
        }

        fun isAutoConnectEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_CONNECT, false)
        }
    }
}

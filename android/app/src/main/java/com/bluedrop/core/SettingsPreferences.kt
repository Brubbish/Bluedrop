package com.bluedrop.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit


class SettingsPreferences(context: Context) {
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("bluedropCache", Context.MODE_PRIVATE)

    fun toggleAutoCopy(value: Boolean) {
        sharedPreferences.edit {
            putBoolean(
                AUTO_COPY_PREFS,
                value
            )
        }
    }

    fun getAutoCopy(): Boolean {
        return sharedPreferences.getBoolean(AUTO_COPY_PREFS, true)
    }

    fun changeTheme(value: Boolean) {
        sharedPreferences.edit {
            putBoolean(
                THEME_PREFS,
                value
            )
        }
    }

    fun getTheme(): Boolean {
        return sharedPreferences.getBoolean(THEME_PREFS, false)
    }

    fun markBatteryGuidanceShown() {
        sharedPreferences.edit { putBoolean(BATTERY_GUIDANCE_SHOWN, true) }
    }

    fun isBatteryGuidanceShown(): Boolean =
        sharedPreferences.getBoolean(BATTERY_GUIDANCE_SHOWN, false)

    companion object {
        const val AUTO_COPY_PREFS = "autoCopyPrefs"
        const val THEME_PREFS = "isDarkMode"
        const val BATTERY_GUIDANCE_SHOWN = "batteryGuidanceShown"
    }
}

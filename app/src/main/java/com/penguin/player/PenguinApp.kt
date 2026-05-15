package com.penguin.player

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class PenguinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("penguin_player", MODE_PRIVATE)
        val darkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}

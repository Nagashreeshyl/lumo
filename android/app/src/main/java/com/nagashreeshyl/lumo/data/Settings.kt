package com.nagashreeshyl.lumo.data

import android.content.Context

/** Small app settings (per device). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val automix get() = prefs.getBoolean("automix", true)
    fun setAutomix(on: Boolean) = prefs.edit().putBoolean("automix", on).apply()
    val autoplay get() = prefs.getBoolean("autoplay", true)
    fun setAutoplay(on: Boolean) = prefs.edit().putBoolean("autoplay", on).apply()
}

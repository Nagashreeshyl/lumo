package com.nagashreeshyl.nive.data

import android.content.Context

/** Small app settings (per device). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val automix get() = prefs.getBoolean("automix", true)
    fun setAutomix(on: Boolean) = prefs.edit().putBoolean("automix", on).apply()
}

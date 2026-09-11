package com.n44r.app.ble

import android.content.Context

/** Stores the ComModule's MAC address so we can auto-connect without scanning next time. */
object DevicePrefs {
    private const val PREFS = "waterrower_prefs"
    private const val KEY_ROWER_ADDRESS = "rower_mac_address"

    fun getSavedRowerAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROWER_ADDRESS, null)

    fun saveRowerAddress(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ROWER_ADDRESS, address).apply()
    }

    fun clearRowerAddress(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ROWER_ADDRESS).apply()
    }

    private const val KEY_HR_ENABLED = "heart_rate_enabled"

    fun getHeartRateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HR_ENABLED, false)

    fun saveHeartRateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HR_ENABLED, enabled).apply()
    }
}

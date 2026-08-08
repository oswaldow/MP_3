package com.learnlayout.mp_3

import android.content.Context

object MonitoPrefs {

    private const val PREFS_NAME = "monito_prefs"
    private const val KEY_HAS_DESCENDED = "has_descended"

    fun hasDescended(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HAS_DESCENDED, false)
    }

    fun setDescended(context: Context) {
        prefs(context).edit().putBoolean(KEY_HAS_DESCENDED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
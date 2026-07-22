package com.aetherai.iris.core

import android.content.Context

object ApiKeyStore {

    private const val PREFS_NAME = "iris_prefs"
    private const val KEY_GROQ_API_KEY = "groq_api_key"

    fun getKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
    }

    fun setKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()
    }

    fun hasKey(context: Context): Boolean = getKey(context).isNotBlank()
}

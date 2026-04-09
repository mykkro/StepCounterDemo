package com.example.stepcounterdemo

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    private const val PREFS_FILE = "app_prefs"
    private const val KEY_LOCALE = "app_locale"
    private const val DEFAULT_LOCALE = "cs"

    fun getLocale(context: Context): String =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LOCALE, DEFAULT_LOCALE) ?: DEFAULT_LOCALE

    fun setLocale(context: Context, localeCode: String) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, localeCode)
            .apply()
    }

    fun applyLocale(context: Context): Context {
        val localeCode = getLocale(context)
        val locale = Locale(localeCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}

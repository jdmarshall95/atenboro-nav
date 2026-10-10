package com.atenboro.nav.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Пользовательские настройки продукта:
 *  - режим оформления (день/ночь) — сохраняется между запусками;
 *  - включённые источники данных. AIDL является основным и не отключается,
 *    остальные по умолчанию выключены и включаются пользователем.
 */
object AppPrefs {
    private const val PREFS = "atenboro_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_NOTIF = "src_notif"
    private const val KEY_A11Y = "src_a11y"
    private const val KEY_LOGCAT = "src_logcat"

    /** "auto" | "day" | "night" */
    @Volatile var themeMode: String = "auto"
        private set

    @Volatile var notifEnabled: Boolean = false
        private set

    @Volatile var a11yEnabled: Boolean = false
        private set

    @Volatile var logcatEnabled: Boolean = false
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        themeMode = p.getString(KEY_THEME, "auto") ?: "auto"
        notifEnabled = p.getBoolean(KEY_NOTIF, false)
        a11yEnabled = p.getBoolean(KEY_A11Y, false)
        logcatEnabled = p.getBoolean(KEY_LOGCAT, false)
    }

    /** Применить сохранённый режим к AppCompat (вызывать до setContentView). */
    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                "day" -> AppCompatDelegate.MODE_NIGHT_NO
                "night" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * Цикл auto → day → night → auto.
     * @return новый режим
     */
    fun cycleTheme(context: Context): String {
        themeMode = when (themeMode) {
            "auto" -> "day"
            "day" -> "night"
            else -> "auto"
        }
        prefs(context).edit().putString(KEY_THEME, themeMode).apply()
        applyTheme()
        return themeMode
    }

    fun setNotifEnabled(context: Context, enabled: Boolean) {
        notifEnabled = enabled
        prefs(context).edit().putBoolean(KEY_NOTIF, enabled).apply()
    }

    fun setA11yEnabled(context: Context, enabled: Boolean) {
        a11yEnabled = enabled
        prefs(context).edit().putBoolean(KEY_A11Y, enabled).apply()
    }

    fun setLogcatEnabled(context: Context, enabled: Boolean) {
        logcatEnabled = enabled
        prefs(context).edit().putBoolean(KEY_LOGCAT, enabled).apply()
    }

    /** Название текущего режима для подсказки. */
    fun themeLabel(): String = when (themeMode) {
        "day" -> "день"
        "night" -> "ночь"
        else -> "авто"
    }
}

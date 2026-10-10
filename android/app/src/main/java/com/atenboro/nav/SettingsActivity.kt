package com.atenboro.nav

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atenboro.nav.databinding.ActivitySettingsBinding
import com.atenboro.nav.service.GisLogcatWatcher
import com.atenboro.nav.settings.AppPrefs
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

/**
 * Настройки: выбор источников данных и статусы запасных каналов.
 * Основной экран остаётся одноэкранным, всё «сложное» живёт здесь.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private enum class Level { OK, PENDING, OFF, ERROR }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.load(this)
        AppPrefs.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTheme.setOnClickListener {
            AppPrefs.cycleTheme(this)
            Toast.makeText(this, "Оформление: ${AppPrefs.themeLabel()}", Toast.LENGTH_SHORT).show()
        }

        bindSwitch(binding.swNotif, AppPrefs.notifEnabled) { enabled ->
            AppPrefs.setNotifEnabled(this, enabled)
            if (enabled && !isNotifListenerEnabled()) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            refreshStatuses()
        }

        bindSwitch(binding.swA11y, AppPrefs.a11yEnabled) { enabled ->
            AppPrefs.setA11yEnabled(this, enabled)
            if (enabled && !isAccessibilityEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            refreshStatuses()
        }

        bindSwitch(binding.swLogcat, AppPrefs.logcatEnabled) { enabled ->
            AppPrefs.setLogcatEnabled(this, enabled)
            if (enabled) GisLogcatWatcher.start(this) else GisLogcatWatcher.stop()
        }

        lifecycleScope.launch {
            NavBus.a11yConnected.collect { refreshStatuses() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        refreshNotifStatus()
        refreshA11yStatus()
    }

    private fun refreshNotifStatus() {
        if (!AppPrefs.notifEnabled) {
            render(binding.dotNotif, binding.statusNotif, getString(R.string.state_off_source), Level.OFF)
            return
        }
        if (isNotifListenerEnabled()) {
            render(binding.dotNotif, binding.statusNotif, getString(R.string.state_active), Level.OK)
        } else {
            render(binding.dotNotif, binding.statusNotif, getString(R.string.state_not_allowed), Level.ERROR)
        }
    }

    private fun refreshA11yStatus() {
        if (!AppPrefs.a11yEnabled) {
            render(binding.dotA11y, binding.statusA11y, getString(R.string.state_off_source), Level.OFF)
            return
        }
        when {
            NavBus.a11yConnected.value ->
                render(binding.dotA11y, binding.statusA11y, getString(R.string.state_active), Level.OK)
            isAccessibilityEnabled() ->
                render(binding.dotA11y, binding.statusA11y, getString(R.string.state_waiting), Level.PENDING)
            else ->
                render(binding.dotA11y, binding.statusA11y, getString(R.string.state_not_allowed), Level.ERROR)
        }
    }

    private fun render(dot: View, label: TextView, text: String, level: Level) {
        label.text = text
        val res = when (level) {
            Level.OK -> R.drawable.dot_status_gold
            Level.PENDING -> R.drawable.dot_status_gray
            Level.OFF -> R.drawable.dot_status_off
            Level.ERROR -> R.drawable.dot_status_red
        }
        dot.setBackgroundResource(res)
    }

    /** Ставит нужное состояние без ложного срабатывания слушателя. */
    private fun bindSwitch(sw: MaterialSwitch, initial: Boolean, onChange: (Boolean) -> Unit) {
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${packageName}.service.NavAccessibilityService"
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return setting.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isNotifListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val expected = "$packageName/${packageName}.service.NavNotificationListener"
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}

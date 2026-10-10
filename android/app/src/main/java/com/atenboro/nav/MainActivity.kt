package com.atenboro.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atenboro.nav.databinding.ActivityMainBinding
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.debug.DebugSync
import com.atenboro.nav.model.NavUpdate
import com.atenboro.nav.net.EspClient
import com.atenboro.nav.net.EspNetwork
import com.atenboro.nav.service.GisLogcatWatcher
import com.atenboro.nav.service.NavProxyService
import com.atenboro.nav.settings.AppPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Основной экран: один экран без прокрутки.
 * День/ночь, шестерёнка настроек, статусы подключений и запуск прокси.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val esp = EspClient()
    private lateinit var debugStore: DebugStore
    private var healthJob: Job? = null

    private enum class Level { OK, PENDING, ERROR }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.load(this)
        AppPrefs.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        debugStore = DebugStore.get(this)
        debugStore.info("MainActivity start")

        requestNotifPermission()
        requestWifiPermissions()
        handleInjectIntent(intent)

        binding.btnTheme.setOnClickListener {
            AppPrefs.cycleTheme(this)
            Toast.makeText(this, "Оформление: ${AppPrefs.themeLabel()}", Toast.LENGTH_SHORT).show()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnConnectEsp.setOnClickListener {
            lifecycleScope.launch {
                render(binding.dotEsp, binding.statusEsp, getString(R.string.state_checking), Level.PENDING)
                debugStore.info("wifi connect tapped")
                val ok = EspNetwork.connectAndBind(this@MainActivity)
                refreshInetStatus()
                if (ok && esp.health(this@MainActivity)) {
                    render(
                        binding.dotEsp, binding.statusEsp,
                        getString(R.string.state_online) + " (${EspClient.defaultBaseUrl()})",
                        Level.OK
                    )
                    debugStore.info("esp online (SoftAP secondary, LTE untouched)")
                    DebugSync(this@MainActivity, esp, debugStore).sync()
                    Toast.makeText(this@MainActivity, "Связь с платой есть", Toast.LENGTH_SHORT).show()
                } else {
                    render(
                        binding.dotEsp, binding.statusEsp,
                        getString(R.string.state_offline) + " — ${EspNetwork.AP_SSID}",
                        Level.ERROR
                    )
                    debugStore.warn("esp offline after connect")
                    Toast.makeText(
                        this@MainActivity,
                        "Нет связи. В настройках Wi‑Fi подключитесь к ${EspNetwork.AP_SSID}, " +
                            "пароль ${EspNetwork.AP_PASS}, затем снова нажмите кнопку.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnStartProxy.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, NavProxyService::class.java))
            if (AppPrefs.logcatEnabled) GisLogcatWatcher.start(this)
            debugStore.info("proxy service started")
            Toast.makeText(this, "Прокси запущен", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            NavBus.a11yConnected.collect { refreshAidlStatus() }
        }

        lifecycleScope.launch {
            NavBus.gisApiConnected.collect { refreshAidlStatus() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInjectIntent(intent)
    }

    /** adb: am start -n com.atenboro.nav/.MainActivity --es turn right --ei dist_m 350 --ez camera true --ei cam_m 180 --ei cam_kmh 60 --es street Sayanskaya */
    private fun handleInjectIntent(intent: Intent?) {
        if (intent?.action != ACTION_INJECT_NAV && intent?.hasExtra("turn") != true) return
        val turn = intent.getStringExtra("turn") ?: return
        val dist = intent.getIntExtra("dist_m", -1)
        val camera = intent.getBooleanExtra("camera", false)
        val camM = intent.getIntExtra("cam_m", -1)
        val camKmh = intent.getIntExtra("cam_kmh", -1)
        val street = intent.getStringExtra("street")
        val maneuverIcon = intent.getStringExtra("maneuver_icon").orEmpty()
        val icon = intent.getStringExtra("icon")
            ?: com.atenboro.nav.parse.ManeuverIconClassifier.fallbackGlyphHex(turn)
        lifecycleScope.launch {
            EspNetwork.bindIfReachable(this@MainActivity)
            val update = NavUpdate(
                turn = turn,
                maneuverIcon = maneuverIcon,
                distM = dist,
                camera = camera,
                camM = camM,
                camKmh = camKmh,
                street = street,
                iconHex = icon,
                navigating = true
            )
            val result = esp.sendNav(update, this@MainActivity)
            debugStore.info(
                if (result.isSuccess) "inject sent $turn ${dist}m"
                else "inject fail: ${result.exceptionOrNull()?.message}"
            )
            if (result.isSuccess) NavBus.publish(update.copy(httpStatus = "OK"))
        }
    }

    override fun onResume() {
        super.onResume()
        EspNetwork.bindIfReachable(this)
        refreshStatuses()
        startHealthLoop()
    }

    override fun onPause() {
        healthJob?.cancel()
        super.onPause()
    }

    /** Единая точка обновления статусов — вызывается по событиям NavBus и из health-цикла. */
    private fun refreshStatuses() {
        refreshAidlStatus()
        refreshInetStatus()
    }

    private fun refreshAidlStatus() {
        if (NavBus.gisApiConnected.value) {
            render(binding.dotGisApi, binding.statusGisApi, getString(R.string.state_connected), Level.OK)
        } else {
            render(binding.dotGisApi, binding.statusGisApi, getString(R.string.state_no_link), Level.PENDING)
        }
    }

    private fun refreshInetStatus() {
        val label = EspNetwork.internetLabel(this)
        val ok = EspNetwork.hasInternet(this)
        render(
            binding.dotInet, binding.statusInet, label,
            if (ok) Level.OK else Level.PENDING
        )
    }

    private fun render(dot: View, label: TextView, text: String, level: Level) {
        label.text = text
        val res = when (level) {
            Level.OK -> R.drawable.dot_status_gold
            Level.PENDING -> R.drawable.dot_status_gray
            Level.ERROR -> R.drawable.dot_status_red
        }
        dot.setBackgroundResource(res)
    }

    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = lifecycleScope.launch {
            while (isActive) {
                val ok = esp.health(this@MainActivity)
                if (ok) {
                    render(
                        binding.dotEsp, binding.statusEsp,
                        getString(R.string.state_online) + " (${EspClient.defaultBaseUrl()})",
                        Level.OK
                    )
                } else {
                    render(
                        binding.dotEsp, binding.statusEsp,
                        getString(R.string.state_offline),
                        Level.ERROR
                    )
                }
                refreshInetStatus()
                delay(2_000)
            }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            1001
        )
    }

    private fun requestWifiPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1002)
        }
    }

    companion object {
        const val ACTION_INJECT_NAV = "com.atenboro.nav.INJECT_NAV"
    }
}

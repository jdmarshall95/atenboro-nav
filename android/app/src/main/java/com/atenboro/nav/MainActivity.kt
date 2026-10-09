package com.atenboro.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atenboro.nav.databinding.ActivityMainBinding
import com.atenboro.nav.model.NavUpdate
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.debug.DebugSync
import com.atenboro.nav.net.EspClient
import com.atenboro.nav.net.EspNetwork
import com.atenboro.nav.service.NavProxyService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val esp = EspClient()
    private lateinit var debugStore: DebugStore
    private var healthJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        debugStore = DebugStore.get(this)
        debugStore.info("MainActivity start")
        com.atenboro.nav.service.GisLogcatWatcher.start(this)

        requestNotifPermission()
        requestWifiPermissions()
        handleInjectIntent(intent)

        binding.btnConnectEsp.setOnClickListener {
            lifecycleScope.launch {
                binding.statusEsp.text = "ESP: подключение к ${EspNetwork.AP_SSID}…"
                binding.dotEsp.setBackgroundColor(Color.parseColor("#FFB703"))
                debugStore.info("wifi connect tapped")
                val ok = EspNetwork.connectAndBind(this@MainActivity)
                refreshInetStatus()
                if (ok && esp.health(this@MainActivity)) {
                    binding.statusEsp.text = "ESP: онлайн (${EspClient.defaultBaseUrl()})"
                    binding.dotEsp.setBackgroundColor(Color.parseColor("#3DDC97"))
                    debugStore.info("esp online (SoftAP secondary, LTE untouched)")
                    DebugSync(this@MainActivity, esp, debugStore).sync()
                    Toast.makeText(this@MainActivity, "Связь с платой есть", Toast.LENGTH_SHORT).show()
                } else {
                    binding.statusEsp.text =
                        "ESP: офлайн — выберите ${EspNetwork.AP_SSID} / ${EspNetwork.AP_PASS}"
                    binding.dotEsp.setBackgroundColor(Color.parseColor("#FF4D4D"))
                    debugStore.warn("esp offline after connect")
                    Toast.makeText(
                        this@MainActivity,
                        "Нет связи. В системных настройках Wi‑Fi подключитесь к ${EspNetwork.AP_SSID}, пароль ${EspNetwork.AP_PASS}, затем снова нажмите кнопку.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnOpenA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOpenNotif.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        binding.btnTestOled.setOnClickListener {
            lifecycleScope.launch {
                EspNetwork.bindIfReachable(this@MainActivity)
                val sample = NavUpdate(
                    turn = "left",
                    distM = 250,
                    camera = true,
                    camM = 120
                )
                val result = esp.sendNav(sample, this@MainActivity)
                if (result.isSuccess) {
                    debugStore.info("test oled ok")
                    NavBus.publish(sample)
                } else {
                    debugStore.error("test oled fail: ${result.exceptionOrNull()?.message}")
                }
                Toast.makeText(
                    this@MainActivity,
                    if (result.isSuccess) "OLED: тест отправлен" else "Ошибка: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnDump.setOnClickListener {
            NavBus.requestDump()
            debugStore.info("a11y dump requested")
            Toast.makeText(this, "Запрос dump…", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyDebug.setOnClickListener {
            val text = binding.txtDebugInfo.text?.toString().orEmpty()
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("atenboro-debug", text))
            debugStore.info("debug text copied")
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }

        binding.btnDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        binding.btnStartProxy.setOnClickListener {
            val intent = Intent(this, NavProxyService::class.java)
            ContextCompat.startForegroundService(this, intent)
            debugStore.info("proxy service started")
            Toast.makeText(this, "Прокси запущен", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            NavBus.update.collect { update ->
                binding.preview.text = update.previewText()
                binding.txtDebugInfo.text = update.debugDetailsText()
            }
        }

        lifecycleScope.launch {
            NavBus.a11yConnected.collect { connected ->
                if (connected) {
                    binding.statusA11y.text = "Accessibility: активен"
                    binding.dotA11y.setBackgroundColor(Color.parseColor("#3DDC97"))
                } else {
                    binding.statusA11y.text = "Accessibility: выкл (требуется настройка)"
                    binding.dotA11y.setBackgroundColor(Color.parseColor("#FFB703"))
                }
            }
        }

        lifecycleScope.launch {
            NavBus.gisApiConnected.collect { connected ->
                if (connected) {
                    binding.statusGisApi.text = "2GIS API: подключено (Dashboard AIDL)"
                    binding.dotGisApi.setBackgroundColor(Color.parseColor("#3DDC97"))
                } else {
                    binding.statusGisApi.text = "2GIS API: нет связи (2ГИС не запущен?)"
                    binding.dotGisApi.setBackgroundColor(Color.parseColor("#FFB703"))
                }
            }
        }

        lifecycleScope.launch {
            NavBus.lastDumpPath.collect { path ->
                if (path != null) {
                    Toast.makeText(this@MainActivity, "Dump: $path", Toast.LENGTH_LONG).show()
                }
            }
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
        refreshA11yLabel()
        EspNetwork.bindIfReachable(this)
        startHealthLoop()
    }

    override fun onPause() {
        healthJob?.cancel()
        super.onPause()
    }

    private fun refreshA11yLabel() {
        val enabled = isAccessibilityEnabled()
        if (!NavBus.a11yConnected.value) {
            if (enabled) {
                binding.statusA11y.text = "Accessibility: разрешена (ожидание)"
                binding.dotA11y.setBackgroundColor(Color.parseColor("#3DDC97"))
            } else {
                binding.statusA11y.text = "Accessibility: выкл (откройте настройки)"
                binding.dotA11y.setBackgroundColor(Color.parseColor("#FF4D4D"))
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${packageName}.service.NavAccessibilityService"
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return setting.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = lifecycleScope.launch {
            while (isActive) {
                val ok = esp.health(this@MainActivity)
                if (ok) {
                    binding.statusEsp.text = "ESP: онлайн (${EspClient.defaultBaseUrl()})"
                    binding.dotEsp.setBackgroundColor(Color.parseColor("#3DDC97"))
                } else {
                    binding.statusEsp.text =
                        "ESP: офлайн — нажмите «Подключить к плате»"
                    binding.dotEsp.setBackgroundColor(Color.parseColor("#FF4D4D"))
                }
                refreshInetStatus()
                delay(2_000)
            }
        }
    }

    private fun refreshInetStatus() {
        val label = EspNetwork.internetLabel(this)
        val ok = EspNetwork.hasInternet(this)
        binding.statusInet.text = "Интернет: $label"
        binding.dotInet.setBackgroundColor(
            Color.parseColor(if (ok) "#3DDC97" else "#FFB703")
        )
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

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

        requestNotifPermission()
        requestWifiPermissions()

        binding.btnConnectEsp.setOnClickListener {
            lifecycleScope.launch {
                binding.statusEsp.text = "ESP: подключение к ${EspNetwork.AP_SSID}…"
                binding.dotEsp.setBackgroundColor(Color.parseColor("#FFB703"))
                debugStore.info("wifi connect tapped")
                val ok = EspNetwork.connectAndBind(this@MainActivity)
                if (ok && esp.health(this@MainActivity)) {
                    binding.statusEsp.text = "ESP: онлайн (${EspClient.DEFAULT_BASE_URL})"
                    binding.dotEsp.setBackgroundColor(Color.parseColor("#3DDC97"))
                    debugStore.info("esp online")
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
            NavBus.lastDumpPath.collect { path ->
                if (path != null) {
                    Toast.makeText(this@MainActivity, "Dump: $path", Toast.LENGTH_LONG).show()
                }
            }
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
                    binding.statusEsp.text = "ESP: онлайн (${EspClient.DEFAULT_BASE_URL})"
                    binding.dotEsp.setBackgroundColor(Color.parseColor("#3DDC97"))
                } else {
                    binding.statusEsp.text =
                        "ESP: офлайн — нажмите «Подключить к плате»"
                    binding.dotEsp.setBackgroundColor(Color.parseColor("#FF4D4D"))
                }
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
}

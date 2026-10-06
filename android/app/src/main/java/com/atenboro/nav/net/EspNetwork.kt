package com.atenboro.nav.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * SoftAP без интернета: Android по умолчанию гоняет трафик через LTE,
 * и http://192.168.4.1 не доходит до платы. Нужно bindProcessToNetwork.
 */
object EspNetwork {
    const val AP_SSID = "atenboro-nav"
    const val AP_PASS = "atenboro1"
    const val ESP_HOST = "192.168.4.1"
    const val ESP_PORT = 80

    /**
     * Эмулятор / mock-плата: `adb shell setprop debug.atenboro.esp_url http://10.0.2.2:18765`
     * (хост-машина с scripts/mock_board.py). Пусто = реальный SoftAP.
     */
    fun espBaseUrl(): String {
        val override = systemProperty("debug.atenboro.esp_url").trim().trimEnd('/')
        return override.ifEmpty { "http://$ESP_HOST" }
    }

    fun espHostPort(): Pair<String, Int> {
        val url = espBaseUrl()
        return try {
            val u = java.net.URI(url)
            val host = u.host ?: ESP_HOST
            val port = if (u.port > 0) u.port else ESP_PORT
            host to port
        } catch (_: Exception) {
            ESP_HOST to ESP_PORT
        }
    }

    private fun systemProperty(key: String): String =
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as String
        }.getOrDefault("")

    private val bound = AtomicReference<Network?>(null)
    private var requestCallback: ConnectivityManager.NetworkCallback? = null

    fun current(): Network? = bound.get()

    /** Mock-плата на host/loopback — bind к SoftAP не нужен. */
    fun isMockBoard(): Boolean {
        val (host, _) = espHostPort()
        return host == "10.0.2.2" || host == "127.0.0.1" || host == "localhost"
    }

    /** Привязать процесс к уже подключённой сети, через которую пингуется ESP. */
    fun bindIfReachable(context: Context): Boolean {
        if (isMockBoard()) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            if (!canReachEsp(network)) continue
            cm.bindProcessToNetwork(network)
            bound.set(network)
            return true
        }
        // Уже bound?
        bound.get()?.let {
            if (canReachEsp(it)) {
                cm.bindProcessToNetwork(it)
                return true
            }
        }
        return false
    }

    fun unbind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        requestCallback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            requestCallback = null
        }
        cm.bindProcessToNetwork(null)
        bound.set(null)
    }

    /**
     * Запросить подключение к SoftAP платы (Android 10+) и привязать процесс.
     * @return true если сеть получена и ESP отвечает на TCP:80
     */
    suspend fun connectAndBind(context: Context): Boolean {
        if (isMockBoard()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return bindIfReachable(context)
        }

        // Уже на нужной сети
        if (bindIfReachable(context)) return true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        requestCallback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            requestCallback = null
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(AP_SSID)
            .setWpa2Passphrase(AP_PASS)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val network = withTimeoutOrNull(45_000L) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                        bound.set(network)
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onLost(network: Network) {
                        if (bound.get() == network) {
                            bound.set(null)
                            cm.bindProcessToNetwork(null)
                        }
                    }
                }
                requestCallback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(cb) }
                    requestCallback = null
                }
            }
        }

        if (network == null) return false
        // Дать DHCP/AP чуть времени
        repeat(10) {
            if (canReachEsp(network)) return true
            Thread.sleep(300)
        }
        return canReachEsp(network)
    }

    private fun canReachEsp(network: Network): Boolean {
        val (host, port) = espHostPort()
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

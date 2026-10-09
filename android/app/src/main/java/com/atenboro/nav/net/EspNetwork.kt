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
import javax.net.SocketFactory
import kotlin.coroutines.resume

/**
 * SoftAP платы + живой LTE/интернет.
 *
 * Не вызываем [ConnectivityManager.bindProcessToNetwork]: иначе весь трафик
 * процесса (2ГИС, AIDL, HTTPS) уходит в SoftAP без интернета.
 * HTTP к ESP идёт через [socketFactory] выбранной SoftAP-[Network].
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

    private val softAp = AtomicReference<Network?>(null)
    private var requestCallback: ConnectivityManager.NetworkCallback? = null

    fun current(): Network? = softAp.get()

    /** SocketFactory SoftAP (или default, если mock / ещё не подключены). */
    fun socketFactory(): SocketFactory =
        softAp.get()?.socketFactory ?: SocketFactory.getDefault()

    /** Mock-плата на host/loopback — SoftAP не нужен. */
    fun isMockBoard(): Boolean {
        val (host, _) = espHostPort()
        return host == "10.0.2.2" || host == "127.0.0.1" || host == "localhost"
    }

    /**
     * Найти уже подключённую сеть, через которую пингуется ESP, и запомнить её
     * **без** process-wide bind (LTE остаётся default для остального трафика).
     */
    fun bindIfReachable(context: Context): Boolean {
        if (isMockBoard()) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        softAp.get()?.let {
            if (canReachEsp(it)) return true
        }
        for (network in cm.allNetworks) {
            if (!canReachEsp(network)) continue
            softAp.set(network)
            return true
        }
        return false
    }

    fun unbind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        requestCallback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            requestCallback = null
        }
        // Не трогаем bindProcessToNetwork — мы его и не ставили.
        softAp.set(null)
    }

    /**
     * Запросить secondary SoftAP (Android 10+) без перехвата default network.
     * @return true если SoftAP получен и ESP отвечает на TCP
     */
    suspend fun connectAndBind(context: Context): Boolean {
        if (isMockBoard()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return bindIfReachable(context)
        }

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
                        softAp.set(network)
                        // НЕ bindProcessToNetwork — оставляем LTE/Wi‑Fi с интернетом default.
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onLost(network: Network) {
                        if (softAp.get() == network) {
                            softAp.set(null)
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
        repeat(10) {
            if (canReachEsp(network)) return true
            Thread.sleep(300)
        }
        return canReachEsp(network)
    }

    /** Есть ли у устройства сеть с интернетом (обычно LTE или обычный Wi‑Fi). */
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Короткая подпись транспорта default-сети: LTE / Wi‑Fi / нет. */
    fun internetLabel(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return "нет"
        val caps = cm.getNetworkCapabilities(active) ?: return "нет"
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!validated) return "нет"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "LTE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "да"
        }
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

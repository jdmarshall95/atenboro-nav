package com.atenboro.nav.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.atenboro.nav.NavBus
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.nav.NavFeed
import com.atenboro.nav.parse.GisDashboardInfo
import ru.dublgis.api.IDashboardInformationService
import ru.dublgis.api.IUpdateCallback

/**
 * Клиент 2GIS Dashboard AIDL API — официальный способ получить данные навигации.
 *
 * Bind: `ru.dublgis.api.ACTION_BIND_DASHBOARD_INFORMATION_SERVICE` →
 * `ru.dublgis.api.DashboardInformationService`.
 *
 * Почему это лучше прежних источников:
 *  - camera + лимит приходят структурированно (README: в notif 7.9.x их не дождаться);
 *  - maneuverIcon — точный кодоным стрелки, а не классификация bitmap'а;
 *  - есть speedLimit, светофор, пробка, progress, режим (motorcycle).
 *
 * Приоритет: публикуем в [NavFeed] с source "aidl" — он старше notif/a11y.
 */
object GisDashboardClient {

    private const val TAG = "AtenboroNavGisApi"
    private const val ACTION = "ru.dublgis.api.ACTION_BIND_DASHBOARD_INFORMATION_SERVICE"
    private const val RECONNECT_DELAY_MS = 5_000L

    /** Порядок поиска 2ГИС на устройстве. */
    private val PACKAGES = listOf(
        "ru.dublgis.dgismobile",
        "ru.dublgis.dgismobile4preview",
        "ru.dublgis.urbi"
    )

    private var service: IDashboardInformationService? = null
    private var appContext: Context? = null
    private var bound = false
    private var boundPackage: String? = null
    private var pkgCursor = 0

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connect(appContext) }

    private val callback = object : IUpdateCallback.Stub() {
        override fun onDataUpdated() {
            val ctx = appContext ?: return
            val svc = service ?: return
            try {
                val json = svc.dashboardInformationJSON
                val info = GisDashboardInfo.parse(json, apiVersion())
                if (info.unknownIconCodename) {
                    Log.w(TAG, "unknown maneuverIcon=${info.maneuverIcon}")
                    DebugStore.get(ctx).info("aidl new codename: ${info.maneuverIcon}")
                }
                val update = info.toNavUpdate()
                if (!info.navigationActive && NavFeed.isNavigating()) {
                    NavFeed.clearNavigating(ctx, "aidl")
                }
                NavFeed.publish(ctx, update, "aidl")
            } catch (e: Exception) {
                // 2GIS мог убить сервис/переустановиться посреди вызова
                Log.w(TAG, "onDataUpdated fail: ${e.message}")
                NavBus.setGisApiConnected(false)
                scheduleReconnect()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                onNullBinding(name)
                return
            }
            service = IDashboardInformationService.Stub.asInterface(binder)
            try {
                service?.registerDashboardInformationCallback(callback)
                NavBus.setGisApiConnected(true)
                handler.removeCallbacks(reconnectRunnable)
                DebugStore.get(appContext ?: return)
                    .info("2GIS API connected (${name?.packageName}) v${apiVersion()}")
                // Первый снимок сразу: callback приходит только на изменения
                callback.onDataUpdated()
            } catch (e: Exception) {
                Log.w(TAG, "register fail: ${e.message}")
                NavBus.setGisApiConnected(false)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 2GIS убит — Android сам переподключит, binder пересоздастся
            service = null
            NavBus.setGisApiConnected(false)
            DebugStore.get(appContext ?: return).info("2GIS API disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            // Обязаны сами unbind и перевызвать bindService
            service = null
            bound = false
            NavBus.setGisApiConnected(false)
            unbindQuietly()
            DebugStore.get(appContext ?: return).info("2GIS API binding died")
            scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            bound = false
            NavBus.setGisApiConnected(false)
            unbindQuietly()
            scheduleReconnect()
        }
    }

    fun connect(context: Context?) {
        val ctx = context ?: return
        appContext = ctx.applicationContext
        if (bound) return
        val pkg = resolvePackage(ctx)
        if (pkg == null) {
            Log.w(TAG, "2GIS not installed")
            return
        }
        val intent = Intent(ACTION).setPackage(pkg)
        val ok = try {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "bindService threw: ${e.message}")
            false
        }
        if (ok) {
            bound = true
            boundPackage = pkg
        } else {
            boundPackage = null
            scheduleReconnect()
        }
    }

    fun disconnect() {
        handler.removeCallbacks(reconnectRunnable)
        try {
            service?.unregisterDashboardInformationCallback(callback)
        } catch (_: Exception) {
        }
        service = null
        NavBus.setGisApiConnected(false)
        unbindQuietly()
        appContext = null
    }

    fun isConnected(): Boolean = service != null

    fun apiVersion(): Int = try {
        service?.apiVersion ?: 0
    } catch (_: Exception) {
        // getApiVersion нет в V0
        0
    }

    private fun unbindQuietly() {
        if (!bound) return
        bound = false
        try {
            appContext?.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        } catch (_: Exception) {
        }
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    /** Стабильный 2ГИС важнее беты; кэш успешного пакета сбрасываем при неудаче. */
    private fun resolvePackage(ctx: Context): String? {
        val pm = ctx.packageManager
        val start = pkgCursor.coerceIn(0, PACKAGES.lastIndex)
        for (i in PACKAGES.indices) {
            val pkg = PACKAGES[(start + i) % PACKAGES.size]
            val exists = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: Exception) {
                false
            }
            if (exists) {
                pkgCursor = (start + i) % PACKAGES.size
                return pkg
            }
        }
        return null
    }
}

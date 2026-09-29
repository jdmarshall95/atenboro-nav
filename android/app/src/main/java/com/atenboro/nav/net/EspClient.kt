package com.atenboro.nav.net

import android.content.Context
import com.atenboro.nav.model.NavUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class EspClient(
    var baseUrl: String = DEFAULT_BASE_URL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun health(context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        context?.let { EspNetwork.bindIfReachable(it) }
        try {
            val req = Request.Builder().url("$baseUrl/health").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendNav(update: NavUpdate, context: Context? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            context?.let { EspNetwork.bindIfReachable(it) }
            try {
                val body = update.toJson().toRequestBody(json)
                val req = Request.Builder()
                    .url("$baseUrl/nav")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(IllegalStateException("HTTP ${resp.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getDebug(context: Context? = null): Result<String> = withContext(Dispatchers.IO) {
        context?.let { EspNetwork.bindIfReachable(it) }
        try {
            val req = Request.Builder().url("$baseUrl/debug").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(body)
                else Result.failure(IllegalStateException("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun postDebug(payload: String, context: Context? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            context?.let { EspNetwork.bindIfReachable(it) }
            try {
                val body = payload.toRequestBody(json)
                val req = Request.Builder()
                    .url("$baseUrl/debug")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(IllegalStateException("HTTP ${resp.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun clearDebug(context: Context? = null): Result<Unit> = withContext(Dispatchers.IO) {
        context?.let { EspNetwork.bindIfReachable(it) }
        try {
            val req = Request.Builder().url("$baseUrl/debug").delete().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(IllegalStateException("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://${EspNetwork.ESP_HOST}"
    }
}

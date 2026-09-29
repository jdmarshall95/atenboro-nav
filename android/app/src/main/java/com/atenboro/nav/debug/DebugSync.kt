package com.atenboro.nav.debug

import android.content.Context
import com.atenboro.nav.net.EspClient
import org.json.JSONObject

class DebugSync(
    private val context: Context,
    private val esp: EspClient = EspClient(),
    private val store: DebugStore = DebugStore.get(context)
) {
    /**
     * 1) пушит локальные phone-события на плату
     * 2) забирает кольцевой лог платы
     * 3) сохраняет всё локально
     */
    suspend fun sync(): Result<String> {
        store.info("sync start")
        val pushBody = JSONObject()
            .put("events", store.pendingPhoneEvents(16))
            .toString()
        esp.postDebug(pushBody, context).onFailure {
            store.warn("push to board failed: ${it.message}")
        }

        val board = esp.getDebug(context)
        return board.fold(
            onSuccess = { json ->
                store.mergeBoardSnapshot(json)
                Result.success(json)
            },
            onFailure = { e ->
                store.error("pull from board failed: ${e.message}")
                Result.failure(e)
            }
        )
    }
}

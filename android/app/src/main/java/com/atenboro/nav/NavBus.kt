package com.atenboro.nav

import com.atenboro.nav.model.NavUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object NavBus {
    private val _update = MutableStateFlow(NavUpdate())
    val update: StateFlow<NavUpdate> = _update.asStateFlow()

    private val _dumpRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dumpRequest: SharedFlow<Unit> = _dumpRequest.asSharedFlow()

    private val _lastDumpPath = MutableStateFlow<String?>(null)
    val lastDumpPath: StateFlow<String?> = _lastDumpPath.asStateFlow()

    private val _a11yConnected = MutableStateFlow(false)
    val a11yConnected: StateFlow<Boolean> = _a11yConnected.asStateFlow()

    fun publish(update: NavUpdate) {
        _update.value = update
    }

    fun requestDump() {
        _dumpRequest.tryEmit(Unit)
    }

    fun setLastDumpPath(path: String?) {
        _lastDumpPath.value = path
    }

    fun setA11yConnected(connected: Boolean) {
        _a11yConnected.value = connected
    }
}

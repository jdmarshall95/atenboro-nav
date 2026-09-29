package com.atenboro.nav

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atenboro.nav.databinding.ActivityDebugBinding
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.debug.DebugSync
import com.atenboro.nav.net.EspClient
import com.atenboro.nav.net.EspNetwork
import kotlinx.coroutines.launch

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private lateinit var store: DebugStore
    private val esp = EspClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = DebugStore.get(this)
        binding.debugPath.text = store.sessionPath()
        renderLocal()

        binding.btnRefresh.setOnClickListener { renderLocal() }

        binding.btnSync.setOnClickListener {
            lifecycleScope.launch {
                EspNetwork.bindIfReachable(this@DebugActivity)
                store.info("manual sync tapped")
                val result = DebugSync(this@DebugActivity, esp, store).sync()
                renderLocal()
                Toast.makeText(
                    this@DebugActivity,
                    if (result.isSuccess) "Синхронизация OK" else "Ошибка: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnClearBoard.setOnClickListener {
            lifecycleScope.launch {
                EspNetwork.bindIfReachable(this@DebugActivity)
                val r = esp.clearDebug(this@DebugActivity)
                store.info(if (r.isSuccess) "board debug cleared" else "clear failed: ${r.exceptionOrNull()?.message}")
                renderLocal()
            }
        }
    }

    private fun renderLocal() {
        val lines = store.recent(250).joinToString("\n") { e ->
            val tag = when (e.origin) {
                "board" -> "B"
                "sync" -> "S"
                else -> "L"
            }
            "$tag ${e.line()}"
        }
        binding.debugLog.text = lines.ifBlank { "(пусто)" }
    }
}

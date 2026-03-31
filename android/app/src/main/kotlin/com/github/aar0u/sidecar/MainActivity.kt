package com.github.aar0u.sidecar

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.aar0u.sidecar.core.ConfigManager
import com.github.aar0u.sidecar.core.ProcessManager
import com.github.aar0u.sidecar.databinding.ActivityMainBinding
import com.github.aar0u.sidecar.databinding.ItemServiceCardBinding
import com.github.aar0u.sidecar.model.ServiceConfig
import com.github.aar0u.sidecar.ui.WebViewActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_LAUNCH_SERVICE = "com.github.aar0u.sidecar.ACTION_LAUNCH_SERVICE"
        const val EXTRA_SERVICE_ID = "extra_service_id"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentServices: List<ServiceConfig> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRefreshConfig.setOnClickListener {
            refreshRemoteConfig(force = true)
        }

        // 1. Initial display with cached or default config
        currentServices = ConfigManager.getCachedOrFallback(this)
        renderServices()
        updateDynamicShortcuts(currentServices)

        // 2. Handle intent if launched from shortcut
        handleLaunchIntent(intent)

        // 3. Fetch remote update
        refreshRemoteConfig(force = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_LAUNCH_SERVICE) {
            val serviceId = intent.getStringExtra(EXTRA_SERVICE_ID) ?: return
            val service = currentServices.firstOrNull { it.id == serviceId }
                ?: ConfigManager.getCachedOrFallback(this).firstOrNull { it.id == serviceId }
            if (service != null) {
                launchService(service)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderServices()
    }

    private fun refreshRemoteConfig(force: Boolean) {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = ConfigManager.loadServices(this@MainActivity, forceRefresh = force)
            binding.loadingIndicator.visibility = View.GONE

            result.onSuccess { services ->
                currentServices = services
                renderServices()
                updateDynamicShortcuts(services)
                if (force) {
                    Toast.makeText(this@MainActivity, "Config updated (${services.size} services)", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                currentServices = ConfigManager.getCachedOrFallback(this@MainActivity)
                renderServices()
                if (force) {
                    Toast.makeText(this@MainActivity, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateDynamicShortcuts(services: List<ServiceConfig>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
            val shortcuts = services.take(4).map { service ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_LAUNCH_SERVICE
                    putExtra(EXTRA_SERVICE_ID, service.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                ShortcutInfo.Builder(this, service.id)
                    .setShortLabel(service.name)
                    .setLongLabel(service.name)
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .build()
            }
            try {
                shortcutManager.dynamicShortcuts = shortcuts
            } catch (_: Exception) {}
        }
    }

    private fun renderServices() {
        binding.servicesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (service in currentServices) {
            val cardBinding = ItemServiceCardBinding.inflate(inflater, binding.servicesContainer, true)
            bindServiceCard(cardBinding, service)
        }
    }

    private fun bindServiceCard(card: ItemServiceCardBinding, service: ServiceConfig) {
        card.tvName.text = service.name
        card.tvDesc.text = service.description.ifEmpty { "No description" }
        card.tvMeta.text = "Port: ${service.port} | Binary: ${service.binaryName}"

        val isRunning = ProcessManager.isRunning(service.id)

        if (isRunning) {
            card.tvStatus.text = "RUNNING"
            card.tvStatus.setBackgroundResource(R.drawable.bg_status_running)
            card.tvStatus.setTextColor(Color.parseColor("#34C759"))

            card.btnStart.visibility = View.GONE
            card.btnOpen.visibility = View.VISIBLE
            card.btnStop.visibility = View.VISIBLE
        } else {
            card.tvStatus.text = "STOPPED"
            card.tvStatus.setBackgroundResource(R.drawable.bg_status_stopped)
            card.tvStatus.setTextColor(Color.parseColor("#8E8E93"))

            card.btnStart.visibility = View.VISIBLE
            card.btnStart.isEnabled = true
            card.btnStart.text = "Launch"
            card.btnOpen.visibility = View.GONE
            card.btnStop.visibility = View.GONE
        }

        card.btnOpen.setOnClickListener {
            openWebView(service)
        }

        card.btnStop.setOnClickListener {
            ProcessManager.stop(service.id)
            bindServiceCard(card, service)
        }

        ProcessManager.setOnStoppedListener(service.id) {
            runOnUiThread { bindServiceCard(card, service) }
        }

        card.btnStart.setOnClickListener {
            card.tvStatus.text = "STARTING…"
            card.tvStatus.setBackgroundResource(R.drawable.bg_status_starting)
            card.tvStatus.setTextColor(Color.parseColor("#FF9500"))
            card.btnStart.isEnabled = false

            lifecycleScope.launch {
                val result = ProcessManager.start(this@MainActivity, service) { progress ->
                    card.tvStatus.text = progress
                }

                result.onSuccess { url ->
                    bindServiceCard(card, service)
                    openWebView(service, url)
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    bindServiceCard(card, service)
                }
            }
        }
    }

    private fun launchService(service: ServiceConfig) {
        if (ProcessManager.isRunning(service.id)) {
            openWebView(service)
            return
        }

        Toast.makeText(this, "Starting ${service.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = ProcessManager.start(this@MainActivity, service) { /* progress */ }
            result.onSuccess { url ->
                openWebView(service, url)
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, "Failed to start ${service.name}: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openWebView(service: ServiceConfig, targetUrl: String = service.url) {
        startActivity(
            WebViewActivity.createIntent(
                this,
                targetUrl,
                service.name,
                service.keepScreenOn
            )
        )
    }
}

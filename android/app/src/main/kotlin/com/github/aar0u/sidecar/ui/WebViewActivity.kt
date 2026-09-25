package com.github.aar0u.sidecar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.aar0u.sidecar.ble.BleBridge
import com.github.aar0u.sidecar.databinding.ActivityWebviewBinding

class WebViewActivity : AppCompatActivity() {

    private var bleBridge: BleBridge? = null

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SERVICE_ID = "extra_service_id"
        const val EXTRA_KEEP_ALIVE = "extra_keep_alive"

        fun createIntent(
            context: Context,
            url: String,
            title: String,
            serviceId: String? = null,
            keepAlive: Boolean = false
        ): Intent = Intent(context, WebViewActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SERVICE_ID, serviceId)
            putExtra(EXTRA_KEEP_ALIVE, keepAlive)
        }
    }

    private lateinit var binding: ActivityWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupBackNavigation()

        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            showLoading("Loading $url…")
            binding.webView.loadUrl(url)
        } else {
            showError("No target URL specified")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                applyStatusBarStyleFromPage(view)
            }
        }

        val bridge = BleBridge(this, binding.webView)
        bleBridge = bridge
        binding.webView.addJavascriptInterface(bridge, "SidecarBle")
        if (!bridge.hasPermissions()) {
            bridge.requestPermissions()
        }
    }

    // Mirrors how browsers theme their own chrome from a page's declared <meta name="theme-color">,
    // so Sidecar never has to hardcode which services are light/dark in services.json.
    private fun applyStatusBarStyleFromPage(view: WebView?) {
        view?.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=\"theme-color\"]');return m?m.content:'';})();"
        ) { result ->
            val colorText = result?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" } ?: return@evaluateJavascript
            val color = runCatching { android.graphics.Color.parseColor(colorText) }.getOrNull() ?: return@evaluateJavascript
            val luminance = (0.299 * android.graphics.Color.red(color) +
                0.587 * android.graphics.Color.green(color) +
                0.114 * android.graphics.Color.blue(color)) / 255
            val lightBackground = luminance > 0.5
            WindowInsetsControllerCompat(window, binding.root).apply {
                isAppearanceLightStatusBars = lightBackground
                isAppearanceLightNavigationBars = lightBackground
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showLoading(message: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.webView.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.webView.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onStop() {
        super.onStop()
        bleBridge?.stopScan()
    }

    override fun onDestroy() {
        val serviceId = intent.getStringExtra(EXTRA_SERVICE_ID)
        val keepAlive = intent.getBooleanExtra(EXTRA_KEEP_ALIVE, false)
        android.util.Log.i("WebViewActivity", "onDestroy: serviceId=$serviceId, keepAlive=$keepAlive")
        if (!serviceId.isNullOrEmpty() && !keepAlive) {
            android.util.Log.i("WebViewActivity", "Service $serviceId is keepAlive=false, stopping process on Web UI exit...")
            com.github.aar0u.sidecar.core.ProcessManager.stop(serviceId)
        }
        bleBridge?.destroy()
        bleBridge = null
        binding.webView.destroy()
        super.onDestroy()
    }
}

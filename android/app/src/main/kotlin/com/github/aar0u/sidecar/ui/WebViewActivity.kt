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
import com.github.aar0u.sidecar.ble.ObeBleBridge
import com.github.aar0u.sidecar.databinding.ActivityWebviewBinding

class WebViewActivity : AppCompatActivity() {

    private var bleBridge: ObeBleBridge? = null

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_KEEP_SCREEN_ON = "extra_keep_screen_on"

        fun createIntent(
            context: Context,
            url: String,
            title: String,
            keepScreenOn: Boolean = true
        ): Intent = Intent(context, WebViewActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_KEEP_SCREEN_ON, keepScreenOn)
        }
    }

    private lateinit var binding: ActivityWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val keepScreenOn = intent.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, true)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

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
            }
        }

        val bridge = ObeBleBridge(this, binding.webView)
        bleBridge = bridge
        binding.webView.addJavascriptInterface(bridge, "SidecarBle")
        if (!bridge.hasPermissions()) {
            bridge.requestPermissions()
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

    override fun onDestroy() {
        bleBridge?.destroy()
        bleBridge = null
        binding.webView.destroy()
        super.onDestroy()
    }
}

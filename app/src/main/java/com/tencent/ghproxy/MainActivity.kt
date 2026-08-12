package com.tencent.ghproxy

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 主界面：
 *   - 顶部状态栏：设备局域网 IP + 端口（电脑访问用）
 *   - WebView：加载本地代理服务首页 http://127.0.0.1:8080/
 *
 * 代理服务为纯 Kotlin（ProxyServer），无任何 native 库，启动即用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private val baseUrl = "http://127.0.0.1:8080"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: ProxyServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        webView = findViewById(R.id.webView)

        // 顶部状态栏：局域网 IP + 端口
        val lanIp = getLocalIpv4() ?: "127.0.0.1"
        statusBar.text = getString(R.string.status_template, lanIp, 8080)

        // WebView 配置
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        webView.webViewClient = WebViewClient()

        // 后台线程启动代理服务（阻塞式 HTTP server）
        Thread {
            try {
                val s = ProxyServer(8080)
                server = s
                s.start(NanoHTTPD_SOCKET_READ_TIMEOUT, false)
                mainHandler.post { loadHome() }
            } catch (t: Throwable) {
                t.printStackTrace()
                mainHandler.post {
                    statusBar.text = getString(R.string.server_error, t.message ?: "unknown")
                }
            }
        }.apply {
            name = "ghproxy-server"
            start()
        }
    }

    private fun loadHome() {
        webView.loadUrl(baseUrl)
        // 若 WebView 加载失败，重试几次
        webView.postDelayed({
            if (webView.url == null || webView.url == "about:blank") {
                webView.loadUrl(baseUrl)
            }
        }, 2000)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        try { server?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    /** 枚举网络接口，返回第一个可用 IPv4（Wi-Fi 优先） */
    private fun getLocalIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var fallback: String? = null
        for (ni in interfaces) {
            try {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual || ni.isPointToPoint) continue
                val name = ni.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("wifi")) {
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                    }
                }
                if (fallback == null) {
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) fallback = addr.hostAddress
                    }
                }
            } catch (_: Exception) {
            }
        }
        return fallback
    }

    companion object {
        private const val NanoHTTPD_SOCKET_READ_TIMEOUT = 5000
    }
}
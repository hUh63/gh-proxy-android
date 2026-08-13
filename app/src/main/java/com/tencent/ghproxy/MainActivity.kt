package com.tencent.ghproxy

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 主界面（v1.0）：
 *   - 顶部状态栏显示：应用版本 / Android 版本 / 设备型号 / 局域网 IP / 端口
 *   - WebView 加载本地代理服务首页 http://127.0.0.1:8080/
 *   - 任何启动异常都会显示在屏幕上（不闪退），并写入 files/ghproxy_crash.log
 *
 * 本应用为纯 Kotlin（NanoHTTPD + OkHttp），无任何 native 库。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private val baseUrl = "http://127.0.0.1:8080"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: ProxyServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            installCrashHandler()
            initUi()
        } catch (t: Throwable) {
            // 任何启动异常：显示错误而非闪退
            showFatalError(t)
        }
    }

    /** 全局崩溃捕获：写入 files/ghproxy_crash.log（下次启动可读取展示） */
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                File(filesDir, "ghproxy_crash.log").writeText(
                    "时间: ${System.currentTimeMillis()}\n线程: ${thread.name}\n$trace"
                )
            } catch (_: Exception) {
            }
            Log.e("GHProxy", "未捕获异常", throwable)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initUi() {
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        webView = findViewById(R.id.webView)

        // 顶部状态栏：版本 / Android / 设备 / IP（全部可见，便于诊断）
        val lanIp = getLocalIpv4() ?: "127.0.0.1"
        val info = "v${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}"
        statusBar.text = getString(R.string.status_template, info, lanIp, 8080)

        // 读取上次崩溃日志（如有）追加显示
        val crash = try {
            val f = File(filesDir, "ghproxy_crash.log")
            if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            null
        }
        if (crash != null && crash.isNotBlank()) {
            statusBar.text = statusBar.text.toString() + "\n⚠️ 上次崩溃: " + crash.take(200)
        }

        // WebView 配置
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        webView.webViewClient = WebViewClient()

        // 下载监听：直接下载按钮/链接导航到文件时，交给系统浏览器或下载器
        webView.setDownloadListener { url, _: String?, _: String?, _: String?, _: Long ->
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {
                // 无可用浏览器时退回 WebView 内加载
                webView.loadUrl(url)
            }
        }

        // 后台线程启动代理服务（阻塞式 HTTP server）
        Thread {
            try {
                val s = ProxyServer(8080, applicationContext)
                server = s
                s.start(5000, false)
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
        webView.postDelayed({
            if (webView.url == null || webView.url == "about:blank") {
                webView.loadUrl(baseUrl)
            }
        }, 2000)
    }

    /** 显示致命错误（替代闪退） */
    private fun showFatalError(t: Throwable) {
        try {
            val trace = Log.getStackTraceString(t)
            File(filesDir, "ghproxy_crash.log").writeText(trace)
        } catch (_: Exception) {
        }
        val tv = TextView(this).apply {
            text = "⚠️ 应用启动失败（已捕获，未闪退）\n\n" +
                "应用: v${BuildConfig.VERSION_NAME}\n" +
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "设备: ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                t.javaClass.simpleName + ": " + (t.message ?: "") + "\n\n" +
                Log.getStackTraceString(t).take(2000)
            textSize = 13f
            setTextColor(0xFFF85149.toInt())
            setPadding(24, 24, 24, 24)
        }
        setContentView(tv)
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
}
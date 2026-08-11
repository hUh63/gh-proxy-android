package com.tencent.ghproxy

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 主界面：
 *   - 顶部状态栏：展示手机局域网 IP + 端口（电脑访问用）
 *   - WebView：加载本地 Python 代理服务首页 http://127.0.0.1:8080/
 *
 * Python 代理服务在此处显式启动（而非依赖 PyApplication 自动启动），
 * 以便捕获启动异常并展示错误信息（避免直接闪退）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private val baseUrl = "http://127.0.0.1:8080"
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        webView = findViewById(R.id.webView)

        // 顶部状态栏：显示局域网 IP + 端口
        val lanIp = getLocalIpv4() ?: "127.0.0.1"
        val port = 8080
        statusBar.text = getString(R.string.status_template, lanIp, port)

        // WebView 配置
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        webView.webViewClient = WebViewClient()

        // 后台线程启动 Python 代理服务（阻塞式），失败则在状态栏提示而非闪退
        startPythonServer()
    }

    /**
     * 在后台线程启动 Python 代理服务。
     * Chaquopy Python.start() 仅需一次；之后 app_main.start_server() 阻塞运行 uvicorn。
     */
    private fun startPythonServer() {
        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                Python.getInstance().getModule("app_main").callAttr("start_server")
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
        // UI 先行加载，Python 服务稍后就绪（秒级）
        loadHomeWithRetry()
    }

    private fun loadHomeWithRetry(retryLeft: Int = 15) {
        webView.loadUrl(baseUrl)
        // 简易重试：Python 服务启动通常需 1~3 秒；失败时最多重试 15 次（约 45s）
        webView.postDelayed({
            if (retryLeft > 0 && (webView.url == null || webView.url == "about:blank")) {
                loadHomeWithRetry(retryLeft - 1)
            }
        }, 3000)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 枚举所有网络接口，返回第一个可用的 IPv4（Wi-Fi / 蜂窝 / VPN 优先取物理接口）。
     */
    private fun getLocalIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var fallback: String? = null
        for (ni in interfaces) {
            try {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual || ni.isPointToPoint) continue
                val name = ni.name.lowercase()
                // Wi-Fi 优先
                if (name.startsWith("wlan") || name.startsWith("wifi")) {
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
                // 其他物理接口
                if (fallback == null) {
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            fallback = addr.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {
                // 单个接口枚举失败不影响整体
            }
        }
        return fallback
    }
}
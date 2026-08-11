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
import com.chaquo.python.Python
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 主界面：
 *   - 顶部状态栏：设备 ABI + 局域网 IP + 端口（或 Python 启动错误）
 *   - WebView：加载本地 Python 代理服务首页 http://127.0.0.1:8080/
 *
 * Python 由 App.onCreate 初始化（主线程）；此处仅后台线程启动 uvicorn 服务。
 * 启动失败会在状态栏显示错误而非闪退。
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

        // Python 初始化是否成功？
        val pyError = App.pythonStartError
        if (pyError != null) {
            // Python 启动失败：展示错误（替代闪退）
            statusBar.text = getString(R.string.python_error, App.deviceAbi, pyError)
            return  // 不加载 WebView
        }

        // 顶部状态栏：ABI + 局域网 IP + 端口
        val lanIp = getLocalIpv4() ?: "127.0.0.1"
        val port = 8080
        statusBar.text = getString(R.string.status_template, lanIp, port, App.deviceAbi)

        // WebView 配置
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        webView.webViewClient = WebViewClient()

        // 后台线程启动 uvicorn（阻塞式），失败时状态栏提示
        startPythonServer()
    }

    private fun startPythonServer() {
        Thread {
            try {
                Python.getInstance().getModule("app_main").callAttr("start_server")
            } catch (t: Throwable) {
                t.printStackTrace()
                val err = t.message ?: t.javaClass.simpleName
                mainHandler.post {
                    statusBar.text = getString(R.string.server_error, err)
                }
            }
        }.apply {
            name = "ghproxy-server"
            start()
        }
        loadHomeWithRetry()
    }

    private fun loadHomeWithRetry(retryLeft: Int = 20) {
        webView.loadUrl(baseUrl)
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
     * 枚举所有网络接口，返回第一个可用的 IPv4（Wi-Fi 优先）。
     */
    private fun getLocalIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var fallback: String? = null
        for (ni in interfaces) {
            try {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual || ni.isPointToPoint) continue
                val name = ni.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("wifi")) {
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
                if (fallback == null) {
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            fallback = addr.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return fallback
    }
}
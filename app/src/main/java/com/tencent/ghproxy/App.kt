package com.tencent.ghproxy

import android.app.Application
import android.os.Build
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * 自定义 Application：
 *  - 在主线程安全地初始化 Python（避免 PyApplication 启动失败导致闪退）
 *  - 记录设备 ABI 与启动错误（写入 files/ghproxy_crash.log，便于诊断）
 */
class App : Application() {

    companion object {
        private const val TAG = "GHProxy"

        /** Python 启动失败时的完整堆栈（null 表示成功） */
        @Volatile
        var pythonStartError: String? = null
            private set

        /** 设备支持的 ABI 列表（用于诊断） */
        @Volatile
        var deviceAbi: String = ""
            private set
    }

    override fun onCreate() {
        super.onCreate()
        deviceAbi = Build.SUPPORTED_ABIS.joinToString(", ")
        Log.i(TAG, "设备 ABI: $deviceAbi")

        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            Log.i(TAG, "Python 启动成功 (Python ${Python.getInstance().version})")
        } catch (t: Throwable) {
            pythonStartError = Log.getStackTraceString(t)
            Log.e(TAG, "Python.start 失败", t)
            // 写入文件便于离线诊断
            try {
                File(filesDir, "ghproxy_crash.log").writeText(pythonStartError!!)
            } catch (_: Exception) {
            }
        }
    }
}
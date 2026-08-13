package com.tencent.ghproxy

import android.content.Context
import android.os.Build
import android.provider.Settings
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Dns
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 纯 Kotlin 实现的 GitHub 下载加速代理（对齐 gh-proxy 全部逻辑）。
 *
 * 替代方案说明：
 *   - 此前用 Chaquopy 嵌入 Python 运行时的方案，其 2022 年预编译 native 库
 *     在 2026 年新 Android（16KB 内存页等）上启动即崩，且无法修复。
 *   - 本实现 100% 纯 Java/Kotlin：NanoHTTPD（HTTP 服务）+ OkHttp（上游转发），
 *     无任何 native 库，所有 Android 设备兼容。
 *
 * 功能：URL 校验 / blob→raw / 302 跟随 / 流式转发 / Range 断点续传 / 大小限制
 */
class ProxyServer(port: Int = 8080, private val context: Context? = null) : NanoHTTPD(port) {

    companion object {
        // ================= 首页 HTML（内嵌，无外部依赖） =================
        private val HOME_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="theme-color" content="#0d1117">
<title>gh-proxy · GitHub 下载加速</title>
<style>
  :root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --muted:#8b949e;
          --accent:#2f81f7; --green:#3fb950; --red:#f85149; }
  * { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  body { background:var(--bg); color:var(--text);
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
         min-height:100vh; display:flex; flex-direction:column; }
  .container { max-width:720px; width:100%; margin:0 auto; padding:24px 16px 40px; flex:1; }
  header { text-align:center; padding:24px 0 8px; }
  .logo { width:56px; height:56px; margin:0 auto 12px; background:linear-gradient(135deg,var(--accent),#a371f7);
          border-radius:14px; display:flex; align-items:center; justify-content:center;
          font-size:28px; font-weight:800; color:#fff; }
  h1 { font-size:22px; font-weight:700; }
  .sub { color:var(--muted); font-size:13px; margin-top:6px; line-height:1.6; }
  .card { background:var(--card); border:1px solid var(--border); border-radius:10px; padding:16px; margin-top:20px; }
  .card label { display:block; font-size:13px; color:var(--muted); margin-bottom:8px; font-weight:600; }
  .input-wrap { display:flex; gap:8px; flex-wrap:wrap; }
  .input-wrap input { flex:1; min-width:200px; background:var(--bg); border:1px solid var(--border);
                      border-radius:8px; color:var(--text); padding:12px 14px; font-size:15px; outline:none; }
  .input-wrap input:focus { border-color:var(--accent); }
  .btn { background:var(--accent); color:#fff; border:none; border-radius:8px; padding:12px 20px;
         font-size:15px; font-weight:600; cursor:pointer; }
  .btn.secondary { background:transparent; border:1px solid var(--border); color:var(--text); }
  .btn.green { background:var(--green); }
  .btn.block { width:100%; margin-top:10px; padding:14px; font-size:16px; }
  #result { display:none; margin-top:14px; }
  .result-box { background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:12px;
                font-size:13px; word-break:break-all; line-height:1.7; user-select:all; }
  .result-actions { display:flex; gap:8px; margin-top:10px; }
  .result-actions .btn { flex:1; padding:11px; font-size:14px; }
  .examples { margin-top:22px; }
  .examples h3 { font-size:14px; color:var(--muted); margin-bottom:10px; font-weight:600; }
  .example { background:var(--card); border:1px solid var(--border); border-radius:8px; padding:10px 12px;
             margin-bottom:8px; font-size:12.5px; cursor:pointer; color:var(--muted); word-break:break-all; }
  .example:hover { border-color:var(--accent); color:var(--text); }
  .tips { margin-top:22px; padding:14px; border-radius:10px; background:rgba(63,185,80,.08);
          border:1px solid rgba(63,185,80,.25); font-size:13px; color:var(--muted); line-height:1.8; }
  .tips b { color:var(--green); }
  footer { text-align:center; color:#484f58; font-size:12px; padding:16px 0 24px; }
  .err { color:var(--red); font-size:12.5px; margin-top:8px; display:none; }
</style>
</head>
<body>
<div class="container">
  <header>
    <div class="logo">GH</div>
    <h1>GitHub 下载加速</h1>
    <p class="sub">手机自部署 · 加速 release / 源码包 / 项目文件<br>电脑连同一 Wi-Fi 也可使用</p>
  </header>
  <div class="card">
    <label>粘贴 GitHub 链接（release / APK / 源码包均可）</label>
    <div class="input-wrap">
      <input id="url" placeholder="https://github.com/user/repo/releases/download/v1.0/app.apk" autocomplete="off" spellcheck="false">
      <button class="btn" id="go">生成加速链接</button>
    </div>
    <div class="err" id="err"></div>
    <div id="result">
      <label>加速下载链接</label>
      <div class="result-box" id="resultUrl"></div>
      <div class="result-actions">
        <button class="btn secondary" onclick="copyLink()">复制链接</button>
        <button class="btn green" onclick="download()">直接下载</button>
      </div>
    </div>
  </div>
  <div class="examples">
    <h3>点一下试试 👇</h3>
    <div class="example" onclick="fill(this)">release 文件（APK）：https://github.com/aaaaxy/aaaaxy/releases/download/v1.5.4/Aaaaxy_1.5.4.apk</div>
    <div class="example" onclick="fill(this)">release 源码包：https://github.com/hunshcn/gh-proxy/archive/refs/heads/master.zip</div>
    <div class="example" onclick="fill(this)">分支源码包：https://github.com/hunshcn/gh-proxy/archive/master.tar.gz</div>
    <div class="example" onclick="fill(this)">项目文件：https://github.com/hunshcn/gh-proxy/blob/master/README.md</div>
    <div class="example" onclick="fill(this)">raw 直链：https://raw.githubusercontent.com/hunshcn/gh-proxy/master/README.md</div>
  </div>
  <div class="card">
    <label>上游模式（决定下载速度上限）</label>
    <div class="result-actions">
      <button class="btn secondary" id="mAccel" onclick="setMode('accel')">通过加速站（推荐）</button>
      <button class="btn secondary" id="mDirect" onclick="setMode('direct')">直连 GitHub</button>
    </div>
    <div class="err" id="modeInfo" style="display:block;color:var(--green);margin-top:8px"></div>
  </div>
  <div class="tips">
    <b>使用说明</b><br>
    ① 电脑与手机连<b>同一 Wi-Fi</b>，浏览器访问 <b>http://手机IP:8080</b><br>
    ② 或直接拼接前缀：<b>http://手机IP:8080</b>/https://github.com/...<br>
    ③ 支持断点续传，可用迅雷 / IDM / 浏览器直接下载。<br>
    <b>💡 推荐「通过加速站」</b>：海外服务器中转 + 国内 CDN 分发，<br>
    绕开大陆对 GitHub 的直连限速，速度通常快 10-100 倍。<br>
    <b>⚠️ 直连报错（无法连接/超时）</b>：说明当前网络直连 GitHub 被限速/阻断，<br>
    请切换为「通过加速站」模式；加速站均不可用时再考虑开 Clash。
  </div>
</div>
<footer>gh-proxy-android · Kotlin 原生 · MIT License</footer>
<script>
  const RX = /^(https?:\/\/)?(github\.com\/[^/]+\/[^/]+\/(releases|archive|blob|raw|tags|info|git-)|raw\.(githubusercontent|github)\.com\/|gist\.(githubusercontent|github)\.com\/)/i;
  function normalize(u){ u=u.trim(); if(!u) return ''; if(!/^https?:\/\//i.test(u)) u='https://'+u; return u; }
  function fill(el){ document.getElementById('url').value=el.textContent.replace(/^[^：:]*[：:]\s*/,''); }
  function generate(){
    const u=normalize(document.getElementById('url').value), err=document.getElementById('err'), res=document.getElementById('result');
    if(!u){ err.textContent='请输入 GitHub 链接'; err.style.display='block'; res.style.display='none'; return; }
    if(!RX.test(u)){ err.textContent='仅支持 GitHub release / archive / blob / raw / gist 链接'; err.style.display='block'; res.style.display='none'; return; }
    err.style.display='none';
    document.getElementById('resultUrl').textContent=location.origin+'/'+u;
    res.style.display='block';
  }
  function copyLink(){
    const t=document.getElementById('resultUrl').textContent;
    // WebView 非安全上下文没有 navigator.clipboard，用 execCommand 兼容方案
    try {
      const ta=document.createElement('textarea');
      ta.value=t; ta.style.position='fixed'; ta.style.opacity='0';
      document.body.appendChild(ta); ta.select(); ta.setSelectionRange(0,99999);
      document.execCommand('copy'); document.body.removeChild(ta);
      showTip('已复制到剪贴板');
    } catch(e) {
      if(navigator.clipboard && navigator.clipboard.writeText){
        navigator.clipboard.writeText(t).then(()=>showTip('已复制'));
      } else { showTip('复制失败，请长按链接手动复制'); }
    }
  }
  function download(){ window.location.href=document.getElementById('resultUrl').textContent; }
  function showTip(m){
    var d=document.createElement('div');
    d.textContent=m;
    d.style.cssText='position:fixed;left:50%;bottom:60px;transform:translateX(-50%);background:#fff;color:#0d1117;padding:8px 16px;border-radius:20px;font-size:13px;z-index:99;';
    document.body.appendChild(d);
    setTimeout(function(){d.remove();},1500);
  }
  function setMode(m){
    fetch('/api/mode?set='+m).then(function(r){return r.json();}).then(function(d){
      refreshMode();
      showTip(m==='accel'?'已切换：通过加速站':'已切换：直连 GitHub');
    }).catch(function(){ showTip('切换失败'); });
  }
  function refreshMode(){
    fetch('/api/mode').then(function(r){return r.json();}).then(function(d){
      var accelBtn=document.getElementById('mAccel'), directBtn=document.getElementById('mDirect');
      accelBtn.style.borderColor = d.mode==='accel' ? 'var(--accent)' : 'var(--border)';
      accelBtn.style.color = d.mode==='accel' ? '#fff' : '';
      accelBtn.style.background = d.mode==='accel' ? 'var(--accent)' : 'transparent';
      directBtn.style.borderColor = d.mode==='direct' ? 'var(--accent)' : 'var(--border)';
      directBtn.style.color = d.mode==='direct' ? '#fff' : '';
      directBtn.style.background = d.mode==='direct' ? 'var(--accent)' : 'transparent';
      var info=document.getElementById('modeInfo');
      if(d.mode==='accel'){
        info.textContent = d.accel==='none' ? '⚠️ 加速站均不可用，将退回直连' : '当前：通过加速站（'+d.accel.replace('https://','')+'）';
      } else {
        info.textContent = '当前：直连 GitHub（受网络限制，慢/断属正常）';
      }
    }).catch(function(){});
  }
  refreshMode();
  document.getElementById('go').onclick=generate;
  document.getElementById('url').addEventListener('keydown',e=>{ if(e.key==='Enter') generate(); });
</script>
</body>
</html>
""".trimIndent()

        private val FAVICON = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#2f81f7"/><stop offset="1" stop-color="#a371f7"/></linearGradient></defs>
<rect width="64" height="64" rx="14" fill="url(#g)"/>
<text x="32" y="42" text-anchor="middle" font-family="sans-serif" font-weight="800" font-size="28" fill="#fff">GH</text>
</svg>""".trimIndent()

        // ================= URL 匹配规则（与 gh-proxy 一致） =================
        private val EXP_RELEASE = Pattern.compile(
            "^(?:https?://)?github\\.com/[^/]+/[^/]+/(?:releases|archive)/.*$", Pattern.CASE_INSENSITIVE)
        private val EXP_BLOB = Pattern.compile(
            "^(?:https?://)?github\\.com/[^/]+/[^/]+/(?:blob|raw)/.*$", Pattern.CASE_INSENSITIVE)
        private val EXP_GIT = Pattern.compile(
            "^(?:https?://)?github\\.com/[^/]+/[^/]+/(?:info|git-).*$", Pattern.CASE_INSENSITIVE)
        private val EXP_RAW = Pattern.compile(
            "^(?:https?://)?raw\\.(?:githubusercontent|github)\\.com/[^/]+/[^/]+/.+/.+$", Pattern.CASE_INSENSITIVE)
        private val EXP_GIST = Pattern.compile(
            "^(?:https?://)?gist\\.(?:githubusercontent|github)\\.com/[^/]+/.+/.+$", Pattern.CASE_INSENSITIVE)
        private val EXP_TAGS = Pattern.compile(
            "^(?:https?://)?github\\.com/[^/]+/[^/]+/tags.*$", Pattern.CASE_INSENSITIVE)

        private val ALL_EXPS = listOf(EXP_RELEASE, EXP_BLOB, EXP_GIT, EXP_RAW, EXP_GIST, EXP_TAGS)

        private const val SIZE_LIMIT = 5L * 1024 * 1024 * 1024   // 5GB
        private const val MAX_REDIRECTS = 5

        /** 上游请求不需要透传的请求头 */
        private val DROP_HEADERS = setOf(
            "host", "connection", "accept-encoding", "transfer-encoding",
            "content-length", "content-encoding", "x-forwarded-for", "x-forwarded-proto"
        )

        /** 透传给客户端的响应头白名单之外的额外头 */
        private val EXTRA_RESPONSE_HEADERS = setOf(
            "content-disposition", "content-range", "accept-ranges", "etag",
            "last-modified", "cache-control", "expires", "date", "location"
        )

        /** 必须走代理的 GitHub 主域名（大陆 DNS 污染，直连解析不了） */
        private val PROXY_HOSTS = setOf(
            "github.com", "api.github.com", "codeload.github.com",
            "raw.githubusercontent.com", "gist.github.com", "gist.githubusercontent.com",
            "github.githubassets.com", "avatars.githubusercontent.com"
        )

        /** 下载 CDN 域名：能直连时走直连（绕开慢速代理节点，这才是加速的意义） */
        private val CDN_HOSTS = setOf(
            "objects.githubusercontent.com", "github-releases.githubusercontent.com",
            "user-images.githubusercontent.com", "github-cloud.s3.amazonaws.com"
        )

        /** 公共 gh-proxy 加速站列表（海外中转，国内可达；App 上游可选用） */
        private val ACCEL_HOSTS = listOf(
            "https://gh-proxy.com",
            "https://ghproxy.net",
            "https://mirror.ghproxy.com",
            "https://gh.llkk.cc"
        )
    }

    // CDN 能否直连（启动时异步探测；false=CDN 也走代理）
    @Volatile
    private var cdnDirectOk = false

    // 加速站模式：true=所有上游请求通过公共加速站中转（绕开大陆对 GitHub 的限速）
    @Volatile
    private var accelMode: Boolean = loadMode()

    // 启动探测选中的可用加速站前缀；null=加速站都不可用
    @Volatile
    private var accelPrefix: String? = null

    private fun loadMode(): Boolean =
        context?.getSharedPreferences("ghproxy", Context.MODE_PRIVATE)
            ?.getBoolean("accel", true) ?: true

    private fun saveMode() {
        context?.getSharedPreferences("ghproxy", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("accel", accelMode)?.apply()
    }

    // DoH 专用 client（目标是 IP/国内域名，无需自定义 DNS，避免递归）
    private val dnsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    init {
        // 异步探测 CDN 直连可达性：仅检测到系统代理时才有分流意义
        Thread {
            cdnDirectOk = probeCdnDirect()
        }.apply {
            name = "cdn-probe"
            isDaemon = true
            start()
        }
        // 异步探测可用的公共加速站（accel 模式下使用）
        Thread {
            accelPrefix = probeAccel()
        }.apply {
            name = "accel-probe"
            isDaemon = true
            start()
        }
    }

    /** 探测可用的加速站：逐个 HEAD，第一个可达的作为上游前缀 */
    private fun probeAccel(): String? {
        val probe = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        for (host in ACCEL_HOSTS) {
            try {
                probe.newCall(OkRequest.Builder().url("$host/").head().build()).execute().close()
                return host
            } catch (_: Exception) {
                // 试下一个
            }
        }
        return null
    }

    /** 直连探测 CDN（不经过代理）：TCP+TLS 能通即视为直连可用 */
    private fun probeCdnDirect(): Boolean {
        if (readSystemProxy() == null) return false
        return try {
            val probe = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY) // 强制直连探测
                .build()
            probe.newCall(OkRequest.Builder().url("https://objects.githubusercontent.com/").head().build()).execute().close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        // 关键：读取安卓系统代理（Clash/VPN 设置的全局代理）。
        // OkHttp 默认不读系统代理，导致开了 Clash 也直连、DNS 被污染而解析失败。
        .proxySelector(systemProxySelector())
        // 关键：自定义 DNS。系统 DNS 可能被污染（解析失败）或被 Clash fake-ip
        // 劫持（返回 198.18.x.x 假 IP，直连必失败）。这里过滤 fake-ip，
        // 失败时用公共 DoH（阿里/腾讯）解析真实 IP。
        .dns(customDns())
        .build()

    // ==================================================================
    // 自定义 DNS：过滤 Clash fake-ip，失败时走公共 DoH
    // ==================================================================
    private fun customDns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val sys = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (_: UnknownHostException) {
                emptyList()
            }
            val real = sys.filterNot { isFakeIp(it.hostAddress ?: "") }
            if (real.isNotEmpty()) {
                return real
            }
            return dohLookup(hostname)?.let {
                if (it.isNotEmpty()) it
                else throw UnknownHostException(hostname)
            } ?: throw UnknownHostException(hostname)
        }
    }

    /** Clash fake-ip 默认网段 198.18.0.0/15 */
    private fun isFakeIp(ip: String): Boolean =
        ip.startsWith("198.18.") || ip.startsWith("198.19.")

    /** 公共 DoH 解析（阿里/腾讯），返回真实 A 记录 IP */
    private fun dohLookup(host: String): List<InetAddress>? {
        val urls = listOf(
            "https://223.5.5.5/resolve?name=$host&type=A",
            "https://dns.tencent.com/dns-query?name=$host&type=A&ct=application/dns-json"
        )
        for (u in urls) {
            try {
                val resp = dnsClient.newCall(OkRequest.Builder().url(u).build()).execute()
                val body = resp.body?.string()
                resp.close()
                if (body.isNullOrBlank()) continue
                val obj = org.json.JSONObject(body)
                val answers = obj.optJSONArray("Answer") ?: continue
                val list = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val a = answers.getJSONObject(i)
                    if (a.optInt("type") == 1) { // A 记录
                        val ip = a.optString("data")
                        if (ip.isNotBlank()) list.add(InetAddress.getByName(ip))
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
                // 尝试下一个 DoH
            }
        }
        return null
    }

    // ==================================================================
    // 智能分流：GitHub 主域名走代理（防 DNS 污染），CDN 域名直连（提速）
    // ==================================================================
    private fun systemProxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): MutableList<Proxy> {
            val host = uri?.host?.lowercase() ?: return mutableListOf(Proxy.NO_PROXY)
            val sysProxy = readSystemProxy()
            if (sysProxy == null) return mutableListOf(Proxy.NO_PROXY) // 无代理：全直连

            val useProxy = when {
                host in PROXY_HOSTS -> true                      // 主域名：必须走代理
                host in CDN_HOSTS -> !cdnDirectOk                // CDN：直连可用则直连
                host.endsWith(".githubusercontent.com") || host.endsWith(".github.com") -> true
                else -> true                                     // 其他保守走代理
            }
            return mutableListOf(if (useProxy) sysProxy else Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    private fun readSystemProxy(): Proxy? {
        val ctx = context ?: return null
        return try {
            val s = if (Build.VERSION.SDK_INT >= 26) {
                Settings.Global.getString(ctx.contentResolver, Settings.Global.HTTP_PROXY)
            } else {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.HTTP_PROXY)
            }
            if (s.isNullOrBlank()) null
            else {
                val idx = s.lastIndexOf(':')
                if (idx <= 0) return null
                val host = s.substring(0, idx).trim()
                val port = s.substring(idx + 1).trim().toIntOrNull()
                if (host.isNotBlank() && port != null && port in 1..65535) {
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================================================================
    // NanoHTTPD 入口
    // ==================================================================
    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.OPTIONS -> newFixedLengthResponse(Status.NO_CONTENT, null, null).apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                    addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,TRACE,DELETE,HEAD,OPTIONS")
                    addHeader("Access-Control-Allow-Headers", "*")
                    addHeader("Access-Control-Max-Age", "1728000")
                }
                session.uri == "/" -> {
                    // NanoHTTPD 无 query 时 queryParameterString 为 null，必须判空
                    val q = session.queryParameterString ?: ""
                    if (q.startsWith("q=")) {
                        // git clone 支持：?q=github.com/xxx -> 301
                        newFixedLengthResponse(Status.REDIRECT, null, null).apply {
                            addHeader("Location", "/" + q.substring(2))
                        }
                    } else {
                        newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", HOME_HTML)
                    }
                }
                session.uri == "/favicon.ico" ->
                    newFixedLengthResponse(Status.OK, "image/svg+xml", FAVICON)
                session.uri == "/api/mode" -> handleMode(session)
                else -> handleProxy(session)
            }
        } catch (e: UnknownHostException) {
            err(502, "DNS 解析失败：${e.message}\n当前网络无法访问 GitHub，请开启 Clash/代理后重试")
        } catch (e: ConnectException) {
            err(502, "连接失败：${e.message}\n当前网络无法直连 GitHub，请开启 Clash/代理后重试")
        } catch (e: SocketTimeoutException) {
            err(504, "连接超时：${e.message}\n网络过慢或代理节点不稳定，请换节点后重试")
        } catch (e: IOException) {
            err(502, "上游连接中断：${e.message}")
        } catch (e: Exception) {
            err(500, "server error: ${e.message}")
        }
    }

    // ==================================================================
    // 代理逻辑
    // ==================================================================
    /** 上游模式查询/切换：GET /api/mode 或 /api/mode?set=accel|direct */
    private fun handleMode(session: IHTTPSession): Response {
        val q = session.queryParameterString ?: ""
        if (q.startsWith("set=")) {
            when (q.substring(4)) {
                "accel" -> { accelMode = true; saveMode() }
                "direct" -> { accelMode = false; saveMode() }
            }
        }
        val mode = if (accelMode) "accel" else "direct"
        val accel = if (accelMode) (accelPrefix ?: "none") else "-"
        return newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", "{\"mode\":\"$mode\",\"accel\":\"$accel\"}")
    }

    private fun handleProxy(session: IHTTPSession): Response {
        var target = session.uri.removePrefix("/")
        if (!target.startsWith("http")) target = "https://$target"
        // 兼容 // 被折叠为 /
        if (target.startsWith("https:/") && !target.startsWith("https://")) {
            target = "https://" + target.substring(7)
        }

        if (!checkUrl(target)) {
            return err(403, "Invalid input. Only GitHub release / archive / raw / blob / gist links are supported.")
        }

        // blob -> raw 直链
        if (EXP_BLOB.matcher(target).matches()) {
            target = target.replace("/blob/", "/raw/", ignoreCase = false)
        }

        return forward(session, target, MAX_REDIRECTS)
    }

    private fun checkUrl(url: String): Boolean = ALL_EXPS.any { it.matcher(url).matches() }

    /**
     * 转发单个 URL。重定向时：
     *   - Location 仍是受支持的 GitHub 链接 → 302 给客户端继续走代理
     *   - 外部（objects.githubusercontent.com 等）→ 服务端跟随
     * accel 模式下：上游统一走公共加速站（gh-proxy 格式：加速站前缀 + 完整 URL）
     */
    private fun forward(session: IHTTPSession, url: String, redirectsLeft: Int): Response {
        // 构造上游地址：accel 模式下 GitHub 主域名走加速站（海外中转），
        // CDN 域名（objects.githubusercontent.com 等）加速站不支持，仍走直连/代理分流
        val isCdnHost = try { URI(url).host?.lowercase() in CDN_HOSTS } catch (_: Exception) { false }
        val useAccel = accelMode && accelPrefix != null && !isCdnHost
        val upstreamUrl = if (useAccel) "$accelPrefix/$url" else url
        val rb = OkRequest.Builder().url(upstreamUrl)

        // 透传请求头（Range / User-Agent / Accept 等）
        session.headers.forEach { (k, v) ->
            if (k.lowercase() !in DROP_HEADERS) {
                try { rb.header(k, v) } catch (_: Exception) {}
            }
        }

        // 方法 + body（下载场景以 GET/HEAD 为主；POST 尽力透传）
        val method = session.method.name
        if (method == "POST" || method == "PUT" || method == "PATCH") {
            val bytes = readRequestBody(session)
            rb.method(method, bytes.toRequestBody(null))
        }

        val resp = client.newCall(rb.build()).execute()

        // ---------- 加速站拒绝类错误（4xx）----------
        // 公共加速站只允许文件下载，拒绝网页内容（仓库主页/releases 页面等），
        // 此时给出中文指引而不是透传英文报错
        if (useAccel && resp.code in 400..499) {
            val msg = try {
                resp.body?.string()?.take(1024) ?: ""
            } catch (_: Exception) {
                ""
            }
            resp.close()
            val friendly = if (msg.contains("not allowed", ignoreCase = true) ||
                msg.contains("downloads only", ignoreCase = true)) {
                "加速站拒绝了该链接：仅支持文件下载，不支持网页内容。\n" +
                    "请粘贴具体文件链接（如 .../releases/download/xxx.apk 或 .../archive/xxx.zip），\n" +
                    "不要粘贴仓库主页或 releases 页面链接。"
            } else {
                "加速站返回错误 ${resp.code}：${msg.ifBlank { "无详情" }}"
            }
            return err(502, friendly)
        }

        // ---------- 重定向 ----------
        if (resp.isRedirect) {
            val loc = resp.header("Location")
            val statusCode = resp.code
            resp.close()
            if (loc != null) {
                if (checkUrl(loc)) {
                    return newFixedLengthResponse(Status.lookup(statusCode), null, null).apply {
                        addHeader("Location", "/$loc")
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                }
                if (redirectsLeft <= 0) return err(502, "Too many redirects.")
                return forward(session, loc, redirectsLeft - 1)
            }
        }

        // ---------- 大小限制 ----------
        val len = resp.header("Content-Length")?.toLongOrNull()
        if (len != null && len > SIZE_LIMIT) {
            resp.close()
            return newFixedLengthResponse(Status.REDIRECT, null, null).apply {
                addHeader("Location", url)
            }
        }

        // ---------- 流式转发 ----------
        val body = resp.body ?: run {
            resp.close()
            return err(502, "empty upstream body")
        }
        // 包装输入流：NanoHTTPD 消费/关闭流时同步关闭上游 OkHttp 连接
        val upstream = body.byteStream()
        val wrapped = object : InputStream() {
            override fun read(): Int = upstream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = upstream.read(b, off, len)
            override fun close() {
                try { upstream.close() } catch (_: Exception) {}
                resp.close()
            }
        }
        // 优先定长响应（透传 Content-Length）：系统下载管理器对 chunked 支持差，
        // 没有 Content-Length 会导致进度条异常/Http Data Error/无法断点续传。
        // 仅当上游未提供 Content-Length 时才退化为 chunked。
        val ct = resp.header("Content-Type")
        val response = if (len != null) {
            newFixedLengthResponse(Status.lookup(resp.code), ct, wrapped, len)
        } else {
            newChunkedResponse(Status.lookup(resp.code), ct, wrapped)
        }
        EXTRA_RESPONSE_HEADERS.forEach { h ->
            resp.header(h)?.let { response.addHeader(h, it) }
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Expose-Headers", "*")
        return response
    }

    /** 读取 POST body（NanoHTTPD 中未调用 parseBody 时 getInputStream 可用） */
    private fun readRequestBody(session: IHTTPSession): ByteArray {
        return try {
            val stream = session.getInputStream()
            if (stream == null) ByteArray(0) else stream.readBytes()
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun err(code: Int, msg: String): Response =
        newFixedLengthResponse(Status.lookup(code), "text/plain; charset=utf-8", msg)
}
package com.tencent.ghproxy

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import java.io.InputStream
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
class ProxyServer(port: Int = 8080) : NanoHTTPD(port) {

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
  <div class="tips">
    <b>使用说明</b><br>
    ① 电脑与手机连<b>同一 Wi-Fi</b>，浏览器访问 <b>http://手机IP:8080</b><br>
    ② 或直接拼接前缀：<b>http://手机IP:8080</b>/https://github.com/...<br>
    ③ 支持断点续传，可用迅雷 / IDM / 浏览器直接下载。
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
    if(navigator.clipboard&&navigator.clipboard.writeText){ navigator.clipboard.writeText(t); }
    else { const ta=document.createElement('textarea'); ta.value=t; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta); }
  }
  function download(){ window.open(document.getElementById('resultUrl').textContent,'_blank'); }
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
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

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
                    val q = session.queryParamsString
                    if (q.startsWith("q=")) {
                        // git clone 支持：?q=github.com/xxx -> 301
                        newFixedLengthResponse(Status.MOVED_PERMANENTLY, null, null).apply {
                            addHeader("Location", "/" + q.substring(2))
                        }
                    } else {
                        newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", HOME_HTML)
                    }
                }
                session.uri == "/favicon.ico" ->
                    newFixedLengthResponse(Status.OK, "image/svg+xml", FAVICON)
                else -> handleProxy(session)
            }
        } catch (e: Exception) {
            err(500, "server error: ${e.message}")
        }
    }

    // ==================================================================
    // 代理逻辑
    // ==================================================================
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
     */
    private fun forward(session: IHTTPSession, url: String, redirectsLeft: Int): Response {
        val rb = OkRequest.Builder().url(url)

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

        // ---------- 重定向 ----------
        if (resp.isRedirect) {
            val loc = resp.header("Location")
            resp.close()
            if (loc != null) {
                if (checkUrl(loc)) {
                    return newFixedLengthResponse(Status.lookup(resp.code()), null, null).apply {
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
        val response = newChunkedResponse(Status.lookup(resp.code()), resp.header("Content-Type"), body.byteStream())
        EXTRA_RESPONSE_HEADERS.forEach { h ->
            resp.header(h)?.let { response.addHeader(h, it) }
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Expose-Headers", "*")
        // 流读完自动关闭上游连接
        response.addCloseConnectionListener { resp.close() }
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
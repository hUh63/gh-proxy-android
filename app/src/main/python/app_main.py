# -*- coding: utf-8 -*-
"""
Android 入口 —— 由 Chaquopy (PyApplication) 在应用启动时调用 start_server()。

设计：
  1. 把首页 HTML 以字符串形式注入到 main 模块（避开 Android 资源/文件系统读取）
  2. 用单进程 uvicorn 启动 FastAPI 应用（Android 不支持 multiprocessing）
  3. 监听 0.0.0.0:8080，手机自己作为代理服务器；同 Wi-Fi 的电脑访问 http://<手机IP>:8080 即可
"""

# 把当前目录加入 sys.path，确保 main / config / proxy 能正常 import
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import main as _main  # noqa: E402

# ----------------------------------------------------------------------
# 首页 HTML（与 PC 版一致，仅将"电脑 IP"改为"手机 IP"，并加 Android 适配样式）
# ----------------------------------------------------------------------
INDEX_HTML = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="theme-color" content="#0d1117">
<title>gh-proxy · GitHub 下载加速</title>
<style>
  :root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --muted:#8b949e;
          --accent:#2f81f7; --accent-hover:#1f6feb; --green:#3fb950; --red:#f85149; --radius:10px; }
  * { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  body { background:var(--bg); color:var(--text);
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
         min-height:100vh; display:flex; flex-direction:column; padding-top:env(safe-area-inset-top); padding-bottom:env(safe-area-inset-bottom); }
  .container { max-width:720px; width:100%; margin:0 auto; padding:24px 16px 40px; flex:1; }
  header { text-align:center; padding:24px 0 8px; }
  .logo { width:56px; height:56px; margin:0 auto 12px;
          background:linear-gradient(135deg,var(--accent),#a371f7); border-radius:14px;
          display:flex; align-items:center; justify-content:center;
          font-size:28px; font-weight:800; color:#fff; box-shadow:0 8px 24px rgba(47,129,247,.25); }
  h1 { font-size:22px; font-weight:700; }
  .sub { color:var(--muted); font-size:13px; margin-top:6px; line-height:1.6; }
  .card { background:var(--card); border:1px solid var(--border); border-radius:var(--radius); padding:16px; margin-top:20px; }
  .card label { display:block; font-size:13px; color:var(--muted); margin-bottom:8px; font-weight:600; }
  .input-wrap { display:flex; gap:8px; flex-wrap:wrap; }
  .input-wrap input { flex:1; min-width:200px; background:var(--bg); border:1px solid var(--border);
                      border-radius:8px; color:var(--text); padding:12px 14px; font-size:15px; outline:none;
                      transition:border-color .15s; }
  .input-wrap input:focus { border-color:var(--accent); }
  .input-wrap input::placeholder { color:#484f58; }
  .btn { background:var(--accent); color:#fff; border:none; border-radius:8px; padding:12px 20px;
         font-size:15px; font-weight:600; cursor:pointer; transition:background .15s;
         display:inline-flex; align-items:center; justify-content:center; gap:6px; }
  .btn:hover { background:var(--accent-hover); }
  .btn:active { transform:scale(.97); }
  .btn.secondary { background:transparent; border:1px solid var(--border); color:var(--text); font-weight:500; }
  .btn.secondary:hover { border-color:var(--accent); color:var(--accent); }
  .btn.green { background:var(--green); }
  .btn.green:hover { filter:brightness(1.1); }
  .btn:disabled { opacity:.5; cursor:not-allowed; }
  .btn.block { width:100%; margin-top:10px; padding:14px; font-size:16px; }
  #result { display:none; margin-top:14px; animation:fadeIn .25s ease; }
  @keyframes fadeIn { from { opacity:0; transform:translateY(6px); } to { opacity:1; transform:none; } }
  .result-box { background:var(--bg); border:1px solid var(--border); border-radius:8px;
                padding:12px; font-size:13px; word-break:break-all; color:var(--text);
                line-height:1.7; max-height:160px; overflow-y:auto; user-select:all; }
  .result-actions { display:flex; gap:8px; margin-top:10px; }
  .result-actions .btn { flex:1; padding:11px 12px; font-size:14px; }
  .examples { margin-top:22px; }
  .examples h3 { font-size:14px; color:var(--muted); margin-bottom:10px; font-weight:600; }
  .example { background:var(--card); border:1px solid var(--border); border-radius:8px;
             padding:10px 12px; margin-bottom:8px; font-size:12.5px; cursor:pointer;
             color:var(--muted); word-break:break-all; line-height:1.6; transition:all .15s; }
  .example:hover { border-color:var(--accent); color:var(--text); }
  .tips { margin-top:22px; padding:14px; border-radius:var(--radius); background:rgba(63,185,80,.08);
          border:1px solid rgba(63,185,80,.25); font-size:13px; color:var(--muted); line-height:1.8; }
  .tips b { color:var(--green); }
  footer { text-align:center; color:#484f58; font-size:12px; padding:16px 0 24px; }
  #toast { position:fixed; left:50%; bottom:48px; transform:translateX(-50%) translateY(20px);
           background:#fff; color:#0d1117; padding:10px 18px; border-radius:999px;
           font-size:13px; font-weight:600; opacity:0; pointer-events:none;
           transition:all .25s ease; box-shadow:0 4px 16px rgba(0,0,0,.4); z-index:99; }
  #toast.show { opacity:1; transform:translateX(-50%) translateY(0); }
  .err { color:var(--red); font-size:12.5px; margin-top:8px; display:none; }
</style>
</head>
<body>
<div class="container">
  <header>
    <div class="logo">GH</div>
    <h1>GitHub 下载加速</h1>
    <p class="sub">手机自部署 · 加速 release / 源码包 / 项目文件<br>本机访问下方页面，电脑连同 Wi-Fi 即可使用</p>
  </header>

  <div class="card">
    <label>粘贴 GitHub 链接（release / APK / 源码包均可）</label>
    <div class="input-wrap">
      <input id="url" type="url" placeholder="https://github.com/user/repo/releases/download/v1.0/app.apk" autocomplete="off" autocapitalize="off" spellcheck="false">
      <button class="btn" id="go" onclick="generate()">生成加速链接</button>
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
    <div class="example" onclick="fill(this)">gist：https://gist.githubusercontent.com/cielpy/351557e6e465c12986419ac5a4dd2568/raw/cmd.py</div>
  </div>

  <div class="tips">
    <b>使用说明</b><br>
    ① 电脑连与手机 <b>同一 Wi-Fi</b>，浏览器访问 <b>__LAN_HOST__:8080</b>；<br>
    ② 或直接拼接加速链接前缀：<br>
    &nbsp;&nbsp;<b>__LAN_HOST__:8080</b>/https://github.com/...<br>
    ③ 支持断点续传，可用迅雷 / IDM / 浏览器直接下载。
  </div>
</div>

<footer>gh-proxy-python · Android Chaquopy · MIT License</footer>
<div id="toast"></div>

<script>
  const URL_EXP = /^(https?:\/\/)?(github\.com\/([^/]+\/[^/]+\/(releases|archive|blob|raw|tags|info|git-)|tags|info|git-)|raw\.(githubusercontent|github)\.com\/|gist\.(githubusercontent|github)\.com\/)/i;

  function normalize(input) {
    let u = input.trim();
    if (!u) return '';
    if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
    return u;
  }
  function validate(u) { return URL_EXP.test(u); }
  function fill(el) {
    document.getElementById('url').value = el.textContent.replace(/^[^：:]*[：:]\s*/, '');
    document.getElementById('result').style.display = 'none';
    document.getElementById('err').style.display = 'none';
    document.getElementById('url').focus();
  }
  function generate() {
    const input = document.getElementById('url').value;
    const err = document.getElementById('err');
    const result = document.getElementById('result');
    const u = normalize(input);
    if (!u) { err.textContent='请输入 GitHub 链接'; err.style.display='block'; result.style.display='none'; return; }
    if (!validate(u)) { err.textContent='仅支持 GitHub release / archive / blob / raw / gist 链接'; err.style.display='block'; result.style.display='none'; return; }
    err.style.display='none';
    document.getElementById('resultUrl').textContent = location.origin + '/' + u;
    result.style.display='block';
  }
  function copyLink() {
    const txt = document.getElementById('resultUrl').textContent;
    const done = () => toast('已复制到剪贴板');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(txt).then(done).catch(() => fallbackCopy(txt, done));
    } else { fallbackCopy(txt, done); }
  }
  function fallbackCopy(txt, done) {
    const ta = document.createElement('textarea');
    ta.value = txt; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); done(); } catch (e) { toast('复制失败，请长按手动复制'); }
    document.body.removeChild(ta);
  }
  function download() {
    const u = document.getElementById('resultUrl').textContent;
    if (u) window.open(u, '_blank');
  }
  function toast(msg) {
    const t = document.getElementById('toast');
    t.textContent = msg; t.classList.add('show');
    clearTimeout(t._timer);
    t._timer = setTimeout(() => t.classList.remove('show'), 1800);
  }
  document.getElementById('url').addEventListener('keydown', e => { if (e.key === 'Enter') generate(); });
</script>
</body>
</html>"""

_main.INDEX_HTML = INDEX_HTML  # 注入到 main 模块


# ----------------------------------------------------------------------
# 启动服务
# ----------------------------------------------------------------------
def start_server():
    """
    由 Chaquopy (PyApplication.onCreate) 在主线程调用。
    Android 上 uvicorn 必须单进程、无 reload（不支持 multiprocessing / watchfiles）。
    """
    import logging
    import uvicorn
    from config import HOST, PORT

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s [%(levelname)s] %(message)s",
                        datefmt="%H:%M:%S")
    logger = logging.getLogger("ghproxy")
    logger.info("gh-proxy-android 启动中... HOST=%s PORT=%s", HOST, PORT)
    logger.info("手机内访问: http://127.0.0.1:%s", PORT)
    logger.info("同 Wi-Fi 电脑访问: http://<手机局域网IP>:%s", PORT)

    uvicorn.run(
        _main.app,
        host=HOST,
        port=PORT,
        log_level="info",
        access_log=True,
        workers=1,        # Android 不支持 multiprocessing
        reload=False,     # Android 不支持 watchfiles
        loop="asyncio",   # 显式指定，避免在某些 Android 平台默认选 uvloop 出问题
        http="h11",       # h11 是纯 Python 实现；uvloop/httptools 在 Android 上兼容性差
    )


# ----------------------------------------------------------------------
# 提供给 Kotlin 调用的运行时状态查询（局域网 IP 用于在应用内展示）
# ----------------------------------------------------------------------
def get_status():
    """返回运行时状态字典，给 Kotlin 调用展示在 UI 上"""
    import socket
    from config import PORT
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    return {"port": PORT, "ips": ips, "running": True}
# gh-proxy-android

**GitHub 下载加速器 Android APK —— 纯 Kotlin 原生实现，手机自部署，即装即用**

基于 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy) 的逻辑，用 **Kotlin + NanoHTTPD + OkHttp** 原生实现代理服务，
**不依赖 Python / Chaquopy / 任何 native 库**，因此不存在 ABI 兼容、16KB 内存页等新系统兼容问题，所有 Android 7.0+ 设备即装即用。

```
┌──────────────────────────────────────┐
│   Android 手机 (本 APK)               │
│   ┌──────────────────────────────┐   │
│   │ MainActivity.onCreate()      │   │
│   │   └─ ProxyServer(8080) 启动  │   │
│   │       └─ NanoHTTPD + OkHttp  │   │
│   │           :8080 (0.0.0.0)    │   │
│   └──────────────────────────────┘   │
└──────────────────┬───────────────────┘
                   │ 局域网
        ┌──────────┴──────────┐
        │                     │
  ┌─────▼─────┐         ┌─────▼─────┐
  │ 手机内     │         │ 电脑      │
  │ WebView   │         │ 浏览器    │
  │ 127.0.0.1 │         │ 192.168.x.x│
  └───────────┘         └───────────┘
```

## 功能

- ✅ **应用内 WebView** 直接使用加速服务（移动端友好的单页 UI）
- ✅ **同 Wi-Fi 电脑可直接访问手机**（顶部状态栏显示手机 IP）
- ✅ 流式转发 + **Range 断点续传**（APK / 大文件下载）
- ✅ release 302 自动跟随（→ objects.githubusercontent.com CDN）
- ✅ blob / raw / gist 全支持
- ✅ 文件大小限制（默认 5GB，超限 302 回源）

---

## 一、构建 APK

### 方式 0：GitHub Actions 自动构建（推荐）

推送 `v*` tag 自动构建并发布 GitHub Release（上传 APK）；也支持 Actions 页面手动触发。

```bash
git tag v1.1.0 && git push origin v1.1.0
```

### 方式 A：Android Studio（推荐）

1. 打开 Android Studio → **Open** → 选中本工程根目录
2. 等待 Gradle 同步
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. 产物：`app/build/outputs/apk/release/app-release.apk`

### 方式 B：命令行

```bash
gradle wrapper
./gradlew assembleRelease
```

### 兼容性

| 配置 | 值 |
|---|---|
| minSdk | 24（Android 7.0+） |
| targetSdk / compileSdk | 34 |
| 技术栈 | Kotlin + NanoHTTPD 2.3.1 + OkHttp 4.12.0 |
| APK 大小 | 约 2-3 MB（无 Python 运行时） |

---

## 二、使用

1. 安装 APK 并启动
2. 顶部状态栏显示：`电脑访问：http://手机IP:8080/ · 手机内：http://127.0.0.1:8080/`
3. 应用内 WebView 直接使用；电脑浏览器访问手机 IP 同样可用
4. 也支持拼接链接：`http://手机IP:8080/https://github.com/...`

---

## 三、工程结构

```
gh-proxy-android/
├── build.gradle / settings.gradle / gradle.properties
├── .github/workflows/build.yml      # GitHub Actions 自动构建
├── RELEASE_NOTES.md
└── app/
    ├── build.gradle                 # 依赖：NanoHTTPD + OkHttp
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml      # INTERNET 权限 + cleartext
        ├── java/com/tencent/ghproxy/
        │   ├── MainActivity.kt      # WebView + 局域网 IP + 服务启动
        │   └── ProxyServer.kt       # 核心代理逻辑（纯 Kotlin）
        └── res/                     # 布局 / 主题 / 图标
```

---

## 四、实现说明（ProxyServer.kt）

- **URL 校验**：与 gh-proxy 一致的正则（releases / archive / blob / raw / gist / git）
- **blob→raw**：自动转换直链
- **302 跟随**：Location 为 GitHub 内链接 → 302 给客户端继续走代理；外部 CDN → 服务端跟随（最多 5 跳）
- **流式转发**：OkHttp 流 → NanoHTTPD chunked，不占内存
- **Range**：请求头透传，支持 206 断点续传
- **大小限制**：Content-Length 超 5GB → 302 回源直连

---

## 五、声明

- 本工具仅供学习与个人使用，请遵守 GitHub 服务条款
- 参考 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy)（MIT License）

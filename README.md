# gh-proxy-android

**把 GitHub 下载加速器打包成 Android APK —— 手机自部署，即装即用**

基于 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy) 的逻辑，用 **Chaquopy (Python 17.0)** 在 APK 内嵌入 Python 运行时，直接运行我们之前写的 FastAPI / httpx 代理代码。

应用启动即内置代理服务：

```
┌──────────────────────────────────────┐
│   Android 手机 (本 APK)               │
│   ┌──────────────────────────────┐   │
│   │ PyApplication.onCreate()     │   │
│   │   └─ app_main.start_server() │   │
│   │       └─ uvicorn.run(app)    │   │
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
- ✅ blob / raw / gist / git 协议全支持
- ✅ 文件大小限制 + 白/黑/放行 访问控制
- ✅ 单 APK 自包含 Python 运行时，**无需网络下载额外组件**

---

## 一、构建 APK

### 方式 0：GitHub Actions 自动构建（推荐，无需本地环境）

仓库自带 [`.github/workflows/build.yml`](.github/workflows/build.yml)：

- **推送 `v*` tag** 自动构建并发布 GitHub Release（上传 APK）
- 也在 Actions 页面支持 **手动触发**（仅产出 artifact，不自动发布）

手动发布流程：
```bash
git tag v1.0.1 && git push origin v1.0.1
# 等待 Actions 完成后自动创建 release
```

### 方式 A：Android Studio（推荐）

1. 打开 Android Studio → **Open** → 选中本工程根目录
2. 等待 Gradle 同步（首次会自动下载 AGP 8.5.2 + Chaquopy 17.0 插件，约 5-10 分钟）
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. 产物：`app/build/outputs/apk/release/app-release.apk`（debug 版：`app-debug.apk`）

> Android Studio 首次打开会自动生成 `gradle/wrapper/` 与 `gradlew`，无需手动处理。

### 方式 B：命令行

确保机器已装 JDK 17+ 与 Android SDK，然后：

```bash
# 在工程根目录执行
gradle wrapper           # 首次执行生成 wrapper（之后用 ./gradlew）
./gradlew assembleDebug  # 生成 debug APK
./gradlew assembleRelease # 生成 release APK（自带 debug 签名）
```

`ANDROID_HOME` 环境变量需指向你的 Android SDK 路径。

### 兼容性

| 配置 | 值 |
|---|---|
| `minSdk` | 24（Android 7.0） |
| `targetSdk` / `compileSdk` | 34 |
| ABI | `arm64-v8a` + `x86_64`（覆盖 99% 真机/模拟器） |
| Python | 3.12（Chaquopy 17.0 嵌入） |
| 包大小 | debug 约 50-60 MB（Python 运行时占大头） |

若需支持 `armeabi-v7a`（超老设备），需把 `app/build.gradle` 的 Python 版本改为 `3.11` 并加 ABI。

---

## 二、使用方法

### 1. 安装

把生成的 APK 传到手机并安装（需开启"未知来源应用"）。

### 2. 启动应用

启动后看到：
- 顶部状态栏：`📡 电脑请访问：http://192.168.x.x:8080/  ·  手机内访问：http://127.0.0.1:8080/`
- 中部 WebView：代理首页（输入 GitHub 链接 → 生成加速链接 → 直接下载）

### 3. 让电脑也能用（可选）

- 让手机和电脑连同一 Wi-Fi
- 电脑浏览器打开 `http://<状态栏显示的手机IP>:8080`
- 即可在电脑上使用同一个加速服务（加速 PC 端下载 APK / 源码包）

### 4. 防火墙提示

手机首次启动需放行 8080 端口（部分 ROM 可能弹出授权，请允许）。

---

## 三、工程结构

```
gh-proxy-android/
├── build.gradle                  # 顶层构建（AGP + Chaquopy 插件版本）
├── settings.gradle
├── gradle.properties
├── .gitignore
├── README.md
└── app/
    ├── build.gradle              # 模块构建（关键：Chaquopy + pip 依赖）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml   # 权限 + Chaquopy 入口声明
        ├── java/com/tencent/ghproxy/
        │   └── MainActivity.kt   # WebView + 局域网 IP 展示
        ├── python/               # 我们的 Python 代码（Chaquopy 打包进 APK）
        │   ├── app_main.py       # Android 入口（uvicorn.start_server）
        │   ├── main.py           # FastAPI app
        │   ├── proxy.py          # 核心代理逻辑
        │   ├── config.py         # 配置
        │   └── __init__.py
        └── res/
            ├── layout/activity_main.xml
            ├── values/{colors,strings,themes}.xml
            ├── drawable/{ic_launcher_background,ic_launcher_foreground}.xml
            ├── mipmap-anydpi-v26/ic_launcher{,_round}.xml    # adaptive icon
            ├── mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png  # PNG 兜底
            └── xml/{data_extraction_rules,backup_rules}.xml
```

---

## 四、配置（运行时调整）

通过环境变量控制（在 `app/src/main/python/app_main.py` 里改默认值，或通过 `adb shell` 设置）：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `GH_PROXY_PORT` | `8080` | 监听端口 |
| `GH_PROXY_SIZE_LIMIT` | `5GB` | 文件大小上限（超限 302 回源） |
| `GH_PROXY_JSDELIVR` | `0` | 分支/raw 走 jsDelivr（`1` 开启） |
| `GH_PROXY_TIMEOUT` | `60` | 上游超时（秒） |
| `GH_PROXY_TLS_VERIFY` | `1` | 证书校验（自签名环境设 `0`） |
| `GH_PROXY_WHITE_LIST` 等 | 空 | 见 PC 版 README |

修改后需要重新 build APK。

---

## 五、常见问题

**Q：APK 太大（50MB+）？**
A：Python 运行时 + FastAPI/httpx 占大部分体积（约 30-40MB）。如必须瘦身：换 `uvicorn` 轻量替代（hypercorn 不行 / 自己写 http server），或用 Pyodide + Web Worker。

**Q：应用启动后 WebView 白屏？**
A：Python 服务启动稍慢（约 1-3 秒），`MainActivity.loadHomeWithRetry` 会自动重试 10 次。如仍白屏：用 `adb logcat | grep ghproxy` 看 Python 启动日志。

**Q：手机锁屏后服务停了？**
A：当前实现是 Activity 生命周期内运行。如需后台保持，可改为 Service（工程已为改造预留接口：把 `app_main.start_server` 改为 Service onCreate 调用）。默认设置下锁屏后系统会限制网络，长时间下载建议保持前台。

**Q：能装到旧手机吗？**
A：`minSdk 24`（Android 7.0+），基本覆盖 2016 年以后的所有手机。`armeabi-v7a` 老设备支持需改 Python 3.11。

---

## 六、声明

- 本工具仅供学习与个人使用，请遵守 GitHub 服务条款
- 遵守原项目 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy) 的 MIT License
- Chaquopy 17.0 在 Apache 2.0 + 商业双重许可下发行；个人/开源用途免费
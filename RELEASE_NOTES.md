# GitHub 加速 · Android APK 工程

**纯 Kotlin 原生实现**的 GitHub 下载加速器（不再依赖 Python/Chaquopy）。

## ✨ 功能

- 应用内 WebView 直接使用加速服务（移动端友好的单页 UI）
- 同 Wi-Fi 电脑可直接访问手机 IP（顶部状态栏显示）
- 流式转发 + Range 断点续传（APK / 大文件下载）
- release 302 自动跟随（→ objects.githubusercontent.com CDN）
- blob / raw / gist 全支持
- 文件大小限制（默认 5GB）

## 📦 兼容性

| 配置 | 值 |
|---|---|
| minSdk | 24（Android 7.0） |
| targetSdk / compileSdk | 34 |
| 技术栈 | Kotlin + NanoHTTPD + OkHttp（纯 Java/Kotlin，无 native 库） |
| APK 大小 | 约 2-3 MB |

## 🚀 自行构建

```bash
git clone https://github.com/hUh63/gh-proxy-android.git
cd gh-proxy-android
# Android Studio 打开 → Build APK；或命令行：
gradle wrapper
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 📜 License

MIT（参考 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy)）

## 📝 版本历史

### v1.0（2026-08-13）
- ✨ **自定义 DNS（DoH 兜底）**：绕过 DNS 污染与 Clash fake-ip（198.18.x.x），
  修复开梯子时报 `Failed to connect to github.com/198.18.0.6:443`
- ✨ **智能分流**：GitHub 主域名走代理，下载 CDN 域名（objects.githubusercontent.com 等）
  探测到可直连时自动直连，绕开慢速代理节点实现真正加速
- 🐛 修复首页「复制」按钮无反应（WebView 非安全上下文无 clipboard API，改用 execCommand 兼容方案）
- 🐛 修复首页「直接下载」按钮无反应（window.open 被 WebView 拦截，改为页内导航 + DownloadListener 交给系统下载器）
- 🐛 错误信息分类（DNS 失败/连接失败/超时分开提示，明确下一步操作）
- 🏷️ 版本号统一为 1.0（应用名与应用内版本号一致）
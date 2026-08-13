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

### v1.0（2026-08-14）
- 🐛 **修复加速站模式 0KB/s**：外部重定向（CDN 302）改为直接交给客户端直连，
  与浏览器行为一致（实测浏览器直连加速站 1MB/s，此前 App 服务端跟随 CDN 导致卡死）
- 🐛 加速站拒绝网页链接时返回中文指引
- ✨ 加速站上游模式（默认开启）：GitHub 请求经公共加速站（gh-proxy.com 等）海外中转
- ✨ 自定义 DNS（DoH 兜底）：绕过 DNS 污染与 Clash fake-ip
- ✨ 智能分流 + 定长响应（修复下载器 Http Data Error）
- 🏷️ 版本号统一为 1.0
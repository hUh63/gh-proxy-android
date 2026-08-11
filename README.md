# gh-proxy-android

**GitHub 下载加速器 · Android APK 工程源码**

基于 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy) 的逻辑，用 Chaquopy 在 APK 内嵌 Python 运行时，
APK 启动即内置代理服务，手机自当加速节点。

应用启动后访问 [Releases](../../releases) 下载工程源码压缩包（`gh-proxy-android-source-v1.0.0.zip`），
用 Android Studio 打开即可构建 APK。

## 功能

- 应用内 WebView 直接使用加速服务（移动端友好的单页 UI）
- 同 Wi-Fi 电脑可直接访问手机 IP（顶部状态栏显示）
- 流式转发 + Range 断点续传（APK / 大文件下载）
- release 302 自动跟随（→ objects.githubusercontent.com CDN）
- blob / raw / gist / git 协议全支持
- 文件大小限制 + 白/黑/放行 访问控制

## 详细说明

见 release 内的 `gh-proxy-android-source-v1.0.0.zip` 工程的 `README.md`。

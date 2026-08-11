# GitHub 加速 · Android APK 工程

基于 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy) 的逻辑，用 **Chaquopy 17.0** 在 APK 内嵌 Python 运行时，APK 启动即内置代理服务，手机自当加速节点。

## ✨ 功能

- 应用内 WebView 直接使用加速服务（移动端友好的单页 UI）
- 同 Wi-Fi 电脑可直接访问手机 IP（顶部状态栏显示）
- 流式转发 + Range 断点续传（APK / 大文件下载）
- release 302 自动跟随（→ objects.githubusercontent.com CDN）
- blob / raw / gist / git 协议全支持
- 文件大小限制 + 白/黑/放行 访问控制

## 📦 兼容性

| 配置 | 值 |
|---|---|
| minSdk | 24（Android 7.0） |
| targetSdk / compileSdk | 34 |
| ABI | arm64-v8a + x86_64 |
| Python | 3.12 |
| APK 大小 | 约 50-60 MB |

## 🚀 自行构建

```bash
git clone https://github.com/hUh63/gh-proxy-android.git
cd gh-proxy-android
# 用 Android Studio 打开 → Build APK；或命令行：
gradle wrapper
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 📜 License

MIT（参考 [hunshcn/gh-proxy](https://github.com/hunshcn/gh-proxy)）
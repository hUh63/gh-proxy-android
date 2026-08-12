# Keep NanoHTTPD / OkHttp
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.tencent.ghproxy.** { *; }
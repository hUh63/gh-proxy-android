# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in proguard-android-optimize.txt.

# Chaquopy: keep Python classes that may be referenced from Java/Kotlin
-keep class com.chaquo.python.** { *; }
-keep class com.tencent.ghproxy.** { *; }
-dontwarn com.chaquo.**
# libsu / Shizuku use reflection & AIDL stubs - keep them intact in release builds
-keep class com.topjohnwu.superuser.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-dontwarn com.topjohnwu.superuser.**
-dontwarn rikka.shizuku.**

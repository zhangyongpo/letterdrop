# 本工程类
-keep class com.example.letterdrop.** { *; }

# Vosk 通过 JNA 反射加载 native 库，混淆会导致运行时崩溃
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class android.speech.** { *; }

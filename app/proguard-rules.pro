# 保留语音识别相关类，避免混淆导致运行时问题
-keep class android.speech.** { *; }
-keep class com.example.letterdrop.** { *; }

package com.example.letterdrop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 封装 Android 原生语音识别（Google 语音识别服务）。
 *
 * - 使用 en-US 语言模型，识别单个英文字母名（A、B、C …）。
 * - 识别到结果后通过 onLetterHeard(Char) 回调通知游戏，并自动重启麦克风，
 *   实现“持续监听”。
 * - 仅在主线程创建与使用 SpeechRecognizer（框架要求）。
 */
class SpeechManager(
    private val context: Context,
    private val onLetterHeard: (Char) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listening = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // 网络/无语音等错误后稍作延迟再重启
                    scheduleRestart()
                }
                override fun onResults(results: Bundle?) {
                    handleResults(results)
                    scheduleRestart()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    // 边说边识别，提升反应速度
                    handleResults(partialResults)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            onError("当前设备不支持语音识别，请安装 Google 应用或使用系统语音服务")
        }
    }

    /** 把识别结果里的“单个字母词”提取出来，回调给游戏 */
    private fun handleResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (text in matches) {
            val tokens = text.uppercase(Locale.US).split(" ", "\n", "\t", ".", ",")
            for (token in tokens) {
                // 只匹配“单个字母”的 token，尽量避免把整词误判成字母
                if (token.length == 1 && token[0] in 'A'..'Z') {
                    onLetterHeard(token[0])
                }
            }
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
    }

    fun start() {
        if (recognizer == null) return
        listening = true
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            onError(e.message ?: "启动语音识别失败")
        }
    }

    /** 识别中断后自动重启，形成连续监听 */
    private fun scheduleRestart() {
        if (!listening) return
        mainHandler.postDelayed({
            if (!listening) return@postDelayed
            try {
                recognizer?.startListening(buildIntent())
            } catch (e: Exception) {
                onError(e.message ?: "重启语音识别失败")
            }
        }, 500)
    }

    fun stop() {
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
        } catch (ignored: Exception) {
        }
    }

    fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }
}

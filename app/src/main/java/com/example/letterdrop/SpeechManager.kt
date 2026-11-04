package com.example.letterdrop

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 语音识别封装。
 *
 * 国内多数手机没有 Google 语音服务，SpeechRecognizer 会不可用。
 * 因此依次尝试多种系统语音后端；全部不可用时通过 onUnavailable 通知上层
 * 降级为「点字模式」，保证游戏仍然可玩。
 *
 * 识别成功 -> onLetterHeard(字母)，并自动重启麦克风实现连续监听。
 * 注意：SpeechRecognizer 必须在主线程创建和使用。
 */
class SpeechManager(
    private val context: Context,
    private val onLetterHeard: (Char) -> Unit,
    private val onError: (String) -> Unit,
    private val onUnavailable: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var consecutiveErrors = 0
    private var unavailableReported = false

    private val noServiceMessage =
        "本机没有可用的系统语音服务，已切换为「点字模式」：直接点中掉落的字母也能消除。"

    /** 英文字母常见读音 / 音译词 -> 字母，提升识别命中率 */
    private val spokenNames = mapOf(
        "AY" to 'A', "BEE" to 'B', "BE" to 'B', "SEE" to 'C', "SEA" to 'C",
        "DEE" to 'D', "EE" to 'E', "EF" to 'F', "GEE" to 'G', "JEE" to 'G',
        "AITCH" to 'H', "EYE" to 'I', "JAY" to 'J', "KAY" to 'K', "EL" to 'L',
        "EM" to 'M', "EN" to 'N', "OH" to 'O', "PEE" to 'P', "CUE" to 'Q',
        "ARE" to 'R', "ESS" to 'S', "TEE" to 'T', "YOU" to 'U', "VEE" to 'V',
        "DOUBLEU" to 'W', "EX" to 'X', "WHY" to 'Y', "ZEE" to 'Z', "ZED" to 'Z'
    )

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            // “没听清 / 超时”是正常现象，不算服务故障
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                consecutiveErrors = 0
                scheduleRestart()
                return
            }
            consecutiveErrors++
            if (consecutiveErrors >= 3) {
                markUnavailable("语音服务连续出错，$noServiceMessage")
            } else {
                scheduleRestart()
            }
        }
        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            handleResults(results)
            scheduleRestart()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            handleResults(partialResults)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        recognizer = createBestRecognizer()
        if (recognizer == null) markUnavailable(noServiceMessage)
    }

    /** 当前是否有可用的语音识别服务 */
    val isAvailable: Boolean get() = recognizer != null && !unavailableReported

    @SuppressLint("NewApi")
    private fun createBestRecognizer(): SpeechRecognizer? {
        // 1) 在线识别（Google 语音服务等），对英文字母名识别最准
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                return SpeechRecognizer.createSpeechRecognizer(context)
                    ?.also { it.setRecognitionListener(recognitionListener) }
            }
        } catch (ignored: Throwable) {
        }
        // 2) Android 12+ 端侧离线识别（部分机型自带，不依赖 Google 服务）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    ?.also { it.setRecognitionListener(recognitionListener) }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    /** 主动停用语音（例如用户拒绝麦克风权限），走降级流程 */
    fun disable(reason: String = noServiceMessage) = markUnavailable(reason)

    private fun markUnavailable(msg: String) {
        if (unavailableReported) return
        unavailableReported = true
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (ignored: Throwable) {
        }
        recognizer = null
        onUnavailable(msg)
    }

    /** 把识别结果里的字母读音提取出来，回调给游戏 */
    private fun handleResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (text in matches) {
            val tokens = text.uppercase(Locale.US).split(" ", "\n", "\t", ".", ",", "?", "!")
            for (token in tokens) {
                if (token.isEmpty()) continue
                // 1) 单个字母（最常见）
                if (token.length == 1 && token[0] in 'A'..'Z') {
                    onLetterHeard(token[0])
                    continue
                }
                // 2) 字母读音 / 音译词（bee、see、zed …）
                spokenNames[token]?.let { onLetterHeard(it) }
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
        if (!isAvailable) return
        listening = true
        consecutiveErrors = 0
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Throwable) {
            onError(e.message ?: "启动语音识别失败")
        }
    }

    /** 识别中断后自动重启，形成连续监听 */
    private fun scheduleRestart() {
        if (!listening || !isAvailable) return
        mainHandler.postDelayed({
            if (!listening || !isAvailable) return@postDelayed
            try {
                recognizer?.startListening(buildIntent())
            } catch (e: Throwable) {
                onError(e.message ?: "重启语音识别失败")
            }
        }, 500)
    }

    fun stop() {
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
        } catch (ignored: Throwable) {
        }
    }

    fun destroy() {
        stop()
        try {
            recognizer?.destroy()
        } catch (ignored: Throwable) {
        }
        recognizer = null
    }
}

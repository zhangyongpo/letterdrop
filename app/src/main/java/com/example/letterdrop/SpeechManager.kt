package com.example.letterdrop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Vosk 离线语音识别（Apache-2.0），完全不依赖 Google 服务 / 网络 / 账号。
 *
 * 核心思路：
 * 1. 用 26 个字母的英文读音（及常见同音词）构造受限 grammar，
 *    把解码结果锁在小词表里，大幅降低误识别；
 * 2. 自己用 AudioRecord 读 16k/16bit/mono PCM 喂给 Recognizer，
 *    每识别完一句就 reset 继续，实现不间断的连续监听；
 * 3. grammar 若因模型不支持或 OOV 创建失败，自动回退到默认语言模型，
 *    再配合读音词映射兜底，保证功能不中断。
 *
 * 模型来自 assets/model（vosk-model-small-en-us-0.15，约 40MB），
 * 首次启动由 StorageService 异步解压到外部私有目录。
 */
class SpeechManager(
    private val context: Context,
    private val onLetterHeard: (Char) -> Unit,
    private val onError: (String) -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val onReady: () -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val DEDUP_MS = 350L

        /**
         * 字母读音 / 同音词 -> 字母。
         * 尽量选常见英文词（bee、see、eye、oh、you、why …），
         * 保证它们确实存在于 Vosk 的 en-us 词典中。
         */
        private val LETTER_WORDS: List<Pair<String, Char>> = listOf(
            "a" to 'A', "ay" to 'A',
            "bee" to 'B', "be" to 'B',
            "see" to 'C', "sea" to 'C',
            "dee" to 'D',
            "ee" to 'E', "e" to 'E',
            "ef" to 'F', "eff" to 'F',
            "gee" to 'G', "jee" to 'G',
            "aitch" to 'H', "h" to 'H',
            "eye" to 'I', "i" to 'I',
            "jay" to 'J',
            "kay" to 'K', "k" to 'K',
            "el" to 'L', "ell" to 'L',
            "em" to 'M',
            "en" to 'N',
            "oh" to 'O', "o" to 'O',
            "pee" to 'P', "p" to 'P',
            "cue" to 'Q', "queue" to 'Q',
            "are" to 'R', "r" to 'R',
            "ess" to 'S', "s" to 'S',
            "tea" to 'T', "tee" to 'T',
            "you" to 'U', "u" to 'U',
            "vee" to 'V',
            "double u" to 'W', "doubleu" to 'W',
            "ex" to 'X', "x" to 'X',
            "why" to 'Y', "y" to 'Y',
            "zee" to 'Z', "zed" to 'Z'
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wordToLetter: Map<String, Char> = LETTER_WORDS.toMap()
    private val grammarJson: String = buildGrammar()
    private val lastHeardAt = ConcurrentHashMap<Char, Long>()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    @Volatile
    private var listening = false
    private var pendingStart = false
    private var modelReady = false
    private var unavailableReported = false

    /**
     * 播放内置字母读音期间挂起解码：录音线程继续读 PCM（不积压缓冲区），
     * 但不把音频交给 Recognizer，避免读音外放被自己识别后自动消除字母。
     */
    @Volatile
    private var recognitionSuspended = false

    /** 模型已就绪且未被降级 */
    val isAvailable: Boolean get() = modelReady && !unavailableReported
    val isUnavailable: Boolean get() = unavailableReported

    /** 由界面在字母读音播放开始/结束时调用；幂等，可安全重复调用 */
    fun setRecognitionSuspended(value: Boolean) {
        recognitionSuspended = value
    }

    private fun buildGrammar(): String {
        val arr = JSONArray()
        for ((word, _) in LETTER_WORDS) arr.put(word)
        for (c in 'a'..'z') arr.put(c.toString())
        arr.put("[unk]")   // 词表外的声音走 unk，避免硬套成某个字母
        return arr.toString()
    }

    /** 短期去重：partial 与 final 结果可能反复命中同一个字母 */
    private fun emit(ch: Char) {
        val now = System.currentTimeMillis()
        val last = lastHeardAt[ch] ?: 0L
        if (now - last < DEDUP_MS) return
        lastHeardAt[ch] = now
        mainHandler.post { onLetterHeard(ch) }
    }

    /** 异步解压模型；首次启动约需数秒，完成后回调 onReady */
    fun prepare() {
        try {
            StorageService.unpack(
                context, "model", "model",
                object : StorageService.Callback<Model> {
                    override fun onComplete(result: Model) {
                        model = result
                        modelReady = true
                        onReady()
                        if (pendingStart) {
                            pendingStart = false
                            start()
                        }
                    }
                },
                object : StorageService.Callback<IOException> {
                    override fun onComplete(result: IOException) {
                        markUnavailable("离线语音模型加载失败：${result.message}")
                    }
                }
            )
        } catch (t: Throwable) {
            markUnavailable("离线语音初始化失败：${t.message}")
        }
    }

    fun start() {
        if (unavailableReported) return
        if (!modelReady) {
            pendingStart = true   // 模型还在解压，就绪后自动开始
            return
        }
        if (listening) return
        lastHeardAt.clear()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("尚未获得麦克风权限")
            return
        }
        try {
            val m = model ?: throw IOException("模型未就绪")

            // 优先使用受限 grammar；不支持时回退默认语言模型
            val rec = try {
                Recognizer(m, SAMPLE_RATE.toFloat(), grammarJson)
            } catch (ignored: Throwable) {
                Recognizer(m, SAMPLE_RATE.toFloat())
            }
            recognizer = rec

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 4096)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("麦克风初始化失败")
            }
            audioRecord = ar
            listening = true
            ar.startRecording()
            audioThread = Thread { listenLoop(rec, ar) }.also { it.start() }
        } catch (t: Throwable) {
            markUnavailable("无法启动离线语音识别：${t.message}")
        }
    }

    private fun listenLoop(rec: Recognizer, ar: AudioRecord) {
        val buffer = ShortArray(2048)
        var wasSuspended = false
        while (listening) {
            val n = ar.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            // 读音播放中：丢弃这段音频；恢复时 reset，清掉挂起前的残留识别状态
            if (recognitionSuspended) {
                if (!wasSuspended) {
                    try {
                        rec.reset()
                    } catch (ignored: Throwable) {
                    }
                    wasSuspended = true
                }
                continue
            }
            if (wasSuspended) {
                try {
                    rec.reset()
                } catch (ignored: Throwable) {
                }
                wasSuspended = false
            }
            try {
                if (rec.acceptWaveForm(buffer, n)) {
                    handleJson(rec.result)
                    rec.reset()          // 重置后继续听下一个字母
                } else {
                    handleJson(rec.partialResult)
                }
            } catch (t: Throwable) {
                mainHandler.post { onError(t.message ?: "识别异常") }
            }
        }
    }

    /** 解析 Vosk 的 {"text": ...} / {"partial": ...}，映射成字母 */
    private fun handleJson(json: String) {
        if (json.isBlank()) return
        val text = try {
            val o = JSONObject(json)
            val t = o.optString("text", "")
            if (t.isNotEmpty()) t else o.optString("partial", "")
        } catch (ignored: Throwable) {
            return
        }.trim().lowercase()

        if (text.isEmpty() || text == "[unk]") return

        // 多词读音优先处理
        if (text.startsWith("double u")) {
            emit('W')
            return
        }

        val found = ArrayList<Char>()
        for (token in text.split(Regex("\\s+"))) {
            val mapped = wordToLetter[token]
            if (mapped != null) {
                found.add(mapped)
            } else if (token.length == 1 && token[0] in 'a'..'z') {
                found.add(token[0].uppercaseChar())
            }
        }
        for (ch in found) emit(ch)
    }

    fun stop() {
        listening = false
        pendingStart = false
        try {
            audioRecord?.stop()
        } catch (ignored: Throwable) {
        }
        try {
            audioThread?.join(500)
        } catch (ignored: InterruptedException) {
        }
        audioThread = null
        try {
            audioRecord?.release()
        } catch (ignored: Throwable) {
        }
        audioRecord = null
        try {
            recognizer?.close()
        } catch (ignored: Throwable) {
        }
        recognizer = null
    }

    /** 主动停用语音（例如用户拒绝麦克风权限） */
    fun disable(reason: String) = markUnavailable(reason)

    private fun markUnavailable(msg: String) {
        if (unavailableReported) return
        unavailableReported = true
        stop()
        mainHandler.post { onUnavailable(msg) }
    }

    fun destroy() {
        stop()
        try {
            model?.close()
        } catch (ignored: Throwable) {
        }
        model = null
        modelReady = false
    }
}

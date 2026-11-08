package com.example.letterdrop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var bgImage: ImageView
    private lateinit var scoreText: TextView
    private lateinit var livesText: TextView
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var gameOverOverlay: View
    private lateinit var gameOverCard: View
    private lateinit var finalScoreText: TextView
    private lateinit var bestScoreText: TextView
    private lateinit var speechManager: SpeechManager

    /** 系统英文 TTS：点击字母时播放标准读音 */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var utteranceSeq = 0

    /** 当前正在播放的读音 id，只认最后一次播放的完成回调 */
    @Volatile
    private var activeUtteranceId: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var modelReady = false
    private var voiceDisabled = false
    private var inGame = false
    private var lastShownScore = 0
    private var bestScore = 0

    private val prefs by lazy { getSharedPreferences(STATS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)
        bgImage = findViewById(R.id.bgImage)
        scoreText = findViewById(R.id.scoreText)
        livesText = findViewById(R.id.livesText)
        speedText = findViewById(R.id.speedText)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        gameOverOverlay = findViewById(R.id.gameOverOverlay)
        gameOverCard = findViewById(R.id.gameOverCard)
        finalScoreText = findViewById(R.id.finalScoreText)
        bestScoreText = findViewById(R.id.bestScoreText)

        bestScore = prefs.getInt(KEY_BEST, 0)

        speechManager = SpeechManager(
            this,
            onLetterHeard = { ch -> gameView.onLetterHeard(ch) },
            // 临时识别错误不打扰用户；真正不可用由 onUnavailable 处理
            onError = { },
            onUnavailable = { msg ->
                voiceDisabled = true
                gameView.practiceMode = true
                refreshStatus()
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            },
            onReady = {
                modelReady = true
                // 模型就绪后才开始正式计分，避免开局等待模型时白掉命
                gameView.practiceMode = voiceDisabled
                refreshStatus()
            }
        )

        gameView.onStateChanged { onStateUpdated() }
        // 点击字母只播放标准读音，由用户跟读麦克风完成消除与纠音
        gameView.onLetterTapped { ch -> speakLetter(ch) }

        startButton.setOnClickListener {
            // 语音不可用时进入听音练习模式；否则先拿到麦克风权限再开局
            if (!voiceDisabled && !hasRecordPermission()) {
                requestRecordPermission()
                return@setOnClickListener
            }
            startGame()
        }

        findViewById<Button>(R.id.restartButton).setOnClickListener {
            gameOverOverlay.visibility = View.GONE
            startGame()
        }

        initTextToSpeech()
        refreshStatus()
        speechManager.prepare()
    }

    /** 初始化系统英文 TTS，并监听播放结束以便恢复语音识别 */
    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            val engine = tts
            if (engine != null && status == TextToSpeech.SUCCESS) {
                val result = engine.setLanguage(Locale.US)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (ttsReady) engine.setSpeechRate(0.85f)   // 稍慢一点，方便听清并模仿
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finishUtterance(utteranceId)
                    override fun onError(utteranceId: String?) = finishUtterance(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) =
                        finishUtterance(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) =
                        finishUtterance(utteranceId)
                })
            }
        }
    }

    /**
     * 点击字母：播放该字母的标准读音。
     * 播放期间挂起离线识别，避免 TTS 外放被麦克风收进去后自己把字母「念」掉，
     * 保证消除只来自用户真正的跟读。
     */
    private fun speakLetter(ch: Char) {
        val engine = tts
        if (!ttsReady || engine == null) {
            Toast.makeText(this, "系统英文语音暂不可用，无法播放读音", Toast.LENGTH_SHORT).show()
            return
        }
        uiHandler.removeCallbacksAndMessages(null)   // 取消上一次的延迟恢复
        val id = "letter_${++utteranceSeq}"
        activeUtteranceId = id
        speechManager.setRecognitionSuspended(true)
        val spoken = PRONUNCIATION[ch] ?: ch.toString()
        if (engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            activeUtteranceId = null
            speechManager.setRecognitionSuspended(false)
        }
    }

    /** 读音播完/失败/被打断后，稍等尾音散去再恢复识别（只认最后一次播放） */
    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId == null || utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        uiHandler.postDelayed({
            if (activeUtteranceId == null) speechManager.setRecognitionSuspended(false)
        }, TTS_RESUME_DELAY_MS)
    }

    /** 统一刷新顶部状态 + 处理结束界面与加分动画 */
    private fun onStateUpdated() {
        val s = gameView.score
        if (s == 0) lastShownScore = 0
        scoreText.text = "得分: $s"
        if (s > lastShownScore) animateScorePop()
        lastShownScore = s

        livesText.text = "生命: " + "❤".repeat(gameView.lives)
        speedText.text = "速度档: ${gameView.speedLevel}档"

        if (gameView.gameOver) {
            if (inGame) {
                inGame = false
                speechManager.stop()
            }
            showGameOver(s)
        } else {
            gameOverOverlay.visibility = View.GONE
        }
        refreshStatus()
    }

    /** 得分 +1 时的弹动动画，让顶部计分更醒目、有反馈感 */
    private fun animateScorePop() {
        scoreText.animate().cancel()
        scoreText.scaleX = 1f
        scoreText.scaleY = 1f
        scoreText.animate()
            .scaleX(1.35f).scaleY(1.35f)
            .setDuration(110)
            .withEndAction {
                scoreText.animate().scaleX(1f).scaleY(1f).setDuration(110)
            }
    }

    /** 显示结束遮罩：淡入 + 卡片带回弹的缩放动画，并写入最高分 */
    private fun showGameOver(score: Int) {
        if (score > bestScore) {
            bestScore = score
            prefs.edit().putInt(KEY_BEST, bestScore).apply()
        }
        finalScoreText.text = score.toString()
        bestScoreText.text = "🏆 最高分: $bestScore"

        gameOverOverlay.visibility = View.VISIBLE
        gameOverOverlay.alpha = 0f
        gameOverOverlay.animate().alpha(1f).setDuration(280)

        gameOverCard.scaleX = 0.8f
        gameOverCard.scaleY = 0.8f
        gameOverCard.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(1.4f))
    }

    private fun startGame() {
        inGame = true
        bgImage.visibility = View.GONE
        // 每局按语音可用性决定模式，并清掉上一局可能残留的挂起状态
        activeUtteranceId = null
        uiHandler.removeCallbacksAndMessages(null)
        speechManager.setRecognitionSuspended(false)
        gameView.practiceMode = voiceDisabled || !modelReady
        // 进入游戏：显示顶部 HUD（得分/生命/速度档），隐藏交给游戏画布
        scoreText.visibility = View.VISIBLE
        livesText.visibility = View.VISIBLE
        speedText.visibility = View.VISIBLE
        gameView.startGame()
        startButton.text = "游戏中..."
        speechManager.start()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = when {
            gameView.gameOver -> "游戏结束！点击「再玩一次」"
            voiceDisabled -> "听音练习：点字母听标准读音（麦克风不可用，不计分）"
            !modelReady -> "正在准备离线语音模型（可先点字母听读音）…"
            inGame -> "念出字母消除 · 点字母听读音，跟读纠正发音"
            else -> "点击开始，念出字母消除；点字母可听标准读音"
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestRecordPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startGame()
            } else {
                // 没有麦克风就没有消除入口：降级为听音练习（点读、落地不扣命）
                voiceDisabled = true
                speechManager.disable("未授予麦克风权限，已切换为「听音练习」：点字母听读音，开启权限后可跟读消除。")
                startGame()
            }
        }
    }

    override fun onStop() {
        // 切后台停止读音，并确保识别不会因回调丢失而一直挂起
        tts?.stop()
        activeUtteranceId = null
        uiHandler.removeCallbacksAndMessages(null)
        speechManager.setRecognitionSuspended(false)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        speechManager.setRecognitionSuspended(false)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        speechManager.destroy()
    }

    companion object {
        private const val REQUEST_RECORD = 100
        private const val STATS_NAME = "letterdrop_stats"
        private const val KEY_BEST = "best_score"
        /** 读音播完后延迟恢复识别，留一点时间让外放尾音散掉 */
        private const val TTS_RESUME_DELAY_MS = 180L

        /**
         * 字母 -> 交给 TTS 拼读的词。用同音词而非单个字母字符，避免部分引擎
         * 把 "A" 读成弱读的 /ə/；这些词与 Vosk 的识别词表一致，
         * 让「听标准音 -> 跟读 -> 被判定」形成纠音闭环。
         */
        private val PRONUNCIATION: Map<Char, String> = mapOf(
            'A' to "ay", 'B' to "bee", 'C' to "see", 'D' to "dee",
            'E' to "ee", 'F' to "eff", 'G' to "gee", 'H' to "aitch",
            'I' to "eye", 'J' to "jay", 'K' to "kay", 'L' to "ell",
            'M' to "em", 'N' to "en", 'O' to "oh", 'P' to "pee",
            'Q' to "cue", 'R' to "are", 'S' to "ess", 'T' to "tea",
            'U' to "you", 'V' to "vee", 'W' to "double u", 'X' to "ex",
            'Y' to "why", 'Z' to "zee"
        )
    }
}

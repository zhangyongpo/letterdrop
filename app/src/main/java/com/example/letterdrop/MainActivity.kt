package com.example.letterdrop

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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

    /** 当前点读播放器；每次点击新建，用完成回调精确恢复识别 */
    private var letterPlayer: MediaPlayer? = null
    private var playSeq = 0

    /** 当前生效的播放 id，0 表示空闲；只认最后一次播放的回调 */
    @Volatile
    private var activePlayId = 0
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
        // 点击字母只播放内置读音，由用户跟读麦克风完成消除与纠音
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

        refreshStatus()
        speechManager.prepare()
    }

    /**
     * 点击字母：播放 APK 内置的字母发音，不做任何实时合成。
     * 音频来自 letterdrop-wx 主包 assets/audio/A~Z.mp3，已重命名为
     * res/raw/letter_a~z.mp3（Android 资源名只能小写），为带 ID3 头的 MP3。
     * 资源 id 用 R.raw 编译期常量直接引用，保证打包时不会被裁剪、也不会拼错名字。
     *
     * 播放期间挂起离线识别，避免内置读音外放被麦克风收进去后自己把字母「念」掉，
     * 保证消除只来自用户真正的跟读。
     */
    private fun speakLetter(ch: Char) {
        val index = ch.uppercaseChar() - 'A'
        uiHandler.removeCallbacksAndMessages(null)   // 取消上一次的延迟恢复
        releaseLetterPlayer()                        // 连点时先停上一条，避免叠音
        activePlayId = 0

        if (index !in 0..25) {
            speechManager.setRecognitionSuspended(false)
            return
        }
        val player = try {
            MediaPlayer.create(this, LETTER_RES_IDS[index])
        } catch (ignored: Throwable) {
            null
        }
        if (player == null) {
            speechManager.setRecognitionSuspended(false)
            return
        }

        val id = ++playSeq
        activePlayId = id
        letterPlayer = player
        speechManager.setRecognitionSuspended(true)

        player.setOnCompletionListener { finishLetterPlayback(id) }
        player.setOnErrorListener { _, _, _ ->
            finishLetterPlayback(id)
            true
        }
        try {
            player.start()
        } catch (ignored: Throwable) {
            finishLetterPlayback(id)
        }
    }

    /** 读音播完/失败后，稍等尾音散去再恢复识别（只认最后一次播放） */
    private fun finishLetterPlayback(id: Int) {
        if (id != activePlayId) return
        activePlayId = 0
        releaseLetterPlayer()
        uiHandler.postDelayed({
            if (activePlayId == 0) speechManager.setRecognitionSuspended(false)
        }, PLAYBACK_RESUME_DELAY_MS)
    }

    private fun releaseLetterPlayer() {
        letterPlayer?.let { p ->
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            try {
                if (p.isPlaying) p.stop()
            } catch (ignored: Throwable) {
            }
            p.release()
        }
        letterPlayer = null
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
        // 每局按语音可用性决定模式，并清掉上一局可能残留的读音/挂起状态
        releaseLetterPlayer()
        activePlayId = 0
        uiHandler.removeCallbacksAndMessages(null)
        speechManager.setRecognitionSuspended(false)
        gameView.practiceMode = voiceDisabled || !modelReady
        // 进入游戏：显示顶部 HUD（得分/生命/速度档），隐藏交给游戏画布
        scoreViewVisible(true)
        gameView.startGame()
        startButton.text = "游戏中..."
        speechManager.start()
        refreshStatus()
    }

    private fun scoreViewVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        scoreText.visibility = v
        livesText.visibility = v
        speedText.visibility = v
    }

    private fun refreshStatus() {
        statusText.text = when {
            gameView.gameOver -> "游戏结束！点击「再玩一次」"
            voiceDisabled -> "听音练习：点字母听读音（麦克风不可用，不计分）"
            !modelReady -> "正在准备离线语音模型（可先点字母听读音）…"
            inGame -> "念出字母消除 · 点字母听读音，跟读纠正发音"
            else -> "点击开始，念出字母消除；点字母可听读音"
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
        releaseLetterPlayer()
        activePlayId = 0
        uiHandler.removeCallbacksAndMessages(null)
        speechManager.setRecognitionSuspended(false)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLetterPlayer()
        activePlayId = 0
        uiHandler.removeCallbacksAndMessages(null)
        speechManager.setRecognitionSuspended(false)
        speechManager.destroy()
    }

    companion object {
        private const val REQUEST_RECORD = 100
        private const val STATS_NAME = "letterdrop_stats"
        private const val KEY_BEST = "best_score"
        /** 读音播完后延迟恢复识别，留一点时间让外放尾音散掉 */
        private const val PLAYBACK_RESUME_DELAY_MS = 180L

        /** A~Z 与 res/raw/letter_a~z.mp3 一一对应，编译期引用 */
        private val LETTER_RES_IDS = intArrayOf(
            R.raw.letter_a, R.raw.letter_b, R.raw.letter_c, R.raw.letter_d,
            R.raw.letter_e, R.raw.letter_f, R.raw.letter_g, R.raw.letter_h,
            R.raw.letter_i, R.raw.letter_j, R.raw.letter_k, R.raw.letter_l,
            R.raw.letter_m, R.raw.letter_n, R.raw.letter_o, R.raw.letter_p,
            R.raw.letter_q, R.raw.letter_r, R.raw.letter_s, R.raw.letter_t,
            R.raw.letter_u, R.raw.letter_v, R.raw.letter_w, R.raw.letter_x,
            R.raw.letter_y, R.raw.letter_z
        )
    }
}

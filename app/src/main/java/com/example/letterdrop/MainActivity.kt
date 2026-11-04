package com.example.letterdrop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
                refreshStatus()
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            },
            onReady = {
                modelReady = true
                refreshStatus()
            }
        )

        gameView.onStateChanged { onStateUpdated() }

        startButton.setOnClickListener {
            // 语音已降级时无需麦克风权限，直接进点字模式
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
            voiceDisabled -> "点字模式：点中掉落的字母即可消除"
            !modelReady -> "正在准备离线语音模型（首次启动约需几秒）…"
            inGame -> "念出字母（或点中字母）即可消除"
            else -> "点击开始，念出或点中掉落的字母"
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
                // 拒绝权限也照样能玩，降级到点字模式
                speechManager.disable("未授予麦克风权限，已切换为「点字模式」：点中字母即可消除。")
                startGame()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }

    companion object {
        private const val REQUEST_RECORD = 100
        private const val STATS_NAME = "letterdrop_stats"
        private const val KEY_BEST = "best_score"
    }
}

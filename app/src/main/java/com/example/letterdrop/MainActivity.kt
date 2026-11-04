package com.example.letterdrop

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var scoreText: TextView
    private lateinit var livesText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var settingsButton: Button
    private lateinit var speechManager: SpeechManager
    private lateinit var prefs: SharedPreferences

    private var modelReady = false
    private var voiceDisabled = false
    private var inGame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)
        scoreText = findViewById(R.id.scoreText)
        livesText = findViewById(R.id.livesText)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        settingsButton = findViewById(R.id.settingsButton)

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        applySpeed(prefs.getInt(KEY_SPEED, GameView.DEFAULT_LEVEL))

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

        gameView.onStateChanged {
            scoreText.text = "得分: ${gameView.score}"
            livesText.text = "生命: " + "❤".repeat(gameView.lives)
            if (gameView.gameOver) {
                startButton.text = "重新开始"
                inGame = false
                speechManager.stop()
            }
            refreshStatus()
        }

        settingsButton.setOnClickListener { showSettingsDialog() }

        startButton.setOnClickListener {
            // 语音已降级时无需麦克风权限，直接进点字模式
            if (!voiceDisabled && !hasRecordPermission()) {
                requestRecordPermission()
                return@setOnClickListener
            }
            startGame()
        }

        refreshStatus()
        speechManager.prepare()
    }

    /** 设置菜单：10 档掉落速度，选择后立即生效并自动保存 */
    private fun showSettingsDialog() {
        val wasPaused = gameView.paused
        gameView.paused = true

        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val label = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val seek = SeekBar(this).apply {
            max = 9
            progress = (gameView.speedLevel - 1).coerceIn(0, 9)
        }
        fun updateLabel(level: Int) {
            label.text = "掉落速度：第 $level 档\n1 档最慢，10 档最快；选择后立即生效并自动保存"
        }
        updateLabel(gameView.speedLevel)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                updateLabel(value + 1)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        container.addView(label)
        container.addView(seek)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(container)
            .setPositiveButton("保存") { _, _ -> applySpeed(seek.progress + 1) }
            .setNegativeButton("取消", null)
            .setOnDismissListener { gameView.paused = wasPaused }
            .show()
    }

    private fun applySpeed(level: Int) {
        val lv = level.coerceIn(1, 10)
        prefs.edit().putInt(KEY_SPEED, lv).apply()
        gameView.setSpeedLevel(lv)
        settingsButton.text = "设置 · ${lv}档"
    }

    private fun startGame() {
        inGame = true
        gameView.startGame()
        startButton.text = "游戏中..."
        speechManager.start()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = when {
            gameView.gameOver -> "游戏结束！点击重新开始"
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
        private const val PREF_NAME = "letterdrop_settings"
        private const val KEY_SPEED = "speed_level"
        private const val REQUEST_RECORD = 100
    }
}

package com.example.letterdrop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var scoreText: TextView
    private lateinit var livesText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var speechManager: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)
        scoreText = findViewById(R.id.scoreText)
        livesText = findViewById(R.id.livesText)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)

        speechManager = SpeechManager(
            this,
            onLetterHeard = { ch -> gameView.onLetterHeard(ch) },
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        )

        // 游戏状态变化 -> 刷新顶部 UI
        gameView.onStateChanged {
            scoreText.text = "得分: ${gameView.score}"
            livesText.text = "生命: " + "❤".repeat(gameView.lives)
            if (gameView.gameOver) {
                statusText.text = "游戏结束！点击重新开始"
                startButton.text = "重新开始"
                speechManager.stop()
            }
        }

        startButton.setOnClickListener {
            if (!hasRecordPermission()) {
                requestRecordPermission()
                return@setOnClickListener
            }
            startGame()
        }
    }

    private fun startGame() {
        gameView.startGame()
        statusText.text = "说出掉落的字母！"
        startButton.text = "游戏中..."
        speechManager.start()
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
        if (requestCode == REQUEST_RECORD &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startGame()
        } else {
            Toast.makeText(this, "需要麦克风权限才能识别读音", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }

    companion object {
        private const val REQUEST_RECORD = 100
    }
}

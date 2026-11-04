package com.example.letterdrop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Random
import kotlin.math.min

/**
 * 游戏画布：自绘 SurfaceView + 游戏线程。
 *
 * 1. 定时在顶部随机生成大写/小写字母并向下掉落；
 * 2. 念对字母（Vosk 离线识别）或点中字母 -> 消失动画；
 * 3. 落到地面 -> 扣一条命，命数为 0 时游戏结束；
 * 4. 掉落速度随得分自动提升：基础为第 5 档，每累计 20 分升 1 档，无上限。
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Runnable {

    private val holder: SurfaceHolder = getHolder()
    private var thread: Thread? = null
    private var running = false

    private val letters = mutableListOf<Letter>()
    private val lock = Any()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random()

    private var viewWidth = 0f
    private var viewHeight = 0f
    private var radius = 40f

    private var lastSpawn = 0L
    private var spawnInterval = 1500L   // 生成间隔(ms)，随时间缩短
    private var fallSpeed = BASE_FALL_SPEED  // 当前下落速度(px/ms)
    private var baseFallSpeed = BASE_FALL_SPEED

    var score = 0
        private set
    var lives = 3
        private set
    var gameOver = false
        private set

    /** 是否已开始：未开始时主页面只显示静态背景，不生成/掉落字母 */
    private var started = false

    /** 当前速度档位，基础为 5 档，随得分提升，无上限 */
    var speedLevel = BASE_LEVEL
        private set

    /** 打开设置弹窗时暂停，避免弹窗期间字母落地扣命 */
    @Volatile
    var paused = false

    /** 是否允许点中字母消除（默认开启，作为无语音服务时的保底玩法） */
    var touchEnabled = true

    private val stateListeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val colors = listOf(
        Color.parseColor("#E74C3C"),
        Color.parseColor("#3498DB"),
        Color.parseColor("#2ECC71"),
        Color.parseColor("#F39C12"),
        Color.parseColor("#9B59B6"),
        Color.parseColor("#1ABC9C")
    )

    init {
        holder.addCallback(this)
    }

    /** 注册状态变化回调（得分/生命/结束），用于刷新顶部 UI */
    fun onStateChanged(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    private fun notifyState() {
        mainHandler.post { stateListeners.forEach { it() } }
    }

    fun startGame() {
        synchronized(lock) {
            letters.clear()
            score = 0
            speedLevel = BASE_LEVEL
            baseFallSpeed = BASE_FALL_SPEED
            fallSpeed = BASE_FALL_SPEED
            started = true
        }
        lives = 3
        gameOver = false
        spawnInterval = 1500L
        lastSpawn = System.currentTimeMillis()
        notifyState()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        radius = min(viewWidth, viewHeight) / 12f
        running = true
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        thread?.join()
    }

    override fun run() {
        var lastTime = System.currentTimeMillis()
        while (running) {
            val now = System.currentTimeMillis()
            val dt = now - lastTime
            lastTime = now
            update(dt, now)
            draw()
            try {
                Thread.sleep(16)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun update(dt: Long, now: Long) {
        if (!started || gameOver || paused) return

        synchronized(lock) {
            // 生成新字母；生成间隔随时间缩短以提升密度（速度本身由得分档位决定）
            if (now - lastSpawn > spawnInterval) {
                spawnLetter()
                lastSpawn = now
                spawnInterval = (spawnInterval * 0.98f).toLong().coerceAtLeast(600L)
            }

            val dy = fallSpeed * dt
            val iterator = letters.iterator()
            while (iterator.hasNext()) {
                val l = iterator.next()
                if (l.popping) {
                    // 被念对 / 被点中：缩小直到消失
                    l.radius *= 0.82f
                    if (l.radius < 2f) {
                        iterator.remove()
                        bumpScore()
                        notifyState()
                    }
                    continue
                }
                l.y += dy
                // 落到地面 = 失败
                if (l.y + l.radius >= viewHeight) {
                    iterator.remove()
                    lives -= 1
                    notifyState()
                    if (lives <= 0) {
                        gameOver = true
                        notifyState()
                    }
                }
            }
        }
    }

    /** 答对一个字母：得分 +1，并依据得分重新计算速度档位（每 20 分升 1 档，无上限） */
    private fun bumpScore() {
        score += 1
        val level = BASE_LEVEL + score / 20
        speedLevel = level
        baseFallSpeed = speedForLevel(level)
        fallSpeed = baseFallSpeed
    }

    private fun spawnLetter() {
        val base = 'A' + random.nextInt(26)
        val char = if (random.nextBoolean()) base else base.lowercaseChar()
        val color = colors[random.nextInt(colors.size)]
        val x = radius + random.nextFloat() * (viewWidth - 2 * radius)
        letters.add(Letter(char, x, -radius, radius, color))
    }

    /** 语音识别回调：把念对的字母对应的掉落字母标记为“消失” */
    fun onLetterHeard(ch: Char) {
        synchronized(lock) {
            val target = letters.firstOrNull { !it.popping && it.name == ch }
            target?.popping = true
        }
    }

    /** 点字模式：点中字母即消除，优先消掉最靠近地面的那个 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchEnabled && !gameOver && !paused && event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (popLetterAt(event.x, event.y)) return true
        }
        return super.onTouchEvent(event)
    }

    private fun popLetterAt(x: Float, y: Float): Boolean {
        synchronized(lock) {
            // 热区略大于圆圈，手机上更好点
            val target = letters.filter { l ->
                if (l.popping) return@filter false
                val dx = l.x - x
                val dy = l.y - y
                dx * dx + dy * dy <= l.radius * l.radius * 1.7f
            }.maxByOrNull { it.y }
            target?.popping = true
            return target != null
        }
    }

    private fun draw() {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.parseColor("#1B1F2A"))
            synchronized(lock) {
                for (l in letters) {
                    paint.color = l.color
                    canvas.drawCircle(l.x, l.y, l.radius, paint)
                    paint.color = Color.WHITE
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = l.radius * 1.2f
                    val fm = paint.fontMetrics
                    val textY = l.y - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(l.char.toString(), l.x, textY, paint)
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    companion object {
        /** 基础速度档位：最低速即原第 5 档 */
        const val BASE_LEVEL = 5
        private const val MIN_SPEED = 0.06f
        private const val MAX_SPEED = 0.40f
        private val BASE_FALL_SPEED = speedForLevel(BASE_LEVEL)

        /**
         * 档位 -> 下落速度 px/ms。
         * 第 5 档(基础)约 0.211，每升 1 档约 +0.038；每 20 分升 1 档，档位无上限，速度随之线性增长。
         */
        fun speedForLevel(level: Int): Float {
            val l = (level - 1).coerceAtLeast(0)
            return MIN_SPEED + l * (MAX_SPEED - MIN_SPEED) / 9f
        }
    }
}

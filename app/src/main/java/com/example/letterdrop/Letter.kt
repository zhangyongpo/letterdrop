package com.example.letterdrop

/**
 * 一个正在掉落的字母。
 * @param char   屏幕上显示的字符（大写或小写随机）
 * @param x      圆心 x 坐标
 * @param y      圆心 y 坐标
 * @param radius 半径（被念对后会逐渐缩小消失）
 * @param color  圆圈颜色
 * @param popping 是否正在“被念中”的消失动画状态（只有语音念对才会置为 true）
 */
class Letter(
    val char: Char,
    var x: Float,
    var y: Float,
    var radius: Float,
    val color: Int,
    var popping: Boolean = false
) {
    /** 该字母对应的“读音目标”：无论大小写，念的都是字母名 A~Z */
    val name: Char get() = char.uppercaseChar()

    /** 被点击听音后的高亮截止时间戳（仅视觉反馈，不影响消除判定） */
    var tapHighlightUntil: Long = 0L
}

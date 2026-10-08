package com.example.studentlookup.service

import androidx.annotation.DrawableRes
import com.example.studentlookup.R

/**
 * 悬浮球样式。长按球 → 「更换悬浮球样式」可现场切换（带预览），选中即保存到本机。
 *
 * ⚠️ 改球的大小只需要改这里的 [widthDp] / [heightDp]：
 * - 窗口尺寸、内部文字、贴边吸附位置都用同一组值，改一处即可；
 * - 不影响卡片、菜单、识别等任何其它功能；
 * - res/layout/floating_ball.xml 里的尺寸不生效（会被 WindowManager 参数覆盖），不用动它。
 */
enum class BallStyle(
    val label: String,
    val widthDp: Int,
    val heightDp: Int,
    val text: String,
    val textSp: Float,
    @DrawableRes val bg: Int
) {
    RING("① 圆形 T · 白边 · 字撑满", 48, 48, "T", 36f, R.drawable.ball_bg),
    PLAIN("② 圆形 T · 无边框", 48, 48, "T", 36f, R.drawable.ball_bg_plain),
    SQUIRCLE("③ 圆角方形 T", 48, 48, "T", 36f, R.drawable.ball_squircle),
    PILL("④ 胶囊「查」", 66, 40, "查", 22f, R.drawable.ball_pill),
    TINY("⑤ 迷你圆点（无字）", 30, 30, "", 1f, R.drawable.ball_bg_plain),
    TINY_RING("⑥ 小圆 T · 白边", 38, 38, "T", 28f, R.drawable.ball_bg)
}

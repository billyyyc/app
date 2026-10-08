package com.example.studentlookup.util

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量「运行记录」：把关键步骤同时写进 logcat 和本地偏好设置。
 *
 * 目的：手机上出问题时（点悬浮球没反应、App 自己退出…），用户不必连电脑抓 logcat——
 * 打开首页就能看到最近发生了什么，并一键复制发给技术支持。
 */
object Diag {

    private const val PREF = "app_state"
    private const val KEY = "diag_log"
    private const val MAX_LINES = 60

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun log(ctx: Context, tag: String, msg: String) {
        Log.d("SLK-$tag", msg)
        try {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val line = "${fmt.format(Date())} [$tag] $msg"
            val old = sp.getString(KEY, "").orEmpty()
            // 连续重复的同一条（例如每次回到前台都刷一遍权限状态）不重复记
            val newest = old.lineSequence().firstOrNull().orEmpty()
            if (newest.endsWith("[$tag] $msg")) return
            val lines = (listOf(line) + old.split('\n'))
                .filter { it.isNotBlank() }
                .take(MAX_LINES)
            sp.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (t: Throwable) {
            Log.w("SLK-Diag", "写入运行记录失败: ${t.message}")
        }
    }

    fun text(ctx: Context, maxLines: Int = 20): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .take(maxLines)
            .joinToString("\n")

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

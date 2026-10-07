package com.example.studentlookup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.studentlookup.App
import com.example.studentlookup.R
import com.example.studentlookup.match.Matcher
import com.example.studentlookup.ocr.OcrFallback
import com.example.studentlookup.ui.MenuAction
import com.example.studentlookup.ui.ResultCardView
import com.example.studentlookup.util.RomUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingBallService : Service() {

    companion object {
        private const val CHANNEL_ID = "float_channel"
        private const val NOTIF_ID = 1
        var isRunning = false
    }

    private lateinit var wm: WindowManager
    private lateinit var ball: android.view.View
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        // 悬浮球仅显示悬浮视图，不涉及录屏；Android 14 上以 mediaProjection 类型启动 FGS
        // 若无有效录屏授权会抛 SecurityException 导致闪退（曾致进程崩溃、无障碍服务被判"无法运行"），
        // 因此用无类型的两参 startForeground。
        startForeground(NOTIF_ID, buildNotification())
        addBall()
        isRunning = true
    }

    override fun onDestroy() {
        if (::ball.isInitialized) wm.removeView(ball)
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addBall() {
        ball = LayoutInflater.from(this).inflate(R.layout.floating_ball, null)
        params = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(240)
        }
        ball.setOnTouchListener(DragListener())
        ball.setOnClickListener { onBallClick() }
        ball.setOnLongClickListener { showMenu(); true }
        wm.addView(ball, params)
    }

    private fun onBallClick() {
        val acc = LookupAccessibilityService.instance
        if (acc == null) {
            toast("请先在系统设置中开启「学员速查」无障碍服务")
            openAccessibilitySettings()
            return
        }
        if (!acc.isInWechat()) {
            toast("请在微信对话界面使用")
            return
        }
        val title = acc.readConversationTitle()
        if (!title.isNullOrBlank()) {
            query(title)
        } else {
            // 兜底：截图 OCR（放到 IO 线程，避免截屏等待阻塞主线程）
            toast("无障碍未读到标题，尝试截图识别…")
            CoroutineScope(Dispatchers.IO).launch {
                val text = OcrFallback.recognizeTop(this@FloatingBallService)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (text.isNullOrBlank()) {
                        toast("未能识别，请长按悬浮球手动搜索")
                    } else {
                        query(text)
                    }
                }
            }
        }
    }

    private fun query(raw: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val all = (applicationContext as App).database.studentDao().getAll()
            val result = Matcher.match(raw, all)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                ResultCardView.show(this@FloatingBallService, result,
                    onManualSearch = { q -> query(q) },
                    onDump = { LookupAccessibilityService.instance?.dumpNodes() }
                )
            }
        }
    }

    private fun showMenu() {
        ResultCardView.showMenu(this) {
            when (it) {
                MenuAction.MANUAL -> ResultCardView.showManualSearch(this) { q -> query(q) }
                MenuAction.REFRESH -> toast("数据已在导入时更新，无需刷新")
                MenuAction.HIDE -> { stopSelf() }
                MenuAction.SETTINGS -> openAccessibilitySettings()
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    private inner class DragListener : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var paramX = 0
        private var paramY = 0
        private var moved = false

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    paramX = params.x
                    paramY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    params.x = paramX + dx.toInt()
                    params.y = paramY + dy.toInt()
                    wm.updateViewLayout(ball, params)
                }
                MotionEvent.ACTION_UP -> {
                    // 贴边：吸附到最近一侧
                    val screenW = resources.displayMetrics.widthPixels
                    params.x = if (params.x < screenW / 2) 0 else screenW - dp(56)
                    wm.updateViewLayout(ball, params)
                    if (moved) return true // 拖动不触发点击
                }
            }
            return false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("学员速查")
            .setContentText("悬浮球运行中 · ${RomUtils.getRom().name}")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

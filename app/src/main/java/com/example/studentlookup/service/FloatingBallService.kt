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
import android.util.Log
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
import com.example.studentlookup.util.AccessibilitySupport
import com.example.studentlookup.util.Diag
import com.example.studentlookup.util.RomUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingBallService : Service() {

    companion object {
        private const val TAG = "SLK-Ball"
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
        // 最外层兜底：这里抛出的异常绝不能冒出去。未捕获异常会杀死整个进程，
        // 同进程的无障碍服务会被系统判为「无法运行」并自动关掉开关——
        // 用户看到的现象就是「App 退出 + 权限被关 + 点悬浮球没反应」。
        try {
            Diag.log(
                this, "Ball",
                "点击：服务实例=${acc != null} 已开启=${AccessibilitySupport.isEnabled(this)}"
            )
            if (acc == null) {
                // 绝不静默失败：无论哪种原因，都给出看得见、点得动的卡片
                handleAccessibilityNotRunning()
                return
            }
            if (!acc.isInWechat()) {
                Diag.log(this, "Ball", "不在微信（当前包名 ${acc.currentPackage}）")
                notifyCard(
                    "当前不在微信对话界面",
                    "请先打开要查询的学员/家长的微信聊天窗口，再点悬浮球。\n" +
                        "也可以直接在这里手动搜索姓名。",
                    listOf("手动搜索" to { showManualSearch() }, "知道了" to {})
                )
                return
            }
            val title = acc.readConversationTitle()
            Diag.log(this, "Ball", "读到会话标题：${title ?: "（空）"}")
            if (!title.isNullOrBlank()) {
                query(title)
            } else {
                // 兜底：截图 OCR（只使用已授权的截屏服务，绝不在这里后台启动它）
                tryOcr()
            }
        } catch (t: Throwable) {
            Diag.log(this, "Ball", "点击处理异常：${t.javaClass.simpleName} ${t.message}")
            toast("出错了：${t.javaClass.simpleName}（已记录，可在首页查看运行记录）")
        }
    }

    /** 无障碍服务「没连上」时的处理：能自愈就自愈，否则给逐步可见的修复引导。 */
    private fun handleAccessibilityNotRunning() {
        if (!AccessibilitySupport.isEnabled(this)) {
            notifyCard(
                "无障碍服务未开启",
                "请先开启「学员速查」的无障碍服务，再点悬浮球。\n\n" +
                    "开启路径：系统设置 → 无障碍 → 已下载的服务 → 学员速查 → 打开开关。",
                listOf(
                    "去开启无障碍" to { openAccessibilitySettings() },
                    "手动搜索" to { showManualSearch() }
                )
            )
            return
        }

        // 已开启但没真正绑定（重装 / 被系统清理后的假死态）
        if (!AccessibilitySupport.canSelfRepair(this)) {
            Diag.log(this, "Ball", "无障碍已开启但未连接，且无自愈权限 → 引导手动关→开")
            notifyCard(
                "无障碍服务未真正运行",
                "系统里显示已开启，但没有把它绑定起来（重装 App 或被系统清理后常见）。\n\n" +
                    "修复：打开系统设置 → 无障碍 → 已下载的服务 → 学员速查 → 先关闭、再打开。\n" +
                    "修好前可以先用下面的手动搜索。",
                listOf(
                    "去无障碍设置" to { openAccessibilitySettings() },
                    "手动搜索" to { showManualSearch() }
                )
            )
            return
        }

        toast("正在重新连接无障碍服务…")
        CoroutineScope(Dispatchers.Main).launch {
            val requested = AccessibilitySupport.rebind(this@FloatingBallService)
            delay(1200)
            val nowConnected = LookupAccessibilityService.instance != null
            Diag.log(this@FloatingBallService, "Ball", "自动重连：请求=$requested 已连接=$nowConnected")
            if (nowConnected) {
                toast("已重新连接，请再点一次悬浮球")
            } else if (requested) {
                notifyCard(
                    "正在重新连接",
                    "已请求系统重新绑定无障碍服务，请等 1~2 秒后再点悬浮球。\n" +
                        "若仍无效，请在系统设置 → 无障碍里把本服务「关→开」一次。",
                    listOf(
                        "去无障碍设置" to { openAccessibilitySettings() },
                        "手动搜索" to { showManualSearch() }
                    )
                )
            } else {
                notifyCard(
                    "无障碍服务未真正运行",
                    "自动重连失败。请打开系统设置 → 无障碍 → 已下载的服务 → 学员速查，" +
                        "先关闭、再打开。\n修好前可以先用下面的手动搜索。",
                    listOf(
                        "去无障碍设置" to { openAccessibilitySettings() },
                        "手动搜索" to { showManualSearch() }
                    )
                )
            }
        }
    }

    /** 无障碍读不到标题时的 OCR 兜底。 */
    private fun tryOcr() {
        // 只有用户已在 App 内明确授权、且截屏服务正在运行时才走 OCR；
        // 不在后台启动截屏服务（会被系统拒绝，且可能拖垮进程）。
        if (ScreenCaptureService.instance == null) {
            Diag.log(this, "Ball", "读不到标题，且截图识别未开启 → 给出手动搜索")
            notifyCard(
                "无法读取微信标题",
                "无障碍没有读到会话标题。\n\n" +
                    "① 最省事：直接手动搜索姓名；\n" +
                    "② 或回 App 首页点「开启截图识别」后重试（需授权屏幕采集，仅用于识别标题）；\n" +
                    "③ 想一劳永逸：在结果卡片点「诊断」，把微信标题控件的 id 记下来（首页有说明）。",
                listOf(
                    "手动搜索" to { showManualSearch() },
                    "开启截图识别" to { requestCapturePermission() }
                )
            )
            return
        }
        toast("正在截图识别…")
        CoroutineScope(Dispatchers.IO).launch {
            val text = OcrFallback.recognizeTop(this@FloatingBallService)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                Diag.log(this@FloatingBallService, "Ball", "截图识别结果：${text ?: "（空）"}")
                if (text.isNullOrBlank()) {
                    notifyCard(
                        "未能识别",
                        "截图识别没有读到姓名。请手动输入姓名搜索。",
                        listOf("手动搜索" to { showManualSearch() })
                    )
                } else {
                    query(text)
                }
            }
        }
    }

    private fun requestCapturePermission() {
        runCatching {
            startActivity(
                Intent(this, com.example.studentlookup.ui.CapturePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open capture permission failed: ${it.message}") }
    }

    private fun showManualSearch() {
        ResultCardView.showManualSearch(this) { q -> query(q) }
    }

    private fun notifyCard(title: String, message: String, actions: List<Pair<String, () -> Unit>>) {
        ResultCardView.showNotice(this, title, message, actions)
    }

    private fun query(raw: String) {
        // 隐藏的运行时校准入口：手动搜索框里输入 `id:com.tencent.mm:id/xxx`
        if (raw.startsWith("id:")) {
            val id = raw.removePrefix("id:").trim()
            if (id.isNotEmpty()) {
                AccessibilitySupport.setTitleCandidateIds(this, listOf(id))
                toast("已记录标题控件 id，请再点一次悬浮球")
            }
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val all = (applicationContext as App).database.studentDao().getAll()
            val result = Matcher.match(raw, all)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                ResultCardView.show(this@FloatingBallService, result,
                    onManualSearch = { q -> query(q) },
                    onDump = {
                        val dump = LookupAccessibilityService.instance?.dumpNodes()
                        if (dump != null) {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("dump", dump)
                            )
                        }
                        dump
                    }
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

package com.example.studentlookup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.example.studentlookup.data.model.Student
import com.example.studentlookup.match.Matcher
import com.example.studentlookup.match.NameNormalizer
import com.example.studentlookup.ocr.OcrFallback
import com.example.studentlookup.ui.MenuAction
import com.example.studentlookup.ui.ResultCardView
import com.example.studentlookup.util.AccessibilitySupport
import com.example.studentlookup.util.Diag
import com.example.studentlookup.util.RomUtils
import com.example.studentlookup.util.TermUtils
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
        // 悬浮球只显示一个悬浮视图，不涉及录屏，所以用「无类型」的两参 startForeground
        // Android 14 起前台服务必须声明并传入类型，否则 startForeground 抛
        // MissingForegroundServiceTypeException（这就是悬浮球一直起不来的根因）。
        // 悬浮球不属于任何既有类型，故用 specialUse（清单里已声明同名类型与用途说明）。
        // 前台服务是「进程不被 ColorOS 清掉」的关键；万一启动失败也不能崩，要如实记下来。
        val fgOk = try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            true
        } catch (t: Throwable) {
            Diag.log(this, "Ball", "startForeground 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
        if (!fgOk) {
            Toast.makeText(
                this,
                "悬浮球启动被系统拒绝（前台服务不可用），请把运行记录发给技术支持",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }
        if (!addBall()) {
            stopSelf()
            return
        }
        isRunning = true
        Diag.log(this, "Ball", "悬浮球已显示（前台服务已启动）")
        // 启动悬浮球时顺手自愈：ColorOS 清掉进程后无障碍常处于「开着但没绑上」的状态
        CoroutineScope(Dispatchers.Main).launch {
            if (LookupAccessibilityService.instance == null) {
                when {
                    !AccessibilitySupport.canSelfRepair(this@FloatingBallService) ->
                        Diag.log(this@FloatingBallService, "Ball", "无障碍未连接，且无自愈权限")
                    AccessibilitySupport.isEnabled(this@FloatingBallService) -> {
                        Diag.log(this@FloatingBallService, "Ball", "无障碍未连接 → 自动重绑")
                        AccessibilitySupport.rebind(this@FloatingBallService)
                    }
                    else -> {
                        Diag.log(this@FloatingBallService, "Ball", "无障碍被关闭 → 自动开启")
                        AccessibilitySupport.rebind(this@FloatingBallService)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::ball.isInitialized) {
            try {
                wm.removeView(ball)
            } catch (t: Throwable) {
                Log.w(TAG, "removeView: ${t.message}")
            }
        }
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 调试入口（仅 adb / 本应用可触发；服务未导出）：
        //   adb shell am startservice -n com.example.studentlookup/.service.FloatingBallService \
        //       --es debug_query "陈怡彤妈"
        intent?.getStringExtra("debug_query")?.takeIf { it.isNotBlank() }?.let {
            Diag.log(this, "Ball", "调试验证查询：$it")
            // 走和 OCR 完全一样的处理链路（含多段备注拆分）
            handleOcrText(it)
        }
        // 调试入口：直接弹出悬浮球样式选择（用于出效果图）
        if (intent?.getStringExtra("debug_ball") != null) {
            showBallStylePicker()
        }
        // 调试入口：直接出联想候选（用于出效果图）
        intent?.getStringExtra("debug_suggest")?.let {
            showManualSearch()
            CoroutineScope(Dispatchers.Main).launch {
                delay(600)
                liveQuery(it)
            }
        }
        // 调试入口：对指定图片跑一次 OCR（用于评估识别准确率，不弹卡片）
        intent?.getStringExtra("debug_ocr_file")?.takeIf { it.isNotBlank() }?.let { file ->
            CoroutineScope(Dispatchers.IO).launch {
                val bmp = runCatching {
                    android.graphics.BitmapFactory.decodeFile(
                        java.io.File(filesDir, file).absolutePath
                    )
                }.getOrNull()
                if (bmp == null) {
                    Diag.log(this@FloatingBallService, "OCR", "调试图片读不到：$file")
                    return@launch
                }
                val acc = LookupAccessibilityService.instance
                val skip = acc?.statusBarHeightPx() ?: 140
                val t = OcrFallback.titleCandidates(
                    this@FloatingBallService, bmp, skip, (skip * 1.2f).toInt()
                )
                Diag.log(
                    this@FloatingBallService, "OCR",
                    "调试识别[$file] 候选=${t.joinToString(" | ")} 尺寸=${bmp.width}x${bmp.height}"
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addBall(): Boolean {
        ball = LayoutInflater.from(this).inflate(R.layout.floating_ball, null)
        val st = ballStyle()
        params = WindowManager.LayoutParams(
            dp(st.widthDp), dp(st.heightDp),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认停在屏幕右边（用户要求）
            x = resources.displayMetrics.widthPixels - dp(st.widthDp)
            y = dp(240)
        }
        applyBallStyle()
        ball.setOnTouchListener(DragListener())
        ball.setOnClickListener { onBallClick() }
        ball.setOnLongClickListener { showMenu(); true }
        // 悬浮窗权限被系统收回时，addView 会抛异常；这里兜住，绝不因此崩溃
        try {
            wm.addView(ball, params)
        } catch (t: Throwable) {
            Diag.log(this, "Ball", "悬浮球无法显示：${t.javaClass.simpleName} ${t.message}")
            toast("悬浮球无法显示：请到系统设置里打开「悬浮窗」权限")
            return false
        }
        return true
    }

    // ---- 悬浮球样式 ----

    private fun appPrefs() = getSharedPreferences("app_state", MODE_PRIVATE)

    private fun ballStyle(): BallStyle =
        BallStyle.entries.getOrElse(appPrefs().getInt("ball_style", 0)) { BallStyle.RING }

    /** 把当前样式应用到球上（文字、字号、背景、窗口尺寸），并同步窗口参数 */
    private fun applyBallStyle() {
        val st = ballStyle()
        val tv = ball.findViewById<android.widget.TextView>(R.id.ball_text) ?: return
        tv.text = st.text
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, st.textSp)
        tv.setTextColor(0xE6FFFFFF.toInt())
        tv.includeFontPadding = false
        tv.setBackgroundResource(st.bg)
        if (::params.isInitialized) {
            params.width = dp(st.widthDp)
            params.height = dp(st.heightDp)
            val screenW = resources.displayMetrics.widthPixels
            if (params.x + params.width > screenW) params.x = screenW - params.width
            runCatching { wm.updateViewLayout(ball, params) }
        }
    }

    private fun setBallStyle(index: Int) {
        appPrefs().edit().putInt("ball_style", index).apply()
        applyBallStyle()
        Diag.log(this, "Ball", "切换样式：${BallStyle.entries.getOrNull(index)?.label}")
        toast("已切换：${BallStyle.entries.getOrNull(index)?.label ?: ""}")
    }

    /** 样式预览（每行：左预览 + 右说明），点行即切换 */
    private fun showBallStylePicker() {
        val styles = BallStyle.entries.mapIndexed { i, st ->
            Triple(st.label, ballPreview(st), i == appPrefs().getInt("ball_style", 0))
        }
        ResultCardView.showBallPicker(this, styles) { index -> setBallStyle(index) }
    }

    /** 造一个用于预览的球视图（固定放在 96x60 的框里居中） */
    private fun ballPreview(st: BallStyle): android.view.View {
        val box = android.widget.FrameLayout(this)
        val tv = android.widget.TextView(this).apply {
            text = st.text
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, st.textSp)
            setTextColor(0xE6FFFFFF.toInt())
            setBackgroundResource(st.bg)
        }
        box.addView(tv, android.widget.FrameLayout.LayoutParams(dp(st.widthDp), dp(st.heightDp), Gravity.CENTER))
        return box
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
                Diag.log(
                    this, "Ball",
                    "不在微信（活动窗口=${acc.activePackage() ?: "null"} " +
                        "事件包名=${acc.currentPackage ?: "null"}）"
                )
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
        // 有自愈权限（install 时已授予）：不论「被系统关掉」还是「开着没连上」，直接自己修好
        if (AccessibilitySupport.canSelfRepair(this)) {
            toast("正在自动开启无障碍服务…")
            CoroutineScope(Dispatchers.Main).launch {
                AccessibilitySupport.rebind(this@FloatingBallService)
                delay(1500)
                val ok = LookupAccessibilityService.instance != null
                Diag.log(this@FloatingBallService, "Ball", "点击时自动修复：已连接=$ok")
                if (ok) {
                    toast("已自动开启，请再点一次悬浮球")
                } else {
                    notifyCard(
                        "自动开启没成功",
                        "请打开系统设置 → 无障碍 → 已下载的服务 → 学员速查，" +
                            "先关闭、再打开一次。修好前可以先用下面的手动搜索。",
                        listOf(
                            "去无障碍设置" to { openAccessibilitySettings() },
                            "手动搜索" to { showManualSearch() }
                        )
                    )
                }
            }
            return
        }
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
        // 主路径：Android 11+ 由无障碍服务直接截屏识别，不需要任何额外授权、也不会有进程崩溃风险。
        // （本机实测微信完全不向无障碍暴露控件树，所以这条路径就是微信里的唯一自动识别方式。）
        val acc = LookupAccessibilityService.instance
        if (acc != null && acc.canTakeScreenshotNow()) {
            // 先给一张看得见的"识别中"卡片：识别要 1~3 秒，只弹 toast 用户会以为没反应
            ResultCardView.showNotice(
                this, "正在识别微信标题…", "正在截图识别，请稍候（约 1~3 秒）",
                listOf("改成手动搜索" to { showManualSearch() })
            )
            CoroutineScope(Dispatchers.Main).launch {
                val skip = acc.statusBarHeightPx()
                val candidates = LinkedHashSet<String>()
                var captured = false
                var scored = 0
                // 第一帧只用 2x/4x（快）；若在学员库里一无所获，再抓一帧补 3x/1x
                val plans = listOf(listOf("2x" to 2, "4x" to 4), listOf("3x" to 3, "1x" to 1))
                for ((frame, plan) in plans.withIndex()) {
                    val full = acc.captureScreen()
                    if (full != null) {
                        captured = true
                        val list = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            OcrFallback.titleCandidates(
                                this@FloatingBallService, full, skip, (skip * 1.2f).toInt(), plan
                            )
                        }
                        candidates.addAll(list)
                        runCatching { full.recycle() }
                        scored = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            val all = allStudents()
                            candidates.maxOfOrNull { scoreText(it, all) } ?: 0
                        }
                    }
                    if (scored > 0) break
                    if (frame == 0) delay(180)
                }
                if (!captured || candidates.isEmpty()) {
                    Diag.log(this@FloatingBallService, "Ball", "无障碍截屏失败/无候选 → 转手动搜索")
                    notifyCard(
                        "无法读取微信标题",
                        "自动识别没成功，请手动输入姓名搜索。",
                        listOf("手动搜索" to { showManualSearch() })
                    )
                    return@launch
                }
                val best = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val all = allStudents()
                    val raw = candidates.maxByOrNull { scoreText(it, all) } ?: candidates.first()
                    // 用学到的字形纠正再试一次，谁在库里对得上就用谁
                    val fixed = applyCharFixes(raw)
                    if (fixed != raw && scoreText(fixed, all) > scoreText(raw, all)) fixed else raw
                }
                Diag.log(
                    this@FloatingBallService, "Ball",
                    "OCR 候选=${candidates.joinToString(" | ")} → 选中=$best"
                )
                handleOcrText(best)
            }
            return
        }

        // 旧路径（Android 10 及以下）：MediaProjection 截屏，需要用户单独授权。
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
        ResultCardView.showManualSearch(
            this,
            onSearch = { q -> query(q) },
            onLiveSearch = { text -> liveQuery(text) }
        )
    }

    /**
     * 处理 OCR 结果。一个家长可能有多个孩子，微信备注常写成
     * 「卢映彤,卢怡彤,卢柳含」这种逗号分隔的多段，所以先按分隔符拆开逐段匹配。
     * 认准的都列出来；认不准的给出「点一下纠正」的入口（纠正一次就记住）。
     */
    private fun handleOcrText(ocr: String) {
        val segs = splitSegments(ocr)
        if (segs.size < 2) {
            query(ocr, fromOcr = true, prefill = ocr)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val all = allStudents()
            val fixes = fixMap()
            val hits = LinkedHashSet<String>()
            val unresolved = ArrayList<String>()
            // 近似命中的：学生名 -> OCR 原文片段（点名字确认后就记住 / 学会字形纠正）
            val approx = HashMap<String, String>()
            for (seg in segs) {
                // 先查纠正记忆，再用学到的字形纠正
                val resolved = fixes[seg] ?: applyCharFixes(seg)
                val r = Matcher.match(resolved, all)
                if (r.found && !r.approximate && r.byName.size == 1) {
                    hits.add(r.byName.keys.first())
                    continue
                }
                // 认不准的：用学员库找最像的（三个字里错一个 → 距离 1），作为「待确认」候选给出。
                // 关键：要跳过"本轮已经用掉的姓名"——否则像 叶莞宜 这种情况，
                // 最像的 叶沛宜(距离1) 会先被选中（可它已经对应第一个孩子），第三个孩子就被丢掉。
                val pick = Matcher.rankSimilar(resolved, all, 6)
                    .firstOrNull { it.second <= 1 && it.first.length == resolved.length && it.first !in hits }
                if (pick != null) {
                    hits.add(pick.first)
                    approx[pick.first] = seg
                } else if (fixes[seg] == null) {
                    unresolved.add(seg)
                }
            }
            Diag.log(
                this@FloatingBallService, "OCR",
                "多段备注：命中=$hits 未认准=${unresolved}"
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (hits.isEmpty()) {
                    query(ocr, fromOcr = true, prefill = ocr)
                    return@withContext
                }
                val rows = all.filter { it.name in hits }
                currentQ?.let { history.addLast(it) }
                currentQ = Q(ocr, true, ocr)
                // 多个孩子 → 直接给网页版「多人查询」那种对照表（一行一个孩子，一列一个学期）
                val low = rows.minOf { TermUtils.sortKey(it.term) }
                val high = rows.maxOf { TermUtils.sortKey(it.term) }
                val terms = all.map { it.term }.distinct()
                    .filter { TermUtils.sortKey(it) in low..high }
                    .sortedByDescending { TermUtils.sortKey(it) }
                    .map { TermUtils.clean(it) }
                val students = rows.groupBy { it.name }
                    .map { (n, rs) ->
                        ResultCardView.MatrixStudent(
                            name = n,
                            byTerm = rs.groupBy { TermUtils.clean(it.term) }
                                .mapValues { it.value.first() },
                            approximate = approx.containsKey(n)
                        )
                    }
                    .sortedBy { it.name }
                ResultCardView.showMatrix(
                    this@FloatingBallService,
                    title = "识别到 ${students.size} 个孩子" +
                        if (approx.isEmpty()) "（点姓名看详情）" else "（≈ 为按识别结果推断，点姓名确认）",
                    terms = terms,
                    students = students,
                    onPickName = { n ->
                        // 用户确认了近似结果 → 永久记住，并学会字形纠正（钛→铱）
                        approx[n]?.let { seg ->
                            rememberFix(seg, n)
                            toast("已记住：$seg → $n")
                        }
                        picked(n)
                    },
                    onBack = backAction()
                )
            }
        }
    }

    /** 纠正某个认不准的片段：预填到手动搜索框，改好后搜索即被记住 */
    private fun startCorrection(segment: String) {
        Diag.log(this, "Ball", "纠正片段：$segment")
        ResultCardView.showManualSearch(
            this,
            initialText = segment,
            onSearch = { typed ->
                rememberFix(segment, typed)
                toast("已记住：$segment → $typed")
                picked(typed)
            },
            onLiveSearch = { text -> liveQuery(text) }
        )
    }

    private fun notifyCard(title: String, message: String, actions: List<Pair<String, () -> Unit>>) {
        ResultCardView.showNotice(this, title, message, actions)
    }

    // ---- 查询 / 返回上一级 ----

    private data class Q(val raw: String, val fromOcr: Boolean, val prefill: String)

    private val history = ArrayDeque<Q>()
    private var currentQ: Q? = null

    /** 用户点了卡片上的东西（候选/搜索/纠正）→ 记录当前状态，便于「返回」 */
    private fun picked(newRaw: String, newFromOcr: Boolean = false) {
        Diag.log(this, "Ball", "点选：$newRaw")
        query(newRaw, newFromOcr, "", push = true)
    }

    private fun backAction(): (() -> Unit)? {
        if (history.isEmpty()) return null
        return {
            val prev = history.removeLastOrNull()
            Diag.log(this, "Ball", "返回上一步")
            if (prev != null) {
                currentQ = null
                query(prev.raw, prev.fromOcr, prev.prefill, push = false)
            }
        }
    }

    private fun query(raw: String, fromOcr: Boolean = false, prefill: String = "", push: Boolean = true) {
        // 隐藏的运行时校准入口：手动搜索框里输入 `id:com.tencent.mm:id/xxx`
        if (raw.startsWith("id:")) {
            val id = raw.removePrefix("id:").trim()
            if (id.isNotEmpty()) {
                AccessibilitySupport.setTitleCandidateIds(this, listOf(id))
                toast("已记录标题控件 id，请再点一次悬浮球")
            }
            return
        }
        // 一次输入多个名字（用 , ， 、 / 分隔）→ 直接走「多人对照表」
        if (splitSegments(raw).size >= 2) {
            handleOcrText(raw)
            return
        }
        if (push) currentQ?.let { history.addLast(it) }
        showQuery(Q(raw, fromOcr, prefill))
    }

    private fun showQuery(q: Q) {
        currentQ = q
        // OCR 纠正记忆：同一个备注名被认错过一次后，下次直接给正确姓名
        val key = if (q.fromOcr) q.raw else null
        val resolved = if (key != null) fixMap()[key] ?: q.raw else q.raw
        if (key != null && resolved != q.raw) {
            Diag.log(this, "OCR", "命中纠正记忆：${q.raw} -> $resolved")
        }
        CoroutineScope(Dispatchers.IO).launch {
            val all = allStudents()
            val result = Matcher.match(resolved, all)
            val siblings = computeSiblings(result, all)
            val grid = if (result.byName.size == 1) {
                buildGrid(all.map { it.term }.distinct(), result.byName.values.first())
            } else emptyList()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                ResultCardView.show(
                    this@FloatingBallService, result,
                    onManualSearch = { v -> picked(v) },
                    prefill = q.prefill,
                    siblings = siblings,
                    onPick = { picked ->
                        if (key != null) rememberFix(key, picked)
                        picked(picked)
                    },
                    unresolved = if (key != null && !result.found) listOf(q.raw) else emptyList(),
                    onCorrect = { seg -> startCorrection(seg) },
                    onBack = backAction(),
                    grid = grid,
                    onLiveSearch = { text -> liveQuery(text) }
                )
            }
        }
    }

    // ---- 学生库缓存（边打字边查要快，别每次都读 1.2 万行） ----

    private var cachedAll: List<Student>? = null
    private var cacheStamp = -1L

    private suspend fun allStudents(): List<Student> {
        val stamp = prefs().getLong("last_import", 0L)
        cachedAll?.let { if (stamp == cacheStamp) return it }
        val list = (applicationContext as App).database.studentDao().getAll()
        cachedAll = list
        cacheStamp = stamp
        return list
    }

    /**
     * 边打字边联想（对齐网页版 buildSuggest）：完全一致 > 前缀 > 包含，最多 12 条，
     * 显示在输入框正下方的候选面板里；点名字即查询该生记录。
     */
    private fun liveQuery(text: String) {
        if (text.isBlank()) {
            ResultCardView.hideSuggestions()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val all = allStudents()
            val q = NameNormalizer.normalize(text)
            if (q.isEmpty()) return@launch
            val names = all.map { NameNormalizer.normalize(it.name) }
                .filter { it.isNotBlank() }
                .distinct()
            val scored = names.mapNotNull { n ->
                val score = when {
                    n == q -> 0
                    n.startsWith(q) -> 1
                    n.contains(q) -> 2
                    else -> -1
                }
                if (score < 0) null else Triple(n, score, n)
            }
                .sortedWith(compareBy({ it.second }, { it.third.length }, { it.third }))
                .take(12)
            val kinds = listOf("完全一致", "前缀", "包含")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                ResultCardView.showSuggestions(
                    scored.map { it.first to kinds.getOrElse(it.second) { "" } }
                ) { name -> picked(name) }
            }
        }
    }

    private fun splitSegments(s: String): List<String> =
        s.split(',', '，', '、', '/', ';', '；', ' ', '\n')
            .map { it.trim() }
            .filter { it.length >= 2 }

    /** 用学员库给 OCR 候选打分：能精确命中库里的姓名，说明这段识别更可信 */
    private fun scoreAgainstDb(text: String, all: List<Student>): Int {
        // 原始与「字形纠正后」取高分
        return maxOf(scoreText(text, all), scoreText(applyCharFixes(text), all))
    }

    private fun scoreText(text: String, all: List<Student>): Int {
        val segs = splitSegments(text)
        if (segs.isEmpty()) return 0
        var score = 0
        for (seg in segs) {
            val r = Matcher.match(seg, all)
            score += when {
                !r.found -> 0
                !r.approximate && r.byName.size == 1 -> 3
                !r.approximate -> 2
                else -> 1
            }
            // 没命中也要看"像不像库里某个名字"：这样多个 OCR 变体之间能挑出更靠谱的一条
            if (!r.found) {
                val d = Matcher.rankSimilar(seg, all, 1).firstOrNull()?.second
                score += when {
                    d == null -> 0
                    d <= 1 -> 2
                    d == 2 -> 1
                    else -> 0
                }
            }
        }
        return score
    }

    /**
     * 按「该生首次就读 ~ 最后就读」的区间，把数据库里所有真实学期列出来，
     * 没有记录的学期留空（灰色行）——一眼就能看出哪一期没来。
     * 和网页版 学生查询.html 的 displayTerms 逻辑一致，只是顺序反过来（最新在最上面）。
     */
    private fun buildGrid(allTerms: List<String>, recs: List<Student>): List<ResultCardView.GridRow> {
        if (recs.isEmpty()) return emptyList()
        // 同一学期可能有多行（同一学生报了不同班次）：期数按「学期」去重编号
        val terms = recs.map { it.term }.distinct()
        val attended = recs.groupBy { it.term }.mapValues { it.value.first() }
        val seqOf = terms.sortedBy { TermUtils.sortKey(it) }
            .mapIndexed { i, t -> t to (i + 1) }
            .toMap()
        val low = terms.minOf { TermUtils.sortKey(it) }
        val high = terms.maxOf { TermUtils.sortKey(it) }
        return allTerms
            .filter { TermUtils.sortKey(it) in low..high }
            .sortedByDescending { TermUtils.sortKey(it) }
            .map { t -> ResultCardView.GridRow(TermUtils.clean(t), seqOf[t], attended[t]) }
    }

    // ---- OCR 纠正记忆（同一家长会反复出现，认错一次就记住） ----

    private fun fixMap(): Map<String, String> =
        getSharedPreferences("app_state", MODE_PRIVATE)
            .getString("ocr_fix", "").orEmpty()
            .split('\n')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun rememberFix(rawOcr: String, name: String) {
        val m = fixMap().toMutableMap()
        m[rawOcr] = name
        getSharedPreferences("app_state", MODE_PRIVATE).edit()
            .putString("ocr_fix", m.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
        learnCharFixes(rawOcr, name)
        Diag.log(this, "OCR", "记住纠正：$rawOcr -> $name")
    }

    // ---- 字形自学习：改正过的错字，以后自动换回来 ----

    private fun prefs() = getSharedPreferences("app_state", MODE_PRIVATE)

    private fun charFixMap(): Map<Char, Char> =
        prefs().getString("ocr_char_fix", "").orEmpty()
            .split('\n')
            .filter { it.length >= 3 && it[1] == '=' }
            .associate { it[0] to it[2] }

    /**
     * 从「认错的名字 → 正确的名字」里学字形对应关系。
     * 例：陈恰形 → 陈怡彤 就记住 恰→怡、形→彤；下次再认成 卢恰彤/卢映形 也能自动改对。
     */
    private fun learnCharFixes(wrong: String, right: String) {
        if (wrong.length != right.length || wrong == right) return
        val m = charFixMap().toMutableMap()
        var n = 0
        for (i in wrong.indices) {
            if (wrong[i] != right[i]) {
                m[wrong[i]] = right[i]
                n++
            }
        }
        if (n == 0) return
        prefs().edit()
            .putString("ocr_char_fix", m.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
        Diag.log(this, "OCR", "学会字形纠正：${m.entries.joinToString(" ") { "${it.key}→${it.value}" }}")
    }

    /** 用学到的错字表把识别结果换一遍 */
    private fun applyCharFixes(s: String): String {
        val m = charFixMap()
        if (m.isEmpty()) return s
        val out = buildString { for (c in s) append(m[c] ?: c) }
        return out
    }

    /**
     * 一个家长可能有多个孩子：用手机号归组，把「同一家长的其他孩子」一并列出来，
     * 这样客服在同一个聊天里就能回答任何一个孩子的情况。只在唯一命中时给，避免误导。
     */
    private fun computeSiblings(
        result: Matcher.MatchResult,
        all: List<Student>
    ): List<Pair<String, String>> {
        if (result.byName.size != 1) return emptyList()
        val (name, recs) = result.byName.entries.first()
        val phone = recs.asSequence()
            .mapNotNull { it.phone?.trim() }
            .firstOrNull { it.isNotEmpty() } ?: return emptyList()
        val others = all.filter { it.name != name && it.phone?.trim() == phone }
        if (others.isEmpty()) return emptyList()
        return others.groupBy { it.name }.map { (n, rs) ->
            val latest = rs.maxByOrNull { TermUtils.sortKey(it.term) }
            val info = if (latest == null) "" else listOf(
                TermUtils.clean(latest.term),
                latest.classSession ?: "",
                latest.teacher ?: ""
            ).filter { it.isNotBlank() }.joinToString(" ")
            n to info
        }.sortedBy { it.first }
    }

    private fun showMenu() {
        ResultCardView.showMenu(this) {
            when (it) {
                MenuAction.MANUAL -> showManualSearch()
                MenuAction.BALL_STYLE -> showBallStylePicker()
                MenuAction.REFRESH -> toast("数据已在导入时更新，无需刷新")
                MenuAction.HIDE -> {
                    // 明确记住「是用户自己要隐藏的」，否则下次打开 App 会自动又冒出来
                    getSharedPreferences("app_state", MODE_PRIVATE)
                        .edit().putBoolean("ball_hidden_by_user", true).apply()
                    Diag.log(this, "Ball", "用户隐藏悬浮球")
                    stopSelf()
                }
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
                    params.x = if (params.x < screenW / 2) 0 else screenW - params.width
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

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
        /** 悬浮球直径（dp）——半透明，尽量不挡内容 */
        private const val BALL_DP = 56
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
            if (AccessibilitySupport.isEnabled(this@FloatingBallService) &&
                LookupAccessibilityService.instance == null
            ) {
                if (AccessibilitySupport.canSelfRepair(this@FloatingBallService)) {
                    Diag.log(this@FloatingBallService, "Ball", "启动时发现无障碍未连接 → 自动重绑")
                    AccessibilitySupport.rebind(this@FloatingBallService)
                } else {
                    Diag.log(this@FloatingBallService, "Ball", "启动时发现无障碍未连接，且无自愈权限")
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
        params = WindowManager.LayoutParams(
            dp(BALL_DP), dp(BALL_DP),
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
            toast("正在识别微信标题…")
            CoroutineScope(Dispatchers.Main).launch {
                val full = acc.captureScreen()
                if (full == null) {
                    Diag.log(this@FloatingBallService, "Ball", "无障碍截屏失败 → 转手动搜索")
                    notifyCard(
                        "无法读取微信标题",
                        "自动识别没成功，请手动输入姓名搜索。",
                        listOf("手动搜索" to { showManualSearch() })
                    )
                    return@launch
                }
                val skip = acc.statusBarHeightPx()
                // 多种放大倍率各识别一遍，再用「学员库」当字典挑最可信的一条
                val candidates = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    OcrFallback.titleCandidates(
                        this@FloatingBallService, full, skip, (skip * 1.2f).toInt()
                    )
                }
                runCatching { full.recycle() }
                if (candidates.isEmpty()) {
                    Diag.log(this@FloatingBallService, "Ball", "OCR 没有候选行")
                    notifyCard(
                        "未能识别",
                        "没有识别出姓名。请手动输入姓名搜索。",
                        listOf("手动搜索" to { showManualSearch() })
                    )
                    return@launch
                }
                val best = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val all = (applicationContext as App).database.studentDao().getAll()
                    candidates.maxByOrNull { scoreAgainstDb(it, all) } ?: candidates.first()
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
        ResultCardView.showManualSearch(this) { q -> query(q) }
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
            val all = (applicationContext as App).database.studentDao().getAll()
            val fixes = fixMap()
            val hits = LinkedHashSet<String>()
            val unresolved = ArrayList<String>()
            for (seg in segs) {
                val resolved = fixes[seg] ?: seg
                val r = Matcher.match(resolved, all)
                if (r.found && !r.approximate && r.byName.size == 1) {
                    hits.add(r.byName.keys.first())
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
                val mr = Matcher.MatchResult(
                    found = true, approximate = false, query = ocr,
                    byName = rows.groupBy { it.name }
                )
                currentQ?.let { history.addLast(it) }
                currentQ = Q(ocr, true, ocr)
                ResultCardView.show(
                    this@FloatingBallService, mr,
                    onManualSearch = { q -> picked(q) },
                    prefill = ocr,
                    unresolved = unresolved,
                    onCorrect = { seg -> startCorrection(seg) },
                    onBack = backAction()
                )
            }
        }
    }

    /** 纠正某个认不准的片段：预填到手动搜索框，改好后搜索即被记住 */
    private fun startCorrection(segment: String) {
        Diag.log(this, "Ball", "纠正片段：$segment")
        ResultCardView.showManualSearch(this, initialText = segment) { typed ->
            rememberFix(segment, typed)
            toast("已记住：$segment → $typed")
            picked(typed)
        }
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
            val all = (applicationContext as App).database.studentDao().getAll()
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
                    grid = grid
                )
            }
        }
    }

    private fun splitSegments(s: String): List<String> =
        s.split(',', '，', '、', '/', ';', '；', ' ', '\n')
            .map { it.trim() }
            .filter { it.length >= 2 }

    /** 用学员库给 OCR 候选打分：能精确命中库里的姓名，说明这段识别更可信 */
    private fun scoreAgainstDb(text: String, all: List<Student>): Int {
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
        val attended = recs.associateBy { it.term }
        val seqOf = recs.sortedBy { TermUtils.sortKey(it.term) }
            .mapIndexed { i, s -> s.term to (i + 1) }
            .toMap()
        val low = recs.minOf { TermUtils.sortKey(it.term) }
        val high = recs.maxOf { TermUtils.sortKey(it.term) }
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
        Diag.log(this, "OCR", "记住纠正：$rawOcr -> $name")
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
                MenuAction.MANUAL -> ResultCardView.showManualSearch(this) { q -> query(q) }
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
                    params.x = if (params.x < screenW / 2) 0 else screenW - dp(BALL_DP)
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

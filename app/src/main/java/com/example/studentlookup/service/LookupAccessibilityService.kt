package com.example.studentlookup.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.studentlookup.util.AccessibilitySupport
import com.example.studentlookup.util.Diag
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 无障碍服务：感知当前是否处于微信，并读取会话标题（即对方备注名）。
 *
 * ⚠️ 校准点（重要）：
 * 微信是混淆 + 自绘 UI，会话标题控件的 resource-id 会随版本变化。
 * 真机上打开一个单聊，调用 dumpNodes() 把日志打印出来，找到标题文本所在的
 * viewIdResourceName，把下面 TITLE_CANDIDATE_IDS 替换成真实 id 即可。
 * 例如：["com.tencent.mm:id/b4c"]。
 * 在拿到真实 id 之前，readConversationTitle() 会走「顶部区域兜底」策略，
 * 通常也能工作，只是略不稳定。
 *
 * 免重编译的校准方式：真机点悬浮球 → 结果卡片「诊断」按钮 → 日志看 dump，
 * 然后在卡片搜索框里输入 `id:com.tencent.mm:id/xxxx` 回车，即写入本机偏好设置。
 */
class LookupAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SLK-A11ySvc"

        @Volatile
        var instance: LookupAccessibilityService? = null

        /** 服务真正连上系统的时间戳（0 表示未连接） */
        @Volatile
        var connectedAt: Long = 0L

        const val WECHAT_PACKAGE = "com.tencent.mm"

        /**
         * 会话标题控件候选 id（全 id，含包名）。
         * 留空则只走兜底；可在真机用 dumpNodes() 校准后把真实值填到这里（编译期默认值），
         * 或运行时在 App 内输入 `id:...` 写入偏好设置（见 AccessibilitySupport）。
         */
        var TITLE_CANDIDATE_IDS: List<String> = emptyList()

        /** 标题不太可能是这些（导航/按钮/时间等） */
        private val NOT_NAME = setOf(
            "微信", "通讯录", "发现", "我", "搜索", "取消", "返回", "更多",
            "聊天信息", "详细资料", "发送", "关闭", "确定", "设置"
        )

        private val TIME_LIKE = Regex("^[\\d\\s:：.\\-+/年月日]+$")
    }

    @Volatile
    var currentPackage: String? = null

    /** 只用于日志：避免每条事件都写运行记录 */
    @Volatile
    private var lastLoggedPkg: String? = null

    override fun onServiceConnected() {
        instance = this
        connectedAt = System.currentTimeMillis()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf(WECHAT_PACKAGE)
        }
        Log.d(TAG, "onServiceConnected")
        Diag.log(
            this, "A11ySvc",
            "服务已连接：capabilities=${serviceInfo?.capabilities} flags=${serviceInfo?.flags} " +
                "事件类型=${serviceInfo?.eventTypes}"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString()
        if (pkg.isNullOrEmpty()) return
        currentPackage = pkg
        if (pkg != lastLoggedPkg) {
            lastLoggedPkg = pkg
            Diag.log(this, "A11ySvc", "收到窗口事件：$pkg（type=${e.eventType}）")
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        instance = null
        connectedAt = 0L
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        instance = null
        connectedAt = 0L
        super.onDestroy()
    }

    /**
     * 当前是否在微信里。
     * 以「当前活动窗口的包名」为准（这样离开微信后能立刻返回 false），
     * 读不到窗口时再退回事件记录的包名。
     */
    fun isInWechat(): Boolean {
        val pkg = activePackage()
        if (!pkg.isNullOrEmpty()) return pkg == WECHAT_PACKAGE
        return currentPackage == WECHAT_PACKAGE
    }

    /**
     * 当前活动窗口所属包名。
     * 优先 rootInActiveWindow；某些 ROM/时机下它会是 null，此时退回「交互窗口列表里 isActive 的那个」。
     */
    fun activePackage(): String? {
        val direct = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!direct.isNullOrEmpty()) return direct
        return runCatching {
            windows.orEmpty()
                .firstOrNull { it.isActive }
                ?.root?.packageName?.toString()
        }.getOrNull()
    }

    /** 取当前活动窗口的根节点（带兜底）。 */
    private fun activeRoot(): AccessibilityNodeInfo? {
        runCatching { rootInActiveWindow }.getOrNull()?.let { return it }
        return runCatching {
            windows.orEmpty().firstOrNull { it.isActive }?.root
        }.getOrNull()
    }

    /**
     * 微信等应用可能完全不向无障碍暴露控件树（本机实测：微信窗口 hasChildren=false），
     * 此时唯一可行的自动取标题方式就是「截屏 + OCR」。
     * Android 11(API 30) 起无障碍服务可以直接截屏，不需要录屏授权、也不需要额外前台服务。
     */
    fun canTakeScreenshotNow(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ((serviceInfo?.capabilities ?: 0) and
                AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0

    /** 截取当前屏幕；失败返回 null（任何情况都不抛异常）。 */
    suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (t: Throwable) {
                            Log.w(TAG, "wrapHardwareBuffer: ${t.message}")
                            null
                        }
                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (t: Throwable) {
                            Log.w(TAG, "close buffer: ${t.message}")
                        }
                        cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        Diag.log(this@LookupAccessibilityService, "A11ySvc", "无障碍截屏失败：code=$errorCode")
                        cont.resume(null)
                    }
                }
            )
        } catch (t: Throwable) {
            Diag.log(this, "A11ySvc", "无障碍截屏异常：${t.javaClass.simpleName} ${t.message}")
            cont.resume(null)
        }
    }

    /** 状态栏高度（像素）：截屏后要跳过状态栏里的时间/电量，避免被 OCR 当成姓名。 */
    fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) resources.getDimensionPixelSize(id) else 0
        return if (h > 0) h else (resources.displayMetrics.density * 40).toInt()
    }

    /**
     * 读取当前微信会话对象的备注名。
     * 优先按候选 id 精确取；取不到则取屏幕顶部区域内最靠上的非空文本节点。
     */
    fun readConversationTitle(): String? {
        val root = activeRoot() ?: run {
            Log.d(TAG, "readTitle: no active window")
            Diag.log(this, "A11ySvc", "读标题失败：拿不到活动窗口")
            return null
        }
        Log.d(TAG, "readTitle: root=${root.packageName} cls=${root.className}")

        // 1) 按候选 resource-id 精确读取（编译期默认 + 运行时校准值）
        val ids = (TITLE_CANDIDATE_IDS + AccessibilitySupport.titleCandidateIds(this)).distinct()
        for (id in ids) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
            for (n in nodes.orEmpty()) {
                val t = n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                if (t != null) {
                    Log.d(TAG, "readTitle: hit id=$id -> $t")
                    return t
                }
            }
        }

        // 2) 兜底：屏幕顶部 1/4 区域内的非空文本节点，取最靠上且像人名的那个
        val screen = Rect()
        root.getBoundsInScreen(screen)
        if (screen.height() <= 0) {
            Log.d(TAG, "readTitle: bad root bounds $screen")
            return null
        }
        val topLine = screen.top + screen.height() / 4
        val candidates = mutableListOf<Pair<Int, String>>()
        traverse(root) { node ->
            val text = node.text?.toString()?.trim()
            if (text.isNullOrEmpty() || text.length > 24) return@traverse
            if (!node.isVisibleToUser) return@traverse
            val b = Rect()
            node.getBoundsInScreen(b)
            if (b.top in screen.top until topLine) {
                candidates.add(b.top to text)
            }
        }
        if (candidates.isEmpty()) {
            Log.d(TAG, "readTitle: no candidate in top area")
            return null
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "readTitle candidates=" +
                candidates.sortedBy { it.first }.joinToString { "${it.second}@${it.first}" })
        }
        val named = candidates.filter { looksLikeName(it.second) }
        val pool = if (named.isNotEmpty()) named else candidates
        return pool.minByOrNull { it.first }?.second
    }

    private fun looksLikeName(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty() || s.length > 24) return false
        if (s in NOT_NAME) return false
        if (TIME_LIKE.matches(s)) return false
        return true
    }

    private fun traverse(node: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        action(node)
        for (i in 0 until node.childCount) traverse(node.getChild(i), action)
    }

    /** 调试用：打印当前窗口所有节点的 id 与文本，用于在真机校准标题控件 */
    fun dumpNodes(): String {
        val root = rootInActiveWindow ?: return "no root"
        val sb = StringBuilder()
        traverse(root) { n ->
            val text = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
            sb.append("${n.viewIdResourceName ?: "-"}\t|\t${n.className ?: "-"}\t|\t$text\n")
        }
        Log.d("SLK-Dump", "\n$sb")
        return sb.toString()
    }
}

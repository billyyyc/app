package com.example.studentlookup.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
 */
class LookupAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LookupAccessibilityService? = null

        const val WECHAT_PACKAGE = "com.tencent.mm"

        /**
         * 会话标题控件候选 id（全 id，含包名）。
         * TODO: 在真机用 dumpNodes() 校准后填入真实值；留空则只走兜底。
         */
        var TITLE_CANDIDATE_IDS: List<String> = emptyList()
    }

    @Volatile
    var currentPackage: String? = null

    override fun onServiceConnected() {
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf(WECHAT_PACKAGE)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun isInWechat(): Boolean = currentPackage == WECHAT_PACKAGE

    /**
     * 读取当前微信会话对象的备注名。
     * 优先按候选 id 精确取；取不到则取屏幕顶部区域内最靠上的非空文本节点。
     */
    fun readConversationTitle(): String? {
        val root = rootInActiveWindow ?: return null

        // 1) 按候选 resource-id 精确读取
        for (id in TITLE_CANDIDATE_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                val t = n.text?.toString()?.takeIf { it.isNotBlank() }
                if (t != null) return t
            }
        }

        // 2) 兜底：屏幕顶部 1/4 区域内的非空文本节点，取最靠上的一个
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val topLine = screen.top + screen.height() / 4
        val candidates = mutableListOf<Pair<Int, String>>()
        traverse(root) { node ->
            val text = node.text?.toString()
            val b = Rect()
            node.getBoundsInScreen(b)
            if (!text.isNullOrBlank() && b.top < topLine) {
                candidates.add(b.top to text)
            }
        }
        candidates.sortBy { it.first }
        return candidates.firstOrNull()?.second
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
            sb.append("${n.viewIdResourceName ?: "-"}\t|\t${n.text ?: ""}\n")
        }
        return sb.toString()
    }
}

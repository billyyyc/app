package com.example.studentlookup.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.example.studentlookup.service.LookupAccessibilityService
import kotlinx.coroutines.delay

/**
 * 无障碍服务的「状态查询」与「假死修复」。
 *
 * 背景：系统设置里显示已开启（AccessibilityManager 能查到），并不代表服务真的连上了。
 * `adb install -r` 重装、或 ColorOS 把服务杀掉之后，服务常处于「已启用但未绑定」的假死态，
 * 此时 `LookupAccessibilityService.instance` 仍为 null，点悬浮球就会「没反应」。
 *
 * 这里提供：
 * - isEnabled：系统层面是否已开启（标准 API 为主，设置字符串兜底）
 * - isConnected：服务是否真的连上（能读窗口内容）
 * - canSelfRepair / rebind：在拿得到 WRITE_SECURE_SETTINGS 时，把服务摘掉再装回，
 *   触发系统重新绑定，从而自愈假死态。
 */
object AccessibilitySupport {

    private const val TAG = "SLK-A11y"
    private const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    private const val SECURE_ENABLED_SERVICES = "enabled_accessibility_services"
    private const val SECURE_ACCESSIBILITY_ENABLED = "accessibility_enabled"

    private const val PREF = "app_state"
    private const val KEY_TITLE_IDS = "title_candidate_ids"

    /** 系统层面是否已开启本应用的无障碍服务。 */
    fun isEnabled(ctx: Context): Boolean {
        val serviceName = LookupAccessibilityService::class.java.name
        val enabledViaApi = runCatching {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                it.resolveInfo.serviceInfo.packageName == ctx.packageName &&
                    it.resolveInfo.serviceInfo.name == serviceName
            }
        }.getOrDefault(false)
        if (enabledViaApi) return true

        // 兜底：解析系统安全设置字符串，宽松匹配（兼容 ColorOS 等把值存成不同格式的 ROM）
        val simple = LookupAccessibilityService::class.java.simpleName
        val raw = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(":").any {
            it.equals("${ctx.packageName}/$simple", true) ||
                it.endsWith(".$simple", true) ||
                it.equals(serviceName, true)
        }
    }

    /** 无障碍服务是否真的连上了（能读当前窗口内容）。 */
    fun isConnected(): Boolean = LookupAccessibilityService.instance != null

    /** 是否具备「自愈」能力：需要一次性 adb 授权 WRITE_SECURE_SETTINGS。 */
    fun canSelfRepair(ctx: Context): Boolean =
        ctx.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED

    /** 本应用的无障碍服务组件名（full class name 形式）。 */
    fun component(ctx: Context): String =
        ctx.packageName + "/" + LookupAccessibilityService::class.java.name

    /**
     * 把本应用的无障碍服务从系统设置里「摘掉 → 再装回」，让系统重新绑定它。
     * 这是解除假死态的可靠手段（等价于用户在设置里手动「关→开」一次）。
     * 需要 WRITE_SECURE_SETTINGS；没有权限时返回 false，调用方应引导用户手动操作。
     */
    suspend fun rebind(ctx: Context): Boolean {
        if (!canSelfRepair(ctx)) {
            Log.w(TAG, "rebind skipped: no WRITE_SECURE_SETTINGS")
            return false
        }
        return try {
            val cr = ctx.contentResolver
            val comp = component(ctx)
            val current = Settings.Secure.getString(cr, SECURE_ENABLED_SERVICES).orEmpty()
            // 其余第三方无障碍服务原样保留，只摘掉本应用自己（含短类名写法）
            val others = current.split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.startsWith("${ctx.packageName}/") }

            Settings.Secure.putInt(cr, SECURE_ACCESSIBILITY_ENABLED, 1)
            Settings.Secure.putString(
                cr, SECURE_ENABLED_SERVICES, others.joinToString(":")
            )
            // 必须留出间隔，否则 ContentObserver 可能把两次写入合并成「无变化」而不触发重绑
            delay(500)
            Settings.Secure.putString(
                cr, SECURE_ENABLED_SERVICES, (others + comp).joinToString(":")
            )
            Settings.Secure.putInt(cr, SECURE_ACCESSIBILITY_ENABLED, 1)
            Log.d(TAG, "rebind requested; before=[$current]")
            Diag.log(ctx, "A11y", "已请求系统重新绑定无障碍服务")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "rebind failed: ${t.message}")
            Diag.log(ctx, "A11y", "重新绑定失败：${t.message}")
            false
        }
    }

    // ---- 标题控件 id 的运行时校准（免重新编译） ----

    /** 用户在 App 里设置的标题控件 id（用诊断功能拿到后填进来）。 */
    fun titleCandidateIds(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_TITLE_IDS, "")
            .orEmpty()
            .split(',', '、', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setTitleCandidateIds(ctx: Context, ids: List<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TITLE_IDS, ids.joinToString(","))
            .apply()
        Log.d(TAG, "title ids set to $ids")
    }
}

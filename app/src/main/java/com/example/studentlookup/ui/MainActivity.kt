package com.example.studentlookup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.studentlookup.App
import com.example.studentlookup.R
import com.example.studentlookup.data.import.DataImporter
import com.example.studentlookup.databinding.ActivityMainBinding
import com.example.studentlookup.service.FloatingBallService
import com.example.studentlookup.util.AccessibilitySupport
import com.example.studentlookup.util.Diag
import com.example.studentlookup.util.RomUtils
import com.example.studentlookup.util.TermUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("app_state", Context.MODE_PRIVATE) }

    /** 本次进程内是否已尝试过「自动重连无障碍」，避免反复折腾系统设置 */
    private var autoRepairTried = false

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { doImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "text/csv"
            ))
        }

        binding.btnStart.setOnClickListener { startFloatingBall() }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOpenOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        binding.btnRepair.setOnClickListener { repairAccessibility() }
        binding.btnBattery.setOnClickListener { requestBatteryWhitelist() }

        Diag.log(this, "App", "打开首页")
        refreshDataInfo()
        handleDebugQuery(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugQuery(intent)
    }

    /**
     * 调试入口（仅 adb 可用）：
     *   adb shell am start -n com.example.studentlookup/.ui.MainActivity --es debug_query "陈怡彤妈"
     * 转发给悬浮球服务，用真实数据跑一遍查询，方便远程验证匹配与卡片样式。
     */
    private fun handleDebugQuery(intent: Intent?) {
        val q = intent?.getStringExtra("debug_query")?.takeIf { it.isNotBlank() }
        val f = intent?.getStringExtra("debug_ocr_file")?.takeIf { it.isNotBlank() }
        val b = intent?.getStringExtra("debug_ball")
        if (q == null && f == null && b == null) return
        if (q != null) Diag.log(this, "App", "调试验证查询：$q")
        if (f != null) Diag.log(this, "App", "调试验证OCR：$f")
        runCatching {
            startService(
                Intent(this, FloatingBallService::class.java).apply {
                    if (q != null) putExtra("debug_query", q)
                    if (f != null) putExtra("debug_ocr_file", f)
                    if (b != null) putExtra("debug_ball", b)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        // 刚回到前台时无障碍服务可能还在重连，稍后再确认一次，避免误报「未连接」
        binding.root.postDelayed({ updatePermissionStatus() }, 900)
        maybeAutoRepair()
        maybeAutoStartBall()
    }

    /**
     * 自动启动悬浮球。
     *
     * 悬浮球背后是一个前台服务，而前台服务正是「进程不被 ColorOS 清掉」的关键——
     * 进程活着，同进程的无障碍服务才不会掉线。之前把启动交给用户点按钮，
     * 结果用户没点（或点了没反应），于是进程被反复清理 → 无障碍反复掉线 → 点球没反应。
     * 现在：只要权限齐、且用户没主动隐藏，打开 App 就自动起。
     */
    private fun maybeAutoStartBall() {
        if (FloatingBallService.isRunning) return
        if (!Settings.canDrawOverlays(this)) return
        if (!AccessibilitySupport.isEnabled(this)) return
        if (prefs.getBoolean("ball_hidden_by_user", false)) return
        Diag.log(this, "App", "自动启动悬浮球")
        startFloatingBall(quiet = true)
    }

    /**
     * 自动重连：ColorOS 清掉 App 进程后，无障碍服务常处于「开关还开着、但没绑上」的假死态。
     * 以前需要用户手动「关→开」，现在只要 App 能拿到 WRITE_SECURE_SETTINGS，就自动重绑一次。
     */
    private fun maybeAutoRepair() {
        if (autoRepairTried) return
        if (AccessibilitySupport.isConnected()) return
        autoRepairTried = true
        if (!AccessibilitySupport.canSelfRepair(this)) {
            Diag.log(this, "App", "无障碍未连接，且当前无自愈权限（需要一次性 adb 授权）")
            return
        }
        // 自愈会让系统再弹一次「检测到无障碍权限」的提醒，别太频繁：5 分钟内只自动修一次
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_auto_repair", 0L) < 5 * 60 * 1000L) {
            Diag.log(this, "App", "无障碍未连接，但刚自动修过（5 分钟内），跳过")
            return
        }
        prefs.edit().putLong("last_auto_repair", now).apply()
        val wasEnabled = AccessibilitySupport.isEnabled(this)
        Diag.log(
            this, "App",
            if (wasEnabled) "无障碍已开启但未连接 → 自动重新绑定" else "无障碍被关闭 → 自动重新开启"
        )
        CoroutineScope(Dispatchers.Main).launch {
            AccessibilitySupport.rebind(this@MainActivity)
            delay(1800)
            val ok = AccessibilitySupport.isConnected()
            updatePermissionStatus()
            Diag.log(this@MainActivity, "App", "自动修复结果：已连接=$ok")
            if (ok) {
                Toast.makeText(this@MainActivity, "无障碍服务已自动开启 ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "自动开启没成功，请点「修复无障碍连接」或去系统设置里手动打开一次",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 把 App 加入电池优化白名单——OPPO/ColorOS 上这是防止进程被清掉的关键一步。 */
    private fun requestBatteryWhitelist() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已经在省电白名单里了 ✅", Toast.LENGTH_SHORT).show()
            return
        }
        val launched = runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.isSuccess
        if (!launched) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun updatePermissionStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val accEnabled = AccessibilitySupport.isEnabled(this)
        val accConnected = AccessibilitySupport.isConnected()
        binding.tvPerm.text = buildString {
            append("悬浮窗权限：").append(if (overlay) "已开启 ✅" else "未开启 ❌").append("\n")
            append("无障碍服务：").append(
                when {
                    !accEnabled -> "未开启 ❌"
                    accConnected -> "已开启 ✅（运行中）"
                    else -> "已开启 ⚠️ 但未连接（点下方「修复无障碍连接」）"
                }
            )
            append("\n")
            append("悬浮球：").append(
                if (FloatingBallService.isRunning) "运行中 ✅（通知请保留）" else "未启动 ❌"
            )
        }
        binding.btnStart.isEnabled = overlay && accEnabled
        binding.btnOpenOverlay.visibility = if (overlay) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnOpenAccessibility.visibility =
            if (accEnabled) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnRepair.visibility =
            if (accEnabled && !accConnected) android.view.View.VISIBLE else android.view.View.GONE
        Diag.log(
            this, "App",
            "权限状态：悬浮窗=${if (overlay) "开" else "关"} 无障碍已开启=$accEnabled 已连接=$accConnected"
        )
    }

    /** 「修复无障碍连接」：解除「设置里已开启、但服务没真正运行」的假死态。 */
    private fun repairAccessibility() {
        when {
            !AccessibilitySupport.isEnabled(this) -> {
                Toast.makeText(this, "请先开启「学员速查」的无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            AccessibilitySupport.isConnected() -> {
                Toast.makeText(this, "无障碍服务运行正常，无需修复", Toast.LENGTH_SHORT).show()
            }
            !AccessibilitySupport.canSelfRepair(this) -> {
                Toast.makeText(
                    this,
                    "请打开无障碍设置，把「学员速查」先关闭、再打开一次，即可重新连接。",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            else -> {
                Toast.makeText(this, "正在重新连接无障碍服务…", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    AccessibilitySupport.rebind(this@MainActivity)
                    delay(1500)
                    updatePermissionStatus()
                    Toast.makeText(
                        this@MainActivity,
                        if (AccessibilitySupport.isConnected())
                            "已重新连接 ✅ 现在可以去微信点悬浮球了"
                        else "仍未连接；请在系统设置 → 无障碍里把本服务「关→开」一次",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startFloatingBall(quiet: Boolean = false) {
        if (FloatingBallService.isRunning) {
            if (!quiet) Toast.makeText(this, "悬浮球已在运行", Toast.LENGTH_SHORT).show()
            return
        }
        // 用户主动点按钮 = 明确想要悬浮球，清掉「用户隐藏过」的标记
        prefs.edit().putBoolean("ball_hidden_by_user", false).apply()
        try {
            startService(Intent(this, FloatingBallService::class.java))
            Diag.log(this, "App", if (quiet) "自动启动悬浮球" else "手动启动悬浮球")
            if (!quiet) {
                Toast.makeText(
                    this,
                    "悬浮球已启动。请让它的通知常驻（别在后台顺手清掉本应用），再到微信里点它",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (t: Throwable) {
            Diag.log(this, "App", "启动悬浮球失败：${t.javaClass.simpleName} ${t.message}")
            Toast.makeText(
                this,
                "悬浮球启动失败：${t.javaClass.simpleName}（已记录，请把运行记录发给技术支持）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun doImport(uri: Uri) {
        binding.btnImport.isEnabled = false
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val result = DataImporter.import(this@MainActivity, uri)
            prefs.edit().putLong("last_import", System.currentTimeMillis()).apply()
            withContext(Dispatchers.Main) {
                binding.btnImport.isEnabled = true
                val msg = buildString {
                    append("成功导入 ${result.inserted} 条，覆盖 ${result.terms.size} 个学期，${result.studentCount} 名学生")
                    if (result.errors.isNotEmpty()) {
                        append("\n提示 ${result.errors.size} 条：\n")
                        append(result.errors.take(10).joinToString("\n"))
                        if (result.errors.size > 10) append("\n…（更多见日志）")
                    }
                }
                binding.tvImportResult.text = msg
                refreshDataInfo()
                if (result.inserted == 0) {
                    // 失败时把具体原因带进 Toast，方便真机上直接定位
                    val why = result.errors.firstOrNull() ?: "未读到任何数据（文件可能为空或损坏）"
                    Toast.makeText(this@MainActivity, "导入失败：$why", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshDataInfo() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = (applicationContext as App).database.studentDao()
            val terms = dao.getTerms()
            val last = prefs.getLong("last_import", 0L)
            val time = if (last == 0L) "尚未导入" else
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(last))
            val latest = terms.maxByOrNull { TermUtils.sortKey(it) }
            withContext(Dispatchers.Main) {
                binding.tvData.text = buildString {
                    append("共 ${terms.size} 个学期")
                    if (latest != null) append(" · 最新一期：${TermUtils.clean(latest)}")
                    append("\n导入时间：$time")
                }
            }
        }
    }
}

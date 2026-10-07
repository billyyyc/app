package com.example.studentlookup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.example.studentlookup.util.RomUtils
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

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { doImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvRom.text = "机型适配：${RomUtils.getRom().name}\n${RomUtils.guidance()}" +
            "\n\n开启无障碍服务（点下方按钮后按此操作）：\n${RomUtils.accessibilityGuidance()}"

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
        binding.btnOcr.setOnClickListener {
            Toast.makeText(
                this,
                "接下来会弹出「开始录制/屏幕采集」授权，请点允许：仅用于识别微信标题，不会保存画面。",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, CapturePermissionActivity::class.java))
        }

        refreshDataInfo()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        // 刚回到前台时无障碍服务可能还在重连，稍后再确认一次，避免误报「未连接」
        binding.root.postDelayed({ updatePermissionStatus() }, 900)
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
        }
        binding.btnStart.isEnabled = overlay && accEnabled
        binding.btnOpenOverlay.visibility = if (overlay) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnOpenAccessibility.visibility =
            if (accEnabled) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnRepair.visibility =
            if (accEnabled && !accConnected) android.view.View.VISIBLE else android.view.View.GONE
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

    private fun startFloatingBall() {
        if (FloatingBallService.isRunning) {
            Toast.makeText(this, "悬浮球已在运行", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, FloatingBallService::class.java))
        Toast.makeText(this, "悬浮球已启动，去微信对话里点它", Toast.LENGTH_SHORT).show()
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
            val count = dao.count()
            val terms = dao.getTerms().size
            val students = dao.countStudents()
            val last = prefs.getLong("last_import", 0L)
            val time = if (last == 0L) "尚未导入" else
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(last))
            withContext(Dispatchers.Main) {
                binding.tvData.text = "当前数据：$count 条 · $terms 个学期 · $students 名学生\n导入时间：$time"
            }
        }
    }
}

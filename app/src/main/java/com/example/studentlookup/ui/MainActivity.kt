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
import com.example.studentlookup.service.LookupAccessibilityService
import com.example.studentlookup.util.RomUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

        binding.tvRom.text = "机型适配：${RomUtils.getRom().name}\n${RomUtils.guidance()}"

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

        refreshDataInfo()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val acc = isAccessibilityEnabled()
        binding.tvPerm.text = buildString {
            append("悬浮窗权限：").append(if (overlay) "已开启 ✅" else "未开启 ❌").append("\n")
            append("无障碍服务：").append(if (acc) "已开启 ✅" else "未开启 ❌")
        }
        binding.btnStart.isEnabled = overlay && acc
        binding.btnOpenOverlay.visibility = if (overlay) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnOpenAccessibility.visibility = if (acc) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isAccessibilityEnabled(): Boolean {
        val id = packageName + "/" + LookupAccessibilityService::class.java.canonicalName
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(id, true) }
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
                    Toast.makeText(this@MainActivity, "导入失败，请检查表头是否含「姓名」列", Toast.LENGTH_LONG).show()
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

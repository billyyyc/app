package com.example.studentlookup.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.studentlookup.R
import com.example.studentlookup.service.ScreenCaptureService
import com.example.studentlookup.util.Diag

/**
 * 「截图识别」授权页（可选功能）。
 *
 * 注意：这里**故意不自动关闭页面**。旧版本是一进来就弹系统授权框、授权完立刻 finish，
 * 用户看到的就是「弹一下、App 就退出了」，很容易误以为崩溃。现在改成明确的界面：
 * 说明用途 → 点「开始授权」→ 显示结果 → 用户自己点「关闭」。
 */
class CapturePermissionActivity : AppCompatActivity() {

    private val REQ_CAPTURE = 100
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_permission)

        tvStatus = findViewById(R.id.tv_capture_status)
        btnStart = findViewById(R.id.btn_capture_start)

        btnStart.setOnClickListener { requestCapture() }
        findViewById<Button>(R.id.btn_capture_close).setOnClickListener { finish() }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (t: Throwable) {
            Diag.log(this, "Capture", "无法弹出屏幕采集授权：${t.message}")
            tvStatus.text = "这台设备无法弹出屏幕采集授权（可能被系统策略限制）。\n" +
                "不影响使用：请直接用手动搜索，或先在结果卡片点「诊断」校准标题控件 id。"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val ok = ScreenCaptureService.startWithPermission(this, resultCode, data)
            tvStatus.text = if (ok) {
                Diag.log(this, "Capture", "截图识别已授权并启动")
                "✅ 已开启截图识别。\n\n现在可以返回微信：点悬浮球若读不到标题，会自动截图识别。" +
                    "\n（系统状态栏出现共享提示属正常，可在系统里随时停止。）"
            } else {
                Diag.log(this, "Capture", "启动截屏服务被系统拒绝")
                "⚠️ 系统拒绝了截屏服务（常见于本机省电/隐私策略）。\n\n" +
                    "不影响使用：请直接用手动搜索，或先在结果卡片点「诊断」校准标题控件 id。"
            }
            btnStart.text = "重新授权"
        } else {
            Diag.log(this, "Capture", "用户取消了屏幕采集授权")
            tvStatus.text = "已取消授权。\n不影响使用：手动搜索、无障碍自动识别都照常工作。"
        }
    }
}

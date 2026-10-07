package com.example.studentlookup.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.example.studentlookup.service.ScreenCaptureService

/**
 * 一次性申请屏幕采集权限。授权后启动 ScreenCaptureService 并自动关闭本页。
 */
class CapturePermissionActivity : Activity() {

    private val REQ_CAPTURE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // 说明用途，避免用户看到系统弹窗不知所以而点「取消」
        Toast.makeText(
            this,
            "请点「允许 / 立即开始」：仅用于识别微信标题，不会保存或上传画面",
            Toast.LENGTH_LONG
        ).show()
        runCatching { startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE) }
            .onFailure { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.startWithPermission(this, resultCode, data)
            Toast.makeText(this, "已授权截图识别", Toast.LENGTH_SHORT).show()
        } else if (requestCode == REQ_CAPTURE) {
            Toast.makeText(this, "已取消授权；仍可手动搜索姓名", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

package com.example.studentlookup.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.example.studentlookup.service.ScreenCaptureService

/**
 * 一次性申请屏幕采集权限。授权后启动 ScreenCaptureService 并自动关闭本页。
 */
class CapturePermissionActivity : Activity() {

    private val REQ_CAPTURE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.startWithPermission(this, resultCode, data)
        }
        finish()
    }
}

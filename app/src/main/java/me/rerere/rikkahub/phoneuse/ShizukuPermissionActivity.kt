package me.rerere.rikkahub.phoneuse

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/** 透明页面：只负责弹一次 Shizuku 授权对话框，然后自动关闭 */
class ShizukuPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1200)
    }

    companion object {
        private const val REQUEST_CODE = 4001
    }
}

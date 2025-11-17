package com.yyj.aiapp.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.yyj.aiapp.floating.GeminiFloatingService

class ScreenCapturePermissionActivity : AppCompatActivity() {

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startFloatingService(result.resultCode, result.data!!)
                Toast.makeText(this, "悬浮窗开启，请切换到其他应用", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "未授权悬浮窗/录屏，无法启用", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = manager.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    private fun startFloatingService(resultCode: Int, resultData: Intent) {
        val intent = Intent(this, GeminiFloatingService::class.java).apply {
            putExtra(GeminiFloatingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(GeminiFloatingService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

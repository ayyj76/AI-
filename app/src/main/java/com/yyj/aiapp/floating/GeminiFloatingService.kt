package com.yyj.aiapp.floating

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.yyj.aiapp.MainActivity
import com.yyj.aiapp.R
import com.yyj.aiapp.data.ConfigStore
import com.yyj.aiapp.data.GeminiConfig
import com.yyj.aiapp.data.ModelProvider
import com.yyj.aiapp.network.AiApiClient
import com.yyj.aiapp.permission.ScreenCapturePermissionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class GeminiFloatingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var bubbleView: android.view.View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var resultView: android.view.View? = null
    private var resultParams: WindowManager.LayoutParams? = null
    private var resultTextView: TextView? = null
    private var resultTitleView: TextView? = null
    private var hideResultRunnable: Runnable? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var capturing = false
    private var currentConfig: GeminiConfig? = null
    private var projectionForegroundSet = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post {
                showResultOverlay("录屏权限已失效，请在弹出的页面重新授权。", durationMs = 2000L)
                promptProjectionPermission()
            }
            releaseProjection()
        }
    }
    private var callbackRegistered = false

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CONFIG_UPDATED) {
                currentConfig = ConfigStore.readConfig(this@GeminiFloatingService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        currentConfig = ConfigStore.readConfig(this)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("${providerLabel()} 悬浮窗运行中"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            projectionForegroundSet = true
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("${providerLabel()} 悬浮窗运行中"))
            projectionForegroundSet = true
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            configReceiver,
            IntentFilter(ACTION_CONFIG_UPDATED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val projectionReady = if (resultCode == Activity.RESULT_OK && resultData != null) {
            initProjection(resultCode, resultData)
        } else {
            false
        }
        if (!projectionReady) {
            showResultOverlay("启动失败：请重新授予录屏权限。", durationMs = 2000L)
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        updateForeground(
            "${providerLabel()} 悬浮窗运行中",
            "点击悬浮球截图并发送给 ${providerLabel()}"
        )
        return START_STICKY
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(configReceiver)
        removeBubble()
        hideResultOverlay()
        releaseProjection()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initProjection(resultCode: Int, data: Intent): Boolean {
        releaseProjection()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data) ?: return false
        mediaProjection = projection
        projection.registerCallback(projectionCallback, handler)
        callbackRegistered = true
        ensureProjectionForeground()
        return true
    }

    private fun releaseProjection() {
        imageReader?.close()
        imageReader = null
        mediaProjection?.let { projection ->
            if (callbackRegistered) {
                projection.unregisterCallback(projectionCallback)
                callbackRegistered = false
            }
            projection.stop()
        }
        mediaProjection = null
        projectionForegroundSet = false
    }

    @SuppressLint("InflateParams")
    private fun showBubble() {
        if (bubbleView != null) return
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER or Gravity.START
        params.x = 100
        params.y = 200
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_floating_bubble, null)
        val wm = windowManager ?: return
        view.setOnTouchListener(FloatingTouchListener(params, wm))
        view.setOnClickListener { captureAndSend() }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            showFailureOverlay("悬浮窗权限缺失，请重新授权")
            stopSelf()
            return
        }
        bubbleView = view
        bubbleParams = params
    }

    private fun removeBubble() {
        val wm = windowManager ?: return
        bubbleView?.let { wm.removeView(it) }
        bubbleView = null
        bubbleParams = null
    }

    private fun captureAndSend() {
        if (capturing) {
            showFailureOverlay("正在处理上一张截图，请稍候")
            return
        }
        val projection = mediaProjection
        if (projection == null) {
            showResultOverlay("需要录屏权限才能截题，请在弹出的授权框中允许。", durationMs = 2000L)
            promptProjectionPermission()
            return
        }
        capturing = true
        bubbleView?.alpha = 0.6f
        serviceScope.launch {
            try {
                val base64 = captureScreen(projection)
                    ?: throw IllegalStateException("无法获取屏幕内容")
                broadcastResult(base64 = base64)
                val config = currentConfig ?: ConfigStore.readConfig(this@GeminiFloatingService)
                if (config.apiKey.isBlank()) {
                    updateForeground("缺少 API Key", "请在配置页填写后重新尝试")
                    return@launch
                }
                val response = AiApiClient.sendRequest(
                    provider = config.provider,
                    apiKey = config.apiKey,
                    apiBaseUrl = config.apiBaseUrl,
                    model = config.model,
                    prompt = config.prompt,
                    base64Image = base64
                )
                response.onSuccess { text ->
                    ConfigStore.saveLastResult(this@GeminiFloatingService, text)
                    broadcastResult(text, base64)
                    updateForeground("${config.provider.displayName} 已返回结果", text.take(50))
                    showResultOverlay(text)
                }.onFailure { error ->
                    val msg = error.message ?: "调用失败"
                    broadcastResult(msg, base64)
                    updateForeground("${config.provider.displayName} 调用失败", msg)
                    showResultOverlay(msg)
                }
            } catch (e: Exception) {
                val message = e.message ?: "截图失败"
                updateForeground("截图失败", message)
                showResultOverlay(message)
            } finally {
                capturing = false
                bubbleView?.alpha = 1f
            }
        }
    }

    private suspend fun captureScreen(projection: MediaProjection): String? =
        withContext(Dispatchers.IO) {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            imageReader = reader
            if (!callbackRegistered) {
                projection.registerCallback(projectionCallback, handler)
                callbackRegistered = true
            }
            val virtualDisplay = projection.createVirtualDisplay(
                "gemini_capture",
                width,
                height,
                density,
                0,
                reader.surface,
                null,
                null
            )
            if (virtualDisplay == null) {
                reader.close()
                imageReader = null
                return@withContext null
            }
            delay(200)
            val image = reader.acquireLatestImage() ?: run {
                virtualDisplay?.release()
                reader.close()
                imageReader = null
                return@withContext null
            }
            val planes = image.planes.first()
            val buffer = planes.buffer
            val pixelStride = planes.pixelStride
            val rowStride = planes.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val outputStream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            image.close()
            bitmap.recycle()
            cropped.recycle()
            virtualDisplay?.release()
            reader.close()
            imageReader = null
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        }

    private fun broadcastResult(text: String? = null, base64: String? = null) {
        val intent = Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_RESULT_TEXT, text)
            putExtra(EXTRA_RESULT_BASE64, base64)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI 悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return baseNotificationBuilder()
            .setContentTitle("${providerLabel()} 悬浮窗运行中")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()
    }

    private fun updateForeground(title: String, content: String) {
        val notification = baseNotificationBuilder()
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun baseNotificationBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(mainPendingIntent())
            .setOngoing(true)
            .addAction(R.drawable.ic_settings, "停止", stopPendingIntent())
    }

    private fun mainPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, GeminiFloatingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun showFailureOverlay(detail: String) {
        showResultOverlay("截屏失败：$detail")
    }

    private fun promptProjectionPermission() {
        val intent = Intent(this, ScreenCapturePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun showResultOverlay(message: String, durationMs: Long = 12000L) {
        handler.post {
            val wm = windowManager ?: return@post
            val view = resultView ?: run {
                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = 200
                val overlay = LayoutInflater.from(this).inflate(R.layout.view_result_overlay, null)
                overlay.findViewById<View>(R.id.button_close_result).setOnClickListener {
                    hideResultOverlay()
                }
                resultTextView = overlay.findViewById(R.id.text_result_overlay)
                resultTitleView = overlay.findViewById(R.id.text_result_title_overlay)
                try {
                    wm.addView(overlay, params)
                } catch (e: Exception) {
                    showResultOverlay("无法展示结果悬浮窗，请检查权限。", durationMs = 2000L)
                    return@post
                }
                resultParams = params
                resultView = overlay
                overlay
            }
            resultTitleView?.text = overlayTitleText()
            resultTextView?.text = message
            resultParams?.let { params ->
                runCatching { wm.updateViewLayout(view, params) }
            }
            hideResultRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable { hideResultOverlay() }
            hideResultRunnable = runnable
            handler.postDelayed(runnable, durationMs)
        }
    }

    private fun hideResultOverlay() {
        hideResultRunnable?.let { handler.removeCallbacks(it) }
        hideResultRunnable = null
        val wm = windowManager ?: return
        resultView?.let {
            runCatching { wm.removeView(it) }
        }
        resultView = null
        resultParams = null
        resultTextView = null
        resultTitleView = null
    }

    private fun ensureProjectionForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!projectionForegroundSet) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("${providerLabel()} 悬浮窗运行中"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                projectionForegroundSet = true
            }
        } else if (!projectionForegroundSet) {
            startForeground(NOTIFICATION_ID, buildNotification("${providerLabel()} 悬浮窗运行中"))
            projectionForegroundSet = true
        }
    }

    companion object {
        const val ACTION_RESULT = "io.github.geminifloat.RESULT"
        const val ACTION_CONFIG_UPDATED = "io.github.geminifloat.CONFIG_UPDATED"
        const val EXTRA_RESULT_TEXT = "extra_result_text"
        const val EXTRA_RESULT_BASE64 = "extra_result_base64"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP = "io.github.geminifloat.ACTION_STOP"
        private const val CHANNEL_ID = "gemini_float_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private fun providerLabel(): String =
        currentConfig?.provider?.displayName ?: ModelProvider.GOOGLE_GEMINI.displayName

    private fun overlayTitleText(): String {
        val provider = currentConfig?.provider ?: ModelProvider.GOOGLE_GEMINI
        val shortName = when (provider) {
            ModelProvider.GOOGLE_GEMINI -> "Gemini"
            ModelProvider.VOLCANO_DOUBAO -> "豆包"
        }
        return "$shortName 返回"
    }
}

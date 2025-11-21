package com.yyj.aiapp.floating

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
    private var virtualDisplay: VirtualDisplay? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureDensity: Int = 0
    private var bubbleAnimator: ObjectAnimator? = null
    private var glowAnimator: ObjectAnimator? = null
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
        return prepareVirtualDisplay()
    }

    private fun releaseProjection() {
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        captureWidth = 0
        captureHeight = 0
        captureDensity = 0
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

    private fun prepareVirtualDisplay(): Boolean {
        val projection = mediaProjection ?: return false
        if (virtualDisplay != null && imageReader != null) {
            return true
        }
        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        captureDensity = metrics.densityDpi
        val reader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            3
        )
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "ai_capture",
            captureWidth,
            captureHeight,
            captureDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        if (virtualDisplay == null) {
            reader.close()
            imageReader = null
            return false
        }
        return true
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
        startBubbleAnimations(view)
    }

    private fun removeBubble() {
        val wm = windowManager ?: return
        stopBubbleAnimations()
        bubbleView?.let { wm.removeView(it) }
        bubbleView = null
        bubbleParams = null
    }

    private fun captureAndSend() {
        if (capturing) {
            showFailureOverlay("正在处理上一张截图，请稍候")
            return
        }
        if (mediaProjection == null) {
            showResultOverlay("需要录屏权限才能截题，请在弹出的授权框中允许。", durationMs = 2000L)
            promptProjectionPermission()
            return
        }
        if (!prepareVirtualDisplay()) {
            showFailureOverlay("初始化录屏通道失败，请重试")
            promptProjectionPermission()
            return
        }
        capturing = true
        bubbleView?.alpha = 0.6f
        serviceScope.launch {
            try {
                val base64 = captureFrame()
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

    private suspend fun captureFrame(): String? =
        withContext(Dispatchers.IO) {
            val reader = imageReader ?: return@withContext null
            var image = reader.acquireLatestImage()
            if (image == null) {
                delay(120)
                image = reader.acquireLatestImage()
            }
            val captured = image ?: return@withContext null
            val planes = captured.planes.first()
            val buffer = planes.buffer
            val pixelStride = planes.pixelStride
            val rowStride = planes.rowStride
            val width = captureWidth
            val height = captureHeight
            if (width == 0 || height == 0) {
                captured.close()
                return@withContext null
            }
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
            captured.close()
            bitmap.recycle()
            cropped.recycle()
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
        notifySafely(NOTIFICATION_ID, notification)
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

    private fun startBubbleAnimations(container: View) {
        stopBubbleAnimations()
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1f)
        val bubblePulse = ObjectAnimator.ofPropertyValuesHolder(container, scaleX, scaleY).apply {
            duration = 1600L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
        bubbleAnimator = bubblePulse
        val glow = container.findViewById<View>(R.id.view_glow)
        if (glow != null) {
            glowAnimator = ObjectAnimator.ofFloat(glow, View.ROTATION, 0f, 360f).apply {
                duration = 6000L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun notifySafely(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return
            }
        }
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun stopBubbleAnimations() {
        bubbleAnimator?.cancel()
        bubbleAnimator = null
        glowAnimator?.cancel()
        glowAnimator = null
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

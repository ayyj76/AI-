package com.yyj.aiapp.ui.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yyj.aiapp.R
import com.yyj.aiapp.data.ConfigStore
import com.yyj.aiapp.data.GeminiConfig
import com.yyj.aiapp.data.ModelProvider
import com.yyj.aiapp.databinding.FragmentHomeBinding
import com.yyj.aiapp.floating.GeminiFloatingService
import com.yyj.aiapp.network.AiApiClient
import com.yyj.aiapp.permission.ScreenCapturePermissionActivity
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var currentConfig: GeminiConfig? = null
    private var selectedModel: String = ConfigStore.defaultModel(ModelProvider.VOLCANO_DOUBAO)
    private var currentProvider: ModelProvider = ModelProvider.VOLCANO_DOUBAO
    private var sendJob: Job? = null
    private var latestScreenshotBase64: String? = null
    private var manualImageBase64: String? = null
    private var manualImageBitmap: Bitmap? = null
    private lateinit var modelAdapter: ArrayAdapter<String>
    private lateinit var providerAdapter: ArrayAdapter<String>
    private val providerItems = listOf(ModelProvider.VOLCANO_DOUBAO, ModelProvider.GOOGLE_GEMINI)
    private val modelItems = mutableListOf<String>()
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                val bytes = bitmapToBytes(bitmap)
                applyManualImage(bytes, "已添加拍摄照片")
            } else {
                Snackbar.make(binding.root, "未获取到照片", Snackbar.LENGTH_SHORT).show()
            }
        }
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val bytes = readBytes(uri)
            if (bytes != null) {
                applyManualImage(bytes, "已添加相册图片")
            } else {
                Snackbar.make(binding.root, "读取图片失败", Snackbar.LENGTH_SHORT).show()
            }
        }
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCameraIntent()
            } else {
                Snackbar.make(binding.root, "未授予相机权限，无法拍照", Snackbar.LENGTH_SHORT).show()
            }
        }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GeminiFloatingService.ACTION_RESULT) return
            val text = intent.getStringExtra(GeminiFloatingService.EXTRA_RESULT_TEXT).orEmpty()
            val base64 = intent.getStringExtra(GeminiFloatingService.EXTRA_RESULT_BASE64).orEmpty()
            if (text.isNotBlank()) {
                binding.textResult.text = text
                ConfigStore.saveLastResult(requireContext(), text)
            }
            if (base64.isNotBlank()) {
                latestScreenshotBase64 = base64
                clearManualImage(showToast = false)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupProviderDropdown()
        setupModelDropdown()
        binding.buttonOverlay.setOnClickListener { handleOverlayFlow() }
        binding.buttonSend.setOnClickListener { sendToModel(isTest = false) }
        binding.buttonTestApi.setOnClickListener { sendToModel(isTest = true) }
        binding.buttonAddImage.setOnClickListener { showImagePickerDialog() }
    }

    override fun onResume() {
        super.onResume()
        loadConfig()
        binding.textResult.text = ConfigStore.readLastResult(requireContext())
        updateScreenshotStatus()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            resultReceiver,
            IntentFilter(GeminiFloatingService.ACTION_RESULT)
        )
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(resultReceiver)
        super.onStop()
    }

    override fun onDestroyView() {
        sendJob?.cancel()
        manualImageBitmap?.recycle()
        manualImageBitmap = null
        _binding = null
        super.onDestroyView()
    }

    private fun loadConfig() {
        currentConfig = ConfigStore.readConfig(requireContext())
        currentProvider = currentConfig?.provider ?: ConfigStore.readActiveProvider(requireContext())
        selectedModel = currentConfig?.model ?: ConfigStore.defaultModel(currentProvider)
        binding.inputProvider.setText(providerLabel(currentProvider), false)
        updateModelOptions()
        binding.inputModel.setText(selectedModel, false)
        updateSendButtonLabel(loading = false)
        updateManualPreview()
        updateScreenshotStatus()
    }

    private fun setupProviderDropdown() {
        val labels = providerItems.map { providerLabel(it) }
        providerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            labels
        )
        binding.inputProvider.setAdapter(providerAdapter)
        binding.inputProvider.setOnItemClickListener { _, _, position, _ ->
            if (position in providerItems.indices) {
                val provider = providerItems[position]
                if (provider != currentProvider) {
                    currentProvider = provider
                    ConfigStore.saveActiveProvider(requireContext(), provider)
                    val providerConfig = ConfigStore.readProviderConfig(requireContext(), provider)
                    selectedModel =
                        providerConfig.modelId.ifBlank { ConfigStore.defaultModel(provider) }
                    updateModelOptions()
                    binding.inputModel.setText(selectedModel, false)
                    updateSendButtonLabel(loading = false)
                    if (manualImageBitmap != null) {
                        updateManualPreview()
                    }
                }
            }
        }
    }

    private fun setupModelDropdown() {
        modelAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            modelItems
        )
        binding.inputModel.setAdapter(modelAdapter)
        binding.inputModel.setOnItemClickListener { _, _, position, _ ->
            if (position in 0 until modelAdapter.count) {
                selectedModel = modelAdapter.getItem(position).orEmpty()
                ConfigStore.saveModel(requireContext(), currentProvider, selectedModel)
            }
        }
    }

    private fun handleOverlayFlow() {
        if (!canDrawOverlays()) {
            showOverlayDialog()
            return
        }
        startActivity(Intent(requireContext(), ScreenCapturePermissionActivity::class.java))
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(requireContext())
        } else {
            true
        }
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(requireContext())
            .setMessage("需要开启“在其他应用上层显示”权限，是否跳转设置？")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                }
            }
            .show()
    }

    private fun updateModelOptions() {
        modelItems.clear()
        val defaults = when (currentProvider) {
            ModelProvider.GOOGLE_GEMINI ->
                resources.getStringArray(R.array.model_options_gemini)
            ModelProvider.VOLCANO_DOUBAO ->
                resources.getStringArray(R.array.model_options_doubao)
        }
        val configured = ConfigStore.readProviderConfig(requireContext(), currentProvider).modelId
        if (configured.isNotBlank()) {
            modelItems.add(configured)
        }
        defaults.forEach { model ->
            if (model.isNotBlank() && !modelItems.contains(model)) {
                modelItems.add(model)
            }
        }
        modelAdapter.notifyDataSetChanged()
    }

    private fun sendToModel(isTest: Boolean) {
        val config = currentConfig ?: ConfigStore.readConfig(requireContext())
        if (config.apiKey.isBlank()) {
            Snackbar.make(binding.root, "API Key 不能为空，请先在配置页保存。", Snackbar.LENGTH_SHORT).show()
            return
        }
        val extra = binding.inputExtra.text?.toString().orEmpty()
        val base64 = manualImageBase64 ?: latestScreenshotBase64
        val model = selectedModel.ifBlank { ConfigStore.defaultModel(config.provider) }
        val promptBuilder = StringBuilder(config.prompt)
        if (extra.isNotBlank()) {
            promptBuilder.append("\n\n补充描述：").append(extra)
        }
        val payloadPrompt = if (isTest) {
            "请回复：${config.provider.displayName} API Key 可用（仅用于测试）"
        } else {
            promptBuilder.toString()
        }
        if (!isTest && base64.isNullOrBlank()) {
            Snackbar.make(binding.root, "请先截屏或上传题目图片。", Snackbar.LENGTH_SHORT).show()
            return
        }
        sendJob?.cancel()
        sendJob = viewLifecycleOwner.lifecycleScope.launch {
            toggleLoading(true)
            val result = AiApiClient.sendRequest(
                provider = config.provider,
                apiKey = config.apiKey,
                apiBaseUrl = config.apiBaseUrl,
                model = model,
                prompt = payloadPrompt,
                base64Image = if (isTest) null else base64
            )
            result.onSuccess { text ->
                binding.textResult.text = text
                ConfigStore.saveLastResult(requireContext(), text)
                if (isTest) {
                    renderTestResult("接口正常：$text")
                } else {
                    renderTestResult("")
                }
            }.onFailure { error ->
                val msg = error.message ?: "调用失败"
                binding.textResult.text = msg
                if (isTest) {
                    renderTestResult("接口异常：$msg")
                }
            }
            toggleLoading(false)
        }
    }

    private fun toggleLoading(loading: Boolean) {
        binding.buttonSend.isEnabled = !loading
        binding.buttonTestApi.isEnabled = !loading
        updateSendButtonLabel(loading)
        binding.buttonTestApi.text =
            if (loading) "检测中..." else getString(R.string.button_test_api)
        binding.buttonOverlay.isEnabled = !loading
    }

    private fun updateSendButtonLabel(loading: Boolean) {
        binding.buttonSend.text = if (loading) {
            "发送中..."
        } else {
            getString(R.string.button_send_with_provider, currentProvider.displayName)
        }
    }

    private fun providerLabel(provider: ModelProvider): String {
        return when (provider) {
            ModelProvider.VOLCANO_DOUBAO -> getString(R.string.provider_option_doubao)
            ModelProvider.GOOGLE_GEMINI -> getString(R.string.provider_option_google)
        }
    }

    private fun renderTestResult(message: String) {
        if (message.isBlank()) {
            binding.layoutTestResults.isVisible = false
            binding.layoutTestResults.removeAllViews()
            return
        }
        binding.layoutTestResults.isVisible = true
        binding.layoutTestResults.removeAllViews()
        val textView = android.widget.TextView(requireContext())
        textView.text = message
        TextViewCompat.setTextAppearance(
            textView,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        )
        binding.layoutTestResults.addView(textView)
    }

    private fun showImagePickerDialog() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += getString(R.string.dialog_option_camera) to { requestCameraImage() }
        options += getString(R.string.dialog_option_gallery) to { launchGalleryIntent() }
        if (!manualImageBase64.isNullOrBlank()) {
            options += getString(R.string.dialog_option_remove_image) to {
                clearManualImage(showToast = true)
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options.getOrNull(which)?.second?.invoke()
            }
            .show()
    }

    private fun requestCameraImage() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCameraIntent()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraIntent() {
        cameraLauncher.launch(null)
    }

    private fun launchGalleryIntent() {
        galleryLauncher.launch("image/*")
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        return output.toByteArray()
    }

    private fun readBytes(uri: Uri): ByteArray? {
        return runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun updateScreenshotStatus() {
        binding.textScreenshotStatus.text = when {
            manualImageBase64?.isNotBlank() == true -> getString(R.string.screenshot_status_manual)
            latestScreenshotBase64?.isNotBlank() == true -> getString(R.string.screenshot_status_ready)
            else -> getString(R.string.screenshot_status_empty)
        }
    }

    private fun applyManualImage(bytes: ByteArray, message: String) {
        binding.imageManualPreview.setImageDrawable(null)
        manualImageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        manualImageBitmap?.recycle()
        manualImageBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        updateManualPreview()
        updateScreenshotStatus()
    }

    private fun clearManualImage(showToast: Boolean) {
        manualImageBase64 = null
        manualImageBitmap?.recycle()
        manualImageBitmap = null
        binding.imageManualPreview.setImageDrawable(null)
        if (showToast) {
            Snackbar.make(binding.root, "已清除上传图片", Snackbar.LENGTH_SHORT).show()
        }
        updateManualPreview()
        updateScreenshotStatus()
    }

    private fun updateManualPreview() {
        val hasManual = manualImageBitmap != null
        binding.inputLayoutExtra.isVisible = !hasManual
        binding.imageManualPreview.isVisible = hasManual
        if (hasManual) {
            binding.imageManualPreview.setImageBitmap(manualImageBitmap)
        } else {
            binding.imageManualPreview.setImageDrawable(null)
        }
    }
}

package com.yyj.aiapp.ui.config

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.yyj.aiapp.R
import com.yyj.aiapp.data.ConfigStore
import com.yyj.aiapp.data.ModelProvider
import com.yyj.aiapp.databinding.FragmentConfigBinding

class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    private var suppressProviderCallback = false
    private var currentProvider: ModelProvider = ModelProvider.VOLCANO_DOUBAO

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDropdowns()
        setupListeners()
        loadValues()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupDropdowns() {
        val googleAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.model_options_gemini)
        )
        val doubaoAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.model_options_doubao)
        )
        binding.inputGoogleModel.setAdapter(googleAdapter)
        binding.inputDoubaoModel.setAdapter(doubaoAdapter)
    }

    private fun setupListeners() {
        binding.buttonGooglePasteApi.setOnClickListener { pasteInto(binding.inputGoogleApi) }
        binding.buttonDoubaoPasteApi.setOnClickListener { pasteInto(binding.inputDoubaoApi) }
        binding.buttonGoogleSave.setOnClickListener {
            persistProvider(ModelProvider.GOOGLE_GEMINI)
        }
        binding.buttonDoubaoSave.setOnClickListener {
            persistProvider(ModelProvider.VOLCANO_DOUBAO)
        }
        binding.buttonGoogleReset.setOnClickListener {
            ConfigStore.resetProviderConfig(requireContext(), ModelProvider.GOOGLE_GEMINI)
            loadProviderValues(ModelProvider.GOOGLE_GEMINI)
            toast("已恢复 Google 默认配置")
        }
        binding.buttonDoubaoReset.setOnClickListener {
            ConfigStore.resetProviderConfig(requireContext(), ModelProvider.VOLCANO_DOUBAO)
            loadProviderValues(ModelProvider.VOLCANO_DOUBAO)
            toast("已恢复豆包默认配置")
        }
        binding.buttonSavePrompt.setOnClickListener {
            ConfigStore.savePrompt(requireContext(), binding.inputPrompt.text?.toString().orEmpty())
            toast("提示词已保存")
        }
        binding.buttonResetPrompt.setOnClickListener {
            ConfigStore.resetPrompt(requireContext())
            binding.inputPrompt.setText(ConfigStore.DEFAULT_PROMPT)
            toast("已恢复默认提示词")
        }
        binding.buttonSaveAll.setOnClickListener { saveEverything() }
        binding.toggleProvider.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressProviderCallback) return@addOnButtonCheckedListener
            val provider = toggleIdToProvider(checkedId)
            currentProvider = provider
            loadProviderValues(provider)
            updateProviderCardVisibility(provider)
            ConfigStore.saveActiveProvider(requireContext(), provider)
            toast("默认服务已切换为 ${provider.displayName}")
        }
    }

    private fun loadValues() {
        val config = ConfigStore.readConfig(requireContext())
        binding.inputPrompt.setText(config.prompt)
        val active = ConfigStore.readActiveProvider(requireContext())
        currentProvider = active
        loadProviderValues(active)
        updateProviderCardVisibility(active)
        suppressProviderCallback = true
        binding.toggleProvider.check(
            if (active == ModelProvider.GOOGLE_GEMINI) {
                binding.buttonProviderGoogle.id
            } else {
                binding.buttonProviderDoubao.id
            }
        )
        suppressProviderCallback = false
    }

    private fun loadProviderValues(provider: ModelProvider) {
        val config = ConfigStore.readProviderConfig(requireContext(), provider)
        val views = providerViews(provider)
        views.baseInput.setText(config.apiBaseUrl)
        views.apiInput.setText(config.apiKey)
        views.modelInput.setText(config.modelId, false)
    }

    private fun persistProvider(provider: ModelProvider, showToast: Boolean = true) {
        val views = providerViews(provider)
        ConfigStore.saveProviderConfig(
            context = requireContext(),
            provider = provider,
            apiBaseUrl = views.baseInput.text?.toString().orEmpty(),
            apiKey = views.apiInput.text?.toString().orEmpty(),
            modelId = views.modelInput.text?.toString().orEmpty()
        )
        if (showToast) {
            toast("${provider.displayName} 配置已保存")
        }
    }

    private fun saveEverything() {
        ConfigStore.savePrompt(requireContext(), binding.inputPrompt.text?.toString().orEmpty())
        persistProvider(currentProvider, showToast = false)
        ConfigStore.saveActiveProvider(requireContext(), currentProvider)
        toast("全部配置已保存")
    }

    private fun toggleIdToProvider(id: Int): ModelProvider =
        if (id == binding.buttonProviderGoogle.id) {
            ModelProvider.GOOGLE_GEMINI
        } else {
            ModelProvider.VOLCANO_DOUBAO
        }

    private fun pasteInto(target: TextInputEditText) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text =
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?.coerceToText(requireContext())
        if (text.isNullOrBlank()) {
            toast("剪贴板没有内容")
            return
        }
        target.setText(text)
    }

    private fun providerViews(provider: ModelProvider): ProviderViews {
        return if (provider == ModelProvider.GOOGLE_GEMINI) {
            ProviderViews(
                baseInput = binding.inputGoogleBaseUrl,
                apiInput = binding.inputGoogleApi,
                modelInput = binding.inputGoogleModel
            )
        } else {
            ProviderViews(
                baseInput = binding.inputDoubaoBaseUrl,
                apiInput = binding.inputDoubaoApi,
                modelInput = binding.inputDoubaoModel
            )
        }
    }

    private fun updateProviderCardVisibility(provider: ModelProvider) {
        binding.cardGoogle.isVisible = provider == ModelProvider.GOOGLE_GEMINI
        binding.cardDoubao.isVisible = provider == ModelProvider.VOLCANO_DOUBAO
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private data class ProviderViews(
        val baseInput: TextInputEditText,
        val apiInput: TextInputEditText,
        val modelInput: AutoCompleteTextView
    )
}

package com.yyj.aiapp.data

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.yyj.aiapp.floating.GeminiFloatingService

data class GeminiConfig(
    val apiKey: String,
    val prompt: String,
    val model: String,
    val provider: ModelProvider,
    val apiBaseUrl: String
)

object ConfigStore {

    private const val PREF_NAME = "gemini_pref"
    private const val KEY_API = "api_key" // legacy key, keep for backwards compatibility
    private const val KEY_PROMPT = "prompt"
    private const val KEY_MODEL = "model" // legacy model key
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_ACTIVE_PROVIDER = "active_provider"
    private const val KEY_GOOGLE_BASE_URL = "google_api_base"
    private const val KEY_GOOGLE_MODEL = "google_model"
    private const val KEY_GOOGLE_API = "google_api_key"
    private const val KEY_VOLCANO_BASE_URL = "doubao_api_base"
    private const val KEY_VOLCANO_MODEL = "doubao_model"
    private const val KEY_VOLCANO_API = "doubao_api_key"

    const val DEFAULT_PROMPT = "为我解决这道题，给我答案，无需过程"
    const val DEFAULT_GOOGLE_MODEL = "gemini-2.5-flash"
    const val DEFAULT_GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    const val DEFAULT_VOLCANO_BASE_URL =
        "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    const val DEFAULT_VOLCANO_MODEL = "doubao-seed-1-6-flash-250828"
    const val DEFAULT_VOLCANO_API_KEY = "fe2fdf25-7092-469a-b487-62b1672b9c1d"

    fun readConfig(context: Context): GeminiConfig {
        val provider = readActiveProvider(context)
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prompt = pref.getString(KEY_PROMPT, DEFAULT_PROMPT).orEmpty()
        val providerConfig = readProviderConfig(context, provider)
        return GeminiConfig(
            apiKey = providerConfig.apiKey,
            prompt = prompt,
            model = providerConfig.modelId,
            provider = provider,
            apiBaseUrl = providerConfig.apiBaseUrl
        )
    }

    fun readProviderConfig(context: Context, provider: ModelProvider): ProviderConfig {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val (keyApi, keyBase, keyModel, defaultBase, defaultModel) = when (provider) {
            ModelProvider.GOOGLE_GEMINI -> Quintuple(
                KEY_GOOGLE_API,
                KEY_GOOGLE_BASE_URL,
                KEY_GOOGLE_MODEL,
                DEFAULT_GOOGLE_BASE_URL,
                DEFAULT_GOOGLE_MODEL
            )

            ModelProvider.VOLCANO_DOUBAO -> Quintuple(
                KEY_VOLCANO_API,
                KEY_VOLCANO_BASE_URL,
                KEY_VOLCANO_MODEL,
                DEFAULT_VOLCANO_BASE_URL,
                DEFAULT_VOLCANO_MODEL
            )
        }
        val legacyApi = pref.getString(KEY_API, "").orEmpty()
        val apiFallback =
            if (provider == ModelProvider.GOOGLE_GEMINI) legacyApi else DEFAULT_VOLCANO_API_KEY
        val api = pref.getString(keyApi, apiFallback).orEmpty().ifBlank {
            if (provider == ModelProvider.VOLCANO_DOUBAO) DEFAULT_VOLCANO_API_KEY else ""
        }
        val baseUrl = pref.getString(keyBase, defaultBase).orEmpty().ifBlank { defaultBase }
        val modelId = pref.getString(
            keyModel,
            if (provider == ModelProvider.GOOGLE_GEMINI) pref.getString(KEY_MODEL, DEFAULT_GOOGLE_MODEL) else defaultModel
        ).orEmpty().ifBlank { defaultModel }
        return ProviderConfig(
            provider = provider,
            apiBaseUrl = baseUrl,
            apiKey = api,
            modelId = modelId
        )
    }

    fun saveProviderConfig(
        context: Context,
        provider: ModelProvider,
        apiBaseUrl: String,
        apiKey: String,
        modelId: String
    ) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val (keyApi, keyBase, keyModel) = when (provider) {
            ModelProvider.GOOGLE_GEMINI -> Triple(
                KEY_GOOGLE_API,
                KEY_GOOGLE_BASE_URL,
                KEY_GOOGLE_MODEL
            )

            ModelProvider.VOLCANO_DOUBAO -> Triple(
                KEY_VOLCANO_API,
                KEY_VOLCANO_BASE_URL,
                KEY_VOLCANO_MODEL
            )
        }
        pref.edit {
            putString(keyApi, apiKey)
            putString(keyBase, apiBaseUrl.ifBlank { defaultBaseUrl(provider) })
            putString(keyModel, modelId.ifBlank { defaultModel(provider) })
            if (provider == ModelProvider.GOOGLE_GEMINI) {
                // keep legacy keys in sync to avoid surprises
                putString(KEY_API, apiKey)
                putString(KEY_MODEL, modelId.ifBlank { DEFAULT_GOOGLE_MODEL })
            }
        }
        notifyService(context)
    }

    fun saveActiveProvider(context: Context, provider: ModelProvider) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ACTIVE_PROVIDER, provider.name)
        }
        notifyService(context)
    }

    fun readActiveProvider(context: Context): ModelProvider {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stored = pref.getString(KEY_ACTIVE_PROVIDER, ModelProvider.VOLCANO_DOUBAO.name)
        return stored?.let {
            runCatching { ModelProvider.valueOf(it) }.getOrNull()
        } ?: ModelProvider.VOLCANO_DOUBAO
    }

    fun readLastResult(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_RESULT, "").orEmpty()

    fun savePrompt(context: Context, prompt: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PROMPT, prompt.ifBlank { DEFAULT_PROMPT })
        }
        notifyService(context)
    }

    fun resetPrompt(context: Context) {
        savePrompt(context, DEFAULT_PROMPT)
    }

    fun saveModel(context: Context, provider: ModelProvider, model: String) {
        val current = readProviderConfig(context, provider)
        saveProviderConfig(
            context = context,
            provider = provider,
            apiBaseUrl = current.apiBaseUrl,
            apiKey = current.apiKey,
            modelId = model
        )
    }

    fun resetProviderConfig(context: Context, provider: ModelProvider) {
        val resetApi =
            if (provider == ModelProvider.VOLCANO_DOUBAO) DEFAULT_VOLCANO_API_KEY else ""
        saveProviderConfig(
            context = context,
            provider = provider,
            apiBaseUrl = defaultBaseUrl(provider),
            apiKey = resetApi,
            modelId = defaultModel(provider)
        )
    }

    fun saveLastResult(context: Context, result: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_RESULT, result)
        }
    }

    fun defaultBaseUrl(provider: ModelProvider): String =
        when (provider) {
            ModelProvider.GOOGLE_GEMINI -> DEFAULT_GOOGLE_BASE_URL
            ModelProvider.VOLCANO_DOUBAO -> DEFAULT_VOLCANO_BASE_URL
        }

    fun defaultModel(provider: ModelProvider): String =
        when (provider) {
            ModelProvider.GOOGLE_GEMINI -> DEFAULT_GOOGLE_MODEL
            ModelProvider.VOLCANO_DOUBAO -> DEFAULT_VOLCANO_MODEL
        }

    private fun notifyService(context: Context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(GeminiFloatingService.ACTION_CONFIG_UPDATED)
        )
    }

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}

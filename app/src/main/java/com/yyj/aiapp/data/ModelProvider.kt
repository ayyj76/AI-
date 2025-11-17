package com.yyj.aiapp.data

enum class ModelProvider(val displayName: String) {
    GOOGLE_GEMINI("Gemini (需要VPN)"),
    VOLCANO_DOUBAO("火山引擎豆包")
}

data class ProviderConfig(
    val provider: ModelProvider,
    val apiBaseUrl: String,
    val apiKey: String,
    val modelId: String
)

package com.yyj.aiapp.network

import com.yyj.aiapp.data.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object AiApiClient {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendRequest(
        provider: ModelProvider,
        apiKey: String,
        apiBaseUrl: String,
        model: String,
        prompt: String,
        base64Image: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider) {
                ModelProvider.GOOGLE_GEMINI ->
                    callGoogle(apiBaseUrl, apiKey, model, prompt, base64Image)

                ModelProvider.VOLCANO_DOUBAO ->
                    callDoubao(apiBaseUrl, apiKey, model, prompt, base64Image)
            }
        }
    }

    private fun callGoogle(
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        base64Image: String?
    ): String {
        val sanitizedBase = apiBaseUrl.trim().trimEnd('/')
        val url = "$sanitizedBase/$model:generateContent?key=$apiKey"
        val body = buildGoogleBody(prompt, base64Image)
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = parseGoogleError(raw).ifBlank {
                    "接口错误：HTTP ${response.code}"
                }
                throw IllegalStateException(message)
            }
            return parseGoogleContent(raw).ifBlank { "未返回内容" }
        }
    }

    private fun callDoubao(
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        base64Image: String?
    ): String {
        val url = apiBaseUrl.trim()
        val body = buildDoubaoBody(model, prompt, base64Image)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = parseDoubaoError(raw).ifBlank {
                    "接口错误：HTTP ${response.code}"
                }
                throw IllegalStateException(message)
            }
            return parseDoubaoContent(raw).ifBlank { "未返回内容" }
        }
    }

    private fun buildGoogleBody(prompt: String, base64Image: String?): String {
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            if (!base64Image.isNullOrBlank()) {
                val inline = JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", base64Image)
                put(JSONObject().put("inline_data", inline))
            }
        }
        val contents = JSONArray().put(JSONObject().put("parts", parts))
        return JSONObject().put("contents", contents).toString()
    }

    private fun buildDoubaoBody(model: String, prompt: String, base64Image: String?): String {
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            if (!base64Image.isNullOrBlank()) {
                val dataUrl = "data:image/jpeg;base64,$base64Image"
                val imagePayload = JSONObject().put("url", dataUrl)
                put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", imagePayload)
                )
            }
        }
        val message = JSONObject()
            .put("role", "user")
            .put("content", content)
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(message))
            .toString()
    }

    private fun parseGoogleContent(response: String): String {
        val root = JSONObject(response)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append("\n")
                builder.append(text)
            }
        }
        return builder.toString()
    }

    private fun parseGoogleError(raw: String): String {
        return runCatching {
            val root = JSONObject(raw)
            val error = root.optJSONObject("error")
            error?.optString("message").orEmpty()
        }.getOrDefault("")
    }

    private fun parseDoubaoContent(response: String): String {
        val root = JSONObject(response)
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val content = message.optJSONArray("content")
        if (content == null) {
            val fallback = message.optString("content")
            if (fallback.isNotBlank()) {
                return fallback
            }
            return ""
        }
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            val type = item.optString("type")
            if (type == "text" || type == "output_text") {
                val text = item.optString("text").ifBlank { item.optString("value") }
                if (text.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append(text)
                }
            }
        }
        return builder.toString()
    }

    private fun parseDoubaoError(raw: String): String {
        return runCatching {
            val root = JSONObject(raw)
            when {
                root.has("error") -> root.optJSONObject("error")?.optString("message").orEmpty()
                root.has("message") -> root.optString("message")
                else -> ""
            }
        }.getOrDefault("")
    }
}

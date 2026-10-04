package com.sqlai.agente.core.ai

import com.sqlai.agente.core.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ProviderPriority { LOCAL, GEMINI, GROK, DEEPSEEK, OPENAI, CUSTOM }

data class ChatMessage(val role: String, val content: String)

data class ProviderResult(
    val provider: String,
    val text: String,
    val fallbackUsed: Boolean = false,
    val latencyMs: Long = 0L,
    val error: String? = null,
)

/**
 * Unified AI gateway.
 *
 * Resolution order = user priority list. If a provider times out or 5xx's, the next
 * one in the chain is attempted automatically — local NDK model is consulted first
 * when hardware has headroom, otherwise the cheapest free-tier cloud endpoint.
 */
class ProviderRegistry(private val vault: Vault) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val priority: MutableList<ProviderPriority> = mutableListOf(
        ProviderPriority.LOCAL,
        ProviderPriority.GEMINI,
        ProviderPriority.DEEPSEEK,
        ProviderPriority.GROK,
        ProviderPriority.OPENAI,
    )

    fun priorityOrder(): List<ProviderPriority> = priority.toList()

    fun setPriorityOrder(order: List<ProviderPriority>) {
        priority.clear()
        priority.addAll(order)
    }

    suspend fun chat(messages: List<ChatMessage>, maxTokens: Int = 1024): ProviderResult =
        withContext(Dispatchers.IO) {
            var lastError: String? = null
            var fallback = false
            for (p in priority) {
                val started = System.currentTimeMillis()
                val r = runCatching { dispatch(p, messages, maxTokens) }
                val elapsed = System.currentTimeMillis() - started
                r.onSuccess { res ->
                    if (res.error == null) {
                        return@withContext res.copy(fallbackUsed = fallback, latencyMs = elapsed)
                    }
                    lastError = res.error
                    fallback = true
                }.onFailure { e -> lastError = e.message; fallback = true }
            }
            ProviderResult(
                provider = "none",
                text = "",
                fallbackUsed = true,
                error = lastError ?: "All providers failed",
            )
        }

    private fun dispatch(p: ProviderPriority, messages: List<ChatMessage>, maxTokens: Int): ProviderResult =
        when (p) {
            ProviderPriority.LOCAL -> localChat(messages, maxTokens)
            ProviderPriority.GEMINI -> geminiChat(messages, maxTokens)
            ProviderPriority.GROK -> grokChat(messages, maxTokens)
            ProviderPriority.DEEPSEEK -> deepseekChat(messages, maxTokens)
            ProviderPriority.OPENAI -> openaiChat(messages, maxTokens)
            ProviderPriority.CUSTOM -> ollamaChat(messages, maxTokens)
        }

    // ------------------------------ providers ------------------------------

    private fun localChat(messages: List<ChatMessage>, maxTokens: Int): ProviderResult {
        val bridge = com.sqlai.agente.ui.nativebridge.NativeEngine
        if (!bridge.isLoaded) return ProviderResult("local", "", error = "no local model loaded")
        val prompt = messages.joinToString("\n") { "${it.role}: ${it.content}" } + "\nassistant:"
        val out = bridge.generate(prompt, maxTokens, 0.7f)
        return if (out.isEmpty()) ProviderResult("local", "", error = "empty generation")
        else ProviderResult("local", out)
    }

    private fun geminiChat(messages: List<ChatMessage>, maxTokens: Int): ProviderResult {
        val key = vault.getSecret(Vault.KEY_GEMINI) ?: return ProviderResult("gemini", "", error = "no key")
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", if (m.role == "assistant") "model" else "user")
                        put("parts", JSONArray().put(JSONObject().put("text", m.content)))
                    })
                }
            })
            put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key"
        return postJson(url, body, "gemini", emptyMap())
    }

    private fun grokChat(messages: List<ChatMessage>, maxTokens: Int): ProviderResult {
        val key = vault.getSecret(Vault.KEY_GROK) ?: return ProviderResult("grok", "", error = "no key")
        return openAiShape("https://api.x.ai/v1/chat/completions", key, messages, maxTokens, "grok")
    }

    private fun deepseekChat(messages: List<ChatMessage>, maxTokens: Int): ProviderResult {
        val key = vault.getSecret(Vault.KEY_DEEPSEEK) ?: return ProviderResult("deepseek", "", error = "no key")
        return openAiShape("https://api.deepseek.com/v1/chat/completions", key, messages, maxTokens, "deepseek")
    }

    private fun openaiChat(messages: List<ChatMessage>, maxTokens: Int): ProviderResult {
        val key = vault.getSecret(Vault.KEY_OPENAI) ?: return ProviderResult("openai", "", error = "no key")
        return openAiShape("https://api.openai.com/v1/chat/completions", key, messages, maxTokens, "openai")
    }

    private fun ollamaChat(messages: List<ChatMessage>, maxTokens: Int): ProviderResult {
        val endpoint = vault.getSecret(Vault.KEY_OLLAMA)
            ?: vault.getPlain("ollama.endpoint")
            ?: return ProviderResult("ollama", "", error = "no local endpoint")
        val body = JSONObject().apply {
            put("model", vault.getPlain("ollama.model") ?: "qwen2.5-coder:3b")
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().put("role", m.role).put("content", m.content))
                }
            })
            put("stream", false)
            put("options", JSONObject().put("num_predict", maxTokens))
        }
        val url = endpoint.trimEnd('/') + "/api/chat"
        return postJson(url, body, "ollama", emptyMap())
    }

    private fun openAiShape(
        url: String, key: String, messages: List<ChatMessage>, maxTokens: Int, name: String,
    ): ProviderResult {
        val body = JSONObject().apply {
            put("model", vault.getPlain("$name.model") ?: defaultModel(name))
            put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().put("role", m.role).put("content", m.content))
                }
            })
        }
        return postJson(url, body, name, mapOf("Authorization" to "Bearer $key"))
    }

    private fun defaultModel(name: String) = when (name) {
        "grok" -> "grok-3-mini"
        "deepseek" -> "deepseek-chat"
        else -> "gpt-4o-mini"
    }

    private fun postJson(url: String, body: JSONObject, name: String, headers: Map<String, String>): ProviderResult {
        val reqBuilder = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        if (name == "gemini") reqBuilder.header("Content-Type", "application/json")
        return try {
            http.newCall(reqBuilder.build()).execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ProviderResult(name, "", error = "HTTP ${resp.code}: ${payload.take(200)}")
                }
                ProviderResult(name, extractContent(name, payload))
            }
        } catch (e: IOException) {
            ProviderResult(name, "", error = e.message)
        } catch (e: Exception) {
            ProviderResult(name, "", error = e.message)
        }
    }

    private fun extractContent(name: String, payload: String): String = runCatching {
        when (name) {
            "gemini" -> {
                val cand = JSONObject(payload).getJSONArray("candidates")
                cand.getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text")
            }
            "ollama" -> JSONObject(payload).getJSONObject("message").getString("text")
            else -> {
                val choices = JSONObject(payload).getJSONArray("choices")
                choices.getJSONObject(0).getJSONObject("message").getString("content")
            }
        }
    }.getOrDefault(payload.take(500))
}

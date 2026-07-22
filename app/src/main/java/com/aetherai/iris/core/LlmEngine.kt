package com.aetherai.iris.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls Groq's OpenAI-compatible chat completions API over HTTPS —
 * IRIS's "brain" is now a network call, not an on-device model. This
 * trades away the original 100%-offline requirement for something that
 * reliably works, after repeated failed attempts at local inference
 * (llama.cpp's Android build fighting Gradle plugin versions, then
 * LiteRT-LM's Gemma models being gated behind Hugging Face auth).
 * Requires internet at conversation time, not just at setup.
 */
class LlmEngine(private val context: Context) {

    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"
    private val model = "openai/gpt-oss-20b"

    private val systemPrompt =
        "You are IRIS, a calm and professional offline voice assistant, " +
        "inspired by JARVIS. Give concise spoken answers by default. " +
        "Do not claim to know things you cannot verify."

    fun isLoaded(): Boolean = ApiKeyStore.hasKey(context)

    /** Blocking network call — call from a background thread. */
    fun generate(conversationContext: String, userMessage: String): String {
        val apiKey = ApiKeyStore.getKey(context)
        if (apiKey.isBlank()) {
            return "I don't have an API key set up yet — add one in Settings."
        }

        return try {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

            if (conversationContext.isNotBlank()) {
                messages.put(JSONObject().put("role", "user").put("content", conversationContext))
            }
            messages.put(JSONObject().put("role", "user").put("content", userMessage))

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)
            body.put("temperature", 0.7)
            body.put("max_tokens", 300)

            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            connection.outputStream.use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "no details"
                return "I couldn't reach my brain right now (error $responseCode): $errorText".take(200)
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseText)
            val choices = json.getJSONArray("choices")
            val message = choices.getJSONObject(0).getJSONObject("message")
            message.getString("content").trim()
        } catch (e: Exception) {
            "I ran into a network problem: ${e.message}"
        }
    }

    fun unload() {
        // No persistent resources to release for a network-based engine.
    }
}

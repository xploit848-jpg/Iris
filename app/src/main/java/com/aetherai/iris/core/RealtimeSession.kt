package com.aetherai.iris.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.os.Build
import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

interface RealtimeSessionListener {
    fun onRealtimeState(state: String)
    fun onRealtimeError(message: String)
    fun onRealtimeToolCall(callId: String, name: String, arguments: JSONObject)
}

private class SessionTokenCallback(private val session: RealtimeSession) : Callback {
    override fun onFailure(call: Call, e: IOException) {
        session.onTokenFailure(e.message ?: "Could not reach the IRIS session server")
    }

    override fun onResponse(call: Call, response: Response) {
        response.use {
            if (!it.isSuccessful) {
                session.onTokenFailure("Session server returned HTTP ${it.code}")
                return
            }
            val body = it.body?.string().orEmpty()
            try {
                val json = JSONObject(body)
                val secret = json.optJSONObject("client_secret")?.optString("value").orEmpty()
                if (secret.isBlank()) {
                    session.onTokenFailure("Session server returned no client secret")
                } else {
                    session.onClientSecret(secret)
                }
            } catch (e: Exception) {
                session.onTokenFailure("Invalid session server response")
            }
        }
    }
}

private class RealtimeSocketListener(private val session: RealtimeSession) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        session.onSocketOpen(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        session.onSocketMessage(text)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        session.onSocketFailure(t.message ?: "Realtime connection failed")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        session.onSocketClosed()
    }
}

private class AudioCaptureRunnable(private val session: RealtimeSession) : Runnable {
    override fun run() {
        session.captureAudio()
    }
}

/**
 * A single, long-lived Realtime audio session. The Android app receives only
 * a short-lived client secret from the local session server; the permanent
 * OpenAI API key never enters the APK.
 */
class RealtimeSession(
    private val context: Context,
    private val sessionUrl: String,
    private val listener: RealtimeSessionListener
) {

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val httpClient = OkHttpClient()
    private var socket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onRealtimeError("Microphone permission is required")
            return
        }
        running.set(true)
        listener.onRealtimeState("CONNECTING")
        val request = Request.Builder().url(sessionUrl).post(okhttp3.RequestBody.create(null, ByteArray(0))).build()
        httpClient.newCall(request).enqueue(SessionTokenCallback(this))
    }

    fun stop() {
        running.set(false)
        socket?.close(1000, "User stopped IRIS")
        socket = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        captureThread = null
        listener.onRealtimeState("IDLE")
    }

    fun onTokenFailure(message: String) {
        running.set(false)
        listener.onRealtimeError(message)
    }

    fun onClientSecret(secret: String) {
        if (!running.get()) return
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=gpt-realtime")
            .addHeader("Authorization", "Bearer $secret")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()
        socket = httpClient.newWebSocket(request, RealtimeSocketListener(this))
    }

    fun onSocketOpen(webSocket: WebSocket) {
        val session = JSONObject()
            .put("type", "realtime")
            .put("model", "gpt-realtime")
            .put("instructions", "You are IRIS. Be concise. Use the screen tools when the user asks you to read or control the Android screen.")
            .put("audio", JSONObject()
                .put("input", JSONObject()
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                    .put("turn_detection", JSONObject()
                        .put("type", "server_vad")
                        .put("threshold", 0.5)
                        .put("prefix_padding_ms", 300)
                        .put("silence_duration_ms", 600)
                        .put("create_response", true)
                        .put("interrupt_response", true)))
                .put("output", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))))
            .put("tools", toolDefinitions())
        webSocket.send(JSONObject().put("type", "session.update").put("session", session).toString())
        startAudioCapture(webSocket)
        listener.onRealtimeState("LISTENING")
    }

    fun onSocketMessage(text: String) {
        try {
            val event = JSONObject(text)
            when (event.optString("type")) {
                "response.output_audio.delta", "response.audio.delta" -> {
                    val encoded = event.optString("delta")
                    if (encoded.isNotBlank()) playAudio(Base64.decode(encoded, Base64.DEFAULT))
                }
                "response.function_call_arguments.done" -> {
                    val args = JSONObject(event.optString("arguments", "{}"))
                    listener.onRealtimeToolCall(event.optString("call_id"), event.optString("name"), args)
                }
                "error" -> listener.onRealtimeError(event.optJSONObject("error")?.optString("message") ?: "Realtime API error")
            }
        } catch (e: Exception) {
            listener.onRealtimeError("Invalid Realtime event")
        }
    }

    fun onSocketFailure(message: String) {
        if (running.get()) listener.onRealtimeError(message)
    }

    fun onSocketClosed() {
        if (running.get()) listener.onRealtimeState("DISCONNECTED")
    }

    fun sendToolResult(callId: String, result: JSONObject) {
        val ws = socket ?: return
        val item = JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", result.toString()))
        ws.send(item.toString())
        ws.send(JSONObject().put("type", "response.create").toString())
    }

    fun captureAudio() {
        val record = audioRecord ?: return
        val buffer = ShortArray(1200)
        record.startRecording()
        while (running.get()) {
            val count = record.read(buffer, 0, buffer.size)
            if (count <= 0) continue
            val bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                bytes[i * 2] = (buffer[i].toInt() and 0xff).toByte()
                bytes[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
            }
            socket?.send(JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString())
        }
    }

    private fun startAudioCapture(webSocket: WebSocket) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBuffer.coerceAtLeast(2400) * 2)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        audioRecord = record
        captureThread = Thread(AudioCaptureRunnable(this), "iris-realtime-mic")
        captureThread?.start()
    }

    private fun playAudio(bytes: ByteArray) {
        if (audioTrack == null) {
            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT)
            audioTrack = if (Build.VERSION.SDK_INT >= 21) {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AUDIO_FORMAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(minBuffer.coerceAtLeast(2400))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else null
            audioTrack?.play()
        }
        audioTrack?.write(bytes, 0, bytes.size)
    }

    private fun toolDefinitions(): JSONArray {
        return JSONArray()
            .put(tool("read_screen", "Read visible text from the current Android screen", JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(tool("tap", "Tap a visible Android control by its text", objectSchema("target", "string")))
            .put(tool("swipe", "Scroll the current Android screen", objectSchema("direction", "string")))
            .put(tool("type_text", "Type text into the focused Android field", objectSchema("text", "string")))
    }

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject {
        return JSONObject().put("type", "function").put("name", name).put("description", description).put("parameters", parameters)
    }

    private fun objectSchema(name: String, type: String): JSONObject {
        return JSONObject().put("type", "object").put("properties", JSONObject().put(name, JSONObject().put("type", type))).put("required", JSONArray().put(name))
    }
}

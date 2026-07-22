package com.aetherai.iris.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

interface VoiceEngineListener {
    fun onSpeechFinished()
}

private class TtsInitListener(private val engine: VoiceEngine) : TextToSpeech.OnInitListener {
    override fun onInit(status: Int) {
        engine.onEngineReady(status == TextToSpeech.SUCCESS)
    }
}

private class TtsProgressListener(private val engine: VoiceEngine) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) {}

    override fun onDone(utteranceId: String?) {
        engine.onSpeechFinished()
    }

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) {
        engine.onSpeechFinished()
    }
}

/**
 * Wraps Android's built-in TextToSpeech engine. English-only for now —
 * the device has no offline Nepali voice (confirmed), and no bundled
 * offline model (Piper/sherpa-onnx) is wired in yet. Revisit as its own
 * module once a working Nepali TTS path is confirmed end-to-end.
 */
class VoiceEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingUtterance: String? = null
    var listener: VoiceEngineListener? = null

    init {
        tts = TextToSpeech(context, TtsInitListener(this))
    }

    fun onEngineReady(success: Boolean) {
        ready = success
        if (success) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(TtsProgressListener(this))
            val pending = pendingUtterance
            pendingUtterance = null
            if (pending != null) speakNow(pending)
        } else {
            // Keep voice input usable on devices without a TTS engine.
            listener?.onSpeechFinished()
        }
    }

    fun speak(text: String) {
        if (ready) {
            speakNow(text)
        } else {
            // Only the latest response matters while TTS is initialising.
            pendingUtterance = text
        }
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iris_speech")
    }

    fun stop() {
        pendingUtterance = null
        tts?.stop()
    }

    fun onSpeechFinished() {
        listener?.onSpeechFinished()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

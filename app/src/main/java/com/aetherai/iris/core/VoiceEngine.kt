package com.aetherai.iris.core

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

private class TtsInitListener(private val engine: VoiceEngine) : TextToSpeech.OnInitListener {
    override fun onInit(status: Int) {
        engine.onEngineReady(status == TextToSpeech.SUCCESS)
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
    private val pendingUtterances = mutableListOf<String>()

    init {
        tts = TextToSpeech(context, TtsInitListener(this))
    }

    fun onEngineReady(success: Boolean) {
        ready = success
        if (success) {
            tts?.language = Locale.US
            for (text in pendingUtterances) {
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
            }
            pendingUtterances.clear()
        }
    }

    fun speak(text: String) {
        if (ready) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            pendingUtterances.add(text)
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

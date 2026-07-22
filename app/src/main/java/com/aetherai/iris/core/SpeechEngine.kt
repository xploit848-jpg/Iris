package com.aetherai.iris.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

interface SttResultCallback {
    fun onResult(text: String)
    fun onError(message: String)
}

private class AndroidSttListener(private val callback: SttResultCallback) : RecognitionListener {
    override fun onResults(results: Bundle) {
        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull().orEmpty()
        callback.onResult(text)
    }
    override fun onError(error: Int) {
        callback.onError("Speech recognition error code $error")
    }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}

/**
 * English-only, via Android's on-device SpeechRecognizer. Nepali STT
 * (Vosk has no Nepali model; sherpa-onnx's Whisper path is unverified)
 * is deferred to its own module once a real model path is confirmed.
 */
class SpeechEngine(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listen(callback: SttResultCallback) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onError("Speech recognition not available on this device")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(AndroidSttListener(callback))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        intent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
        intent.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
        intent.putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 15000)
        r.startListening(intent)
        recognizer = r
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}

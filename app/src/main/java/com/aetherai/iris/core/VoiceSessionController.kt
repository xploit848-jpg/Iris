package com.aetherai.iris.core

import android.content.Context
import android.os.Handler
import android.os.Looper

enum class SessionOrbState { IDLE, LISTENING, THINKING, SPEAKING }

interface VoiceSessionListener {
    fun onOrbState(state: SessionOrbState)
    fun onStatusText(text: String)
}

private class SttCallbackImpl(private val controller: VoiceSessionController) : SttResultCallback {
    override fun onResult(text: String) {
        controller.onSttResult(text)
    }
    override fun onError(message: String) {
        controller.onSttError(message)
    }
}

private class SttResultRunnable(private val controller: VoiceSessionController, private val text: String) : Runnable {
    override fun run() {
        controller.handleSttResultOnUiThread(text)
    }
}

private class SttErrorRunnable(private val controller: VoiceSessionController, private val message: String) : Runnable {
    override fun run() {
        controller.handleSttErrorOnUiThread(message)
    }
}

private class RouteAndSpeakRunnable(private val controller: VoiceSessionController, private val transcript: String) : Runnable {
    override fun run() {
        controller.routeAndSpeak(transcript)
    }
}

private class OrbStateUiRunnable(private val controller: VoiceSessionController, private val state: SessionOrbState) : Runnable {
    override fun run() {
        controller.listener?.onOrbState(state)
    }
}

private class StatusTextUiRunnable(private val controller: VoiceSessionController, private val text: String) : Runnable {
    override fun run() {
        controller.listener?.onStatusText(text)
    }
}

/**
 * SpeechRecognizer requires all calls (create, startListening,
 * stopListening) to happen on the main thread — calling it from a
 * background thread throws immediately. This Runnable is the only
 * place startListening() actually executes, and every path that wants
 * to (re)start listening posts through here instead of calling the
 * engine directly, regardless of which thread they're on.
 */
private class PerformStartListeningRunnable(private val controller: VoiceSessionController) : Runnable {
    override fun run() {
        controller.performStartListening()
    }
}

private class PerformStopListeningRunnable(private val controller: VoiceSessionController) : Runnable {
    override fun run() {
        controller.performStopListening()
    }
}

private class GreetAndListenRunnable(private val controller: VoiceSessionController) : Runnable {
    override fun run() {
        controller.greetAndListen()
    }
}

/**
 * Owns the full listen -> route -> speak -> relisten loop, shared by
 * both the in-app Home page mic button and the background floating
 * orb overlay, so the two triggers behave identically.
 */
class VoiceSessionController(private val context: Context) {

    private val speechEngine = SpeechEngine(context)
    private val voiceEngine = VoiceEngine(context)
    private val llmEngine = LlmEngine(context)
    private val commandRouter = CommandRouter(context, llmEngine)
    private val mainHandler = Handler(Looper.getMainLooper())

    var listener: VoiceSessionListener? = null

    @Volatile
    var sessionActive = false
        private set

    /** Safe to call from any thread — SpeechRecognizer work always runs on main. */
    fun startSession() {
        if (sessionActive) return
        sessionActive = true
        mainHandler.post(GreetAndListenRunnable(this))
    }

    /** Safe to call from any thread. */
    fun stopSession() {
        sessionActive = false
        mainHandler.post(PerformStopListeningRunnable(this))
        postOrbState(SessionOrbState.IDLE)
        postStatusText("Idle")
    }

    fun greetAndListen() {
        postOrbState(SessionOrbState.SPEAKING)
        val greeting = GreetingHelper.buildGreeting(context)
        voiceEngine.speak(greeting)
        if (sessionActive) startListening()
    }

    /** Safe to call from any thread — always hops to main before touching SpeechRecognizer. */
    fun startListening() {
        mainHandler.post(PerformStartListeningRunnable(this))
    }

    fun performStartListening() {
        if (!sessionActive) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            postStatusText("Grant microphone permission in Settings first")
            postOrbState(SessionOrbState.IDLE)
            sessionActive = false
            return
        }
        postOrbState(SessionOrbState.LISTENING)
        postStatusText("Listening...")
        speechEngine.listen(SttCallbackImpl(this))
    }

    fun performStopListening() {
        speechEngine.stop()
    }

    fun onSttResult(text: String) {
        mainHandler.post(SttResultRunnable(this, text))
    }

    fun onSttError(message: String) {
        mainHandler.post(SttErrorRunnable(this, message))
    }

    /** Always called on the main thread (posted via onSttResult). */
    fun handleSttResultOnUiThread(text: String) {
        if (!sessionActive) return
        if (text.isBlank()) {
            postStatusText("Didn't catch that")
            startListening()
            return
        }
        postOrbState(SessionOrbState.THINKING)
        postStatusText("Thinking...")
        MemoryStore.appendConversationTurn("user", text)
        Thread(RouteAndSpeakRunnable(this, text)).start()
    }

    /** Always called on the main thread (posted via onSttError). */
    fun handleSttErrorOnUiThread(message: String) {
        if (!sessionActive) return
        postStatusText("Didn't catch that — try again")
        startListening()
    }

    /** Runs on a background thread — commandRouter.route() may block on network calls or Thread.sleep for multi-step actions. */
    fun routeAndSpeak(transcript: String) {
        val result = commandRouter.route(transcript)
        postStatusText(result.spokenReply)
        MemoryStore.appendConversationTurn("iris", result.spokenReply)

        postOrbState(SessionOrbState.SPEAKING)
        voiceEngine.speak(result.spokenReply)

        if (result.shouldStopListening) {
            sessionActive = false
            postOrbState(SessionOrbState.IDLE)
            postStatusText("Idle")
        } else if (sessionActive) {
            startListening()
        }
    }

    private fun postOrbState(state: SessionOrbState) {
        mainHandler.post(OrbStateUiRunnable(this, state))
    }

    private fun postStatusText(text: String) {
        mainHandler.post(StatusTextUiRunnable(this, text))
    }

    fun release() {
        sessionActive = false
        speechEngine.release()
        voiceEngine.release()
        llmEngine.unload()
    }
}

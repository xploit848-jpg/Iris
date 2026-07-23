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

private class RouteResultRunnable(
    private val controller: VoiceSessionController,
    private val result: RouteResult
) : Runnable {
    override fun run() {
        controller.handleRouteResultOnMainThread(result)
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

private class SpeechFinishedRunnable(private val controller: VoiceSessionController) : Runnable {
    override fun run() {
        controller.handleSpeechFinishedOnMainThread()
    }
}

private class ControllerTtsListener(private val controller: VoiceSessionController) : VoiceEngineListener {
    override fun onSpeechFinished() {
        controller.onSpeechFinished()
    }
}

/**
 * Owns the full listen -> route -> speak -> relisten loop, shared by
 * both the in-app Home page mic button and the background floating
 * orb overlay. Relistening happens the instant TTS reports it's
 * finished (via VoiceEngine's onSpeechFinished callback) rather than
 * after a guessed delay.
 */
class VoiceSessionController(private val context: Context) {

    private val speechEngine = SpeechEngine(context)
    private val voiceEngine = VoiceEngine(context)
    private val llmEngine = LlmEngine(context)
    private val commandRouter = CommandRouter(context, llmEngine)
    private val mainHandler = Handler(Looper.getMainLooper())

    var listener: VoiceSessionListener? = null

    init {
        voiceEngine.listener = ControllerTtsListener(this)
    }

    @Volatile
    var sessionActive = false
        private set

    fun startSession() {
        if (sessionActive) return
        sessionActive = true
        Thread(GreetAndListenRunnable(this)).start()
    }

    fun stopSession() {
        sessionActive = false
        mainHandler.post(PerformStopListeningRunnable(this))
        voiceEngine.stop()
        postOrbState(SessionOrbState.IDLE)
        postStatusText("Idle")
    }

    fun greetAndListen() {
        postOrbState(SessionOrbState.SPEAKING)
        val greeting = GreetingHelper.buildGreeting(context)
        voiceEngine.speak(greeting)
    }

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

    fun onSpeechFinished() {
        mainHandler.post(SpeechFinishedRunnable(this))
    }

    fun handleSpeechFinishedOnMainThread() {
        if (sessionActive) startListening()
    }

    fun onSttResult(text: String) {
        mainHandler.post(SttResultRunnable(this, text))
    }

    fun onSttError(message: String) {
        mainHandler.post(SttErrorRunnable(this, message))
    }

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

    fun handleSttErrorOnUiThread(message: String) {
        if (!sessionActive) return
        postStatusText("Didn't catch that — try again")
        startListening()
    }

    fun routeAndSpeak(transcript: String) {
        val result = commandRouter.route(transcript)
        if (!sessionActive) return
        MemoryStore.appendConversationTurn("iris", result.spokenReply)
        mainHandler.post(RouteResultRunnable(this, result))
    }

    fun handleRouteResultOnMainThread(result: RouteResult) {
        if (!sessionActive) return
        postStatusText(result.spokenReply)

        postOrbState(SessionOrbState.SPEAKING)
        voiceEngine.speak(result.spokenReply)

        if (result.shouldStopListening) {
            sessionActive = false
            postOrbState(SessionOrbState.IDLE)
            postStatusText("Idle")
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

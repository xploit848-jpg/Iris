package com.aetherai.iris.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aetherai.iris.service.IrisAccessibilityService
import org.json.JSONObject

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

private class RealtimeStateRunnable(
    private val controller: VoiceSessionController,
    private val state: String
) : Runnable {
    override fun run() {
        controller.handleRealtimeStateOnMainThread(state)
    }
}

private class RealtimeErrorRunnable(
    private val controller: VoiceSessionController,
    private val message: String
) : Runnable {
    override fun run() {
        controller.handleRealtimeErrorOnMainThread(message)
    }
}

private class RealtimeToolRunnable(
    private val controller: VoiceSessionController,
    private val callId: String,
    private val name: String,
    private val arguments: JSONObject
) : Runnable {
    override fun run() {
        controller.handleRealtimeToolOnMainThread(callId, name, arguments)
    }
}

private class ControllerRealtimeListener(private val controller: VoiceSessionController) : RealtimeSessionListener {
    override fun onRealtimeState(state: String) {
        controller.onRealtimeState(state)
    }

    override fun onRealtimeError(message: String) {
        controller.onRealtimeError(message)
    }

    override fun onRealtimeToolCall(callId: String, name: String, arguments: JSONObject) {
        controller.onRealtimeToolCall(callId, name, arguments)
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
    private val realtimeSession = RealtimeSession(context, BuildConfig.REALTIME_SESSION_URL, ControllerRealtimeListener(this))
    private var usingRealtime = false

    var listener: VoiceSessionListener? = null

    init {
        voiceEngine.listener = ControllerTtsListener(this)
    }

    @Volatile
    var sessionActive = false
        private set

    /** Safe to call from any thread — SpeechRecognizer work always runs on main. */
    fun startSession() {
        if (sessionActive) return
        sessionActive = true
        usingRealtime = true
        realtimeSession.start()
    }

    /** Safe to call from any thread. */
    fun stopSession() {
        sessionActive = false
        usingRealtime = false
        realtimeSession.stop()
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

    fun onRealtimeState(state: String) {
        mainHandler.post(RealtimeStateRunnable(this, state))
    }

    fun onRealtimeError(message: String) {
        mainHandler.post(RealtimeErrorRunnable(this, message))
    }

    fun onRealtimeToolCall(callId: String, name: String, arguments: JSONObject) {
        mainHandler.post(RealtimeToolRunnable(this, callId, name, arguments))
    }

    fun handleRealtimeStateOnMainThread(state: String) {
        if (!usingRealtime || !sessionActive) return
        when (state) {
            "CONNECTING" -> {
                postOrbState(SessionOrbState.THINKING)
                postStatusText("Connecting live voice...")
            }
            "LISTENING" -> {
                postOrbState(SessionOrbState.LISTENING)
                postStatusText("Listening...")
            }
        }
    }

    fun handleRealtimeErrorOnMainThread(message: String) {
        if (!sessionActive || !usingRealtime) return
        // Preserve the original recognizer path if the local token server,
        // network, or Realtime service is unavailable.
        usingRealtime = false
        realtimeSession.stop()
        postStatusText("Live voice unavailable — using standard voice mode")
        mainHandler.post(GreetAndListenRunnable(this))
    }

    fun handleRealtimeToolOnMainThread(callId: String, name: String, arguments: JSONObject) {
        if (!sessionActive || !usingRealtime) return
        val service = IrisAccessibilityService.instance
        val result = JSONObject()
        if (service == null) {
            result.put("ok", false).put("error", "Accessibility service is not enabled")
            realtimeSession.sendToolResult(callId, result)
            return
        }
        when (name) {
            "read_screen" -> result.put("ok", true).put("text", service.readScreenText().take(40).joinToString("\n"))
            "tap" -> result.put("ok", service.tapByText(arguments.optString("target")))
                .put("target", arguments.optString("target"))
            "swipe" -> result.put("ok", service.swipe(arguments.optString("direction")))
                .put("direction", arguments.optString("direction"))
            "type_text" -> result.put("ok", service.typeText(arguments.optString("text")))
            else -> result.put("ok", false).put("error", "Unknown screen tool")
        }
        realtimeSession.sendToolResult(callId, result)
    }

    /** Safe to call from any thread — always hops to main before touching SpeechRecognizer. */
    fun startListening() {
        mainHandler.post(PerformStartListeningRunnable(this))
    }

    fun performStartListening() {
        if (usingRealtime) return
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
        if (usingRealtime) return
        mainHandler.post(SttResultRunnable(this, text))
    }

    fun onSttError(message: String) {
        if (usingRealtime) return
        mainHandler.post(SttErrorRunnable(this, message))
    }

    /** Always called on the main thread (posted via onSttResult). */
    fun handleSttResultOnUiThread(text: String) {
        if (usingRealtime) return
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
        if (usingRealtime) return
        if (!sessionActive) return
        postStatusText("Didn't catch that — try again")
        startListening()
    }

    /** Runs on a background thread — commandRouter.route() may block on network calls or Thread.sleep for multi-step actions. */
    fun routeAndSpeak(transcript: String) {
        if (usingRealtime) return
        val result = commandRouter.route(transcript)
        if (!sessionActive) return
        MemoryStore.appendConversationTurn("iris", result.spokenReply)
        mainHandler.post(RouteResultRunnable(this, result))
    }

    /** TTS and voice-session state changes are owned by the main thread. */
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
        realtimeSession.stop()
        speechEngine.release()
        voiceEngine.release()
        llmEngine.unload()
    }
}

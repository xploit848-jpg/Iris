package com.aetherai.iris.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.aetherai.iris.core.SessionOrbState
import com.aetherai.iris.core.VoiceSessionController
import com.aetherai.iris.core.VoiceSessionListener
import com.aetherai.iris.ui.OrbState
import com.aetherai.iris.ui.OrbView

private class OrbTouchListener(
    private val service: IrisOverlayService,
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val orbView: View
) : View.OnTouchListener {

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var moved = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                    moved = true
                }
                params.x = initialX + dx
                params.y = initialY + dy
                windowManager.updateViewLayout(orbView, params)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    service.onOrbTapped()
                }
                return true
            }
        }
        return false
    }
}

private class OverlaySessionListener(private val service: IrisOverlayService) : VoiceSessionListener {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onOrbState(state: SessionOrbState) {
        mainHandler.post(OrbStateApplyRunnable(service, state))
    }

    override fun onStatusText(text: String) {
        // No visible status text in the overlay itself — orb animation is the feedback.
    }
}

private class OrbStateApplyRunnable(private val service: IrisOverlayService, private val state: SessionOrbState) : Runnable {
    override fun run() {
        service.applyOrbState(state)
    }
}

/**
 * Small draggable orb shown over other apps. Tapping it (without
 * having dragged) toggles a voice session in place — no Activity UI
 * opens. Tap again while listening/thinking/speaking to stop.
 */
class IrisOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "iris_overlay"
        const val NOTIFICATION_ID = 1002
    }

    private var windowManager: WindowManager? = null
    private var orbView: OrbView? = null
    private lateinit var sessionController: VoiceSessionController

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "IRIS Floating Orb", NotificationManager.IMPORTANCE_MIN)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        sessionController = VoiceSessionController(this)
        sessionController.listener = OverlaySessionListener(this)
        addOrbToWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("IRIS")
            .setContentText("Floating orb active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun addOrbToWindow() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val overlayType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            140,
            140,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        val orb = OrbView(this)
        orbView = orb
        orb.setOnTouchListener(OrbTouchListener(this, params, wm, orb))
        wm.addView(orb, params)
    }

    fun onOrbTapped() {
        if (sessionController.sessionActive) {
            sessionController.stopSession()
        } else {
            sessionController.startSession()
        }
    }

    fun applyOrbState(state: SessionOrbState) {
        orbView?.state = when (state) {
            SessionOrbState.IDLE -> OrbState.IDLE
            SessionOrbState.LISTENING -> OrbState.LISTENING
            SessionOrbState.THINKING -> OrbState.THINKING
            SessionOrbState.SPEAKING -> OrbState.SPEAKING
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionController.release()
        val wm = windowManager
        val orb = orbView
        if (wm != null && orb != null) {
            wm.removeView(orb)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

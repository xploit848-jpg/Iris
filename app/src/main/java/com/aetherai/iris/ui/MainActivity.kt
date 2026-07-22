package com.aetherai.iris.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.aetherai.iris.R
import com.aetherai.iris.core.ApiKeyStore
import com.aetherai.iris.core.MemoryStore
import com.aetherai.iris.core.PermissionManager
import com.aetherai.iris.core.PermissionSpec
import com.aetherai.iris.core.SessionOrbState
import com.aetherai.iris.core.VoiceSessionController
import com.aetherai.iris.core.VoiceSessionListener
import com.aetherai.iris.service.IrisAccessibilityService
import com.aetherai.iris.service.IrisOverlayService

private const val PAGE_HOME = 0
private const val PAGE_HISTORY = 1
private const val PAGE_MEMORY = 2
private const val PAGE_SETTINGS = 3

private class TabClickListener(
    private val activity: MainActivity,
    private val page: Int
) : View.OnClickListener {
    override fun onClick(v: View) {
        activity.showPage(page)
    }
}

private class MicButtonClickListener(private val activity: MainActivity) : View.OnClickListener {
    override fun onClick(v: View) {
        activity.onMicTapped()
    }
}

private class RequestAllClickListener(private val activity: MainActivity) : View.OnClickListener {
    override fun onClick(v: View) {
        activity.requestAllPermissions()
    }
}

private class SaveApiKeyClickListener(private val activity: MainActivity) : View.OnClickListener {
    override fun onClick(v: View) {
        activity.saveApiKeyFromInput()
    }
}

private class ToggleOverlayClickListener(private val activity: MainActivity) : View.OnClickListener {
    override fun onClick(v: View) {
        activity.toggleFloatingOrb()
    }
}

private class PermissionRowClickListener(
    private val activity: MainActivity,
    private val spec: PermissionSpec
) : View.OnClickListener {
    override fun onClick(v: View) {
        activity.requestSinglePermission(spec)
    }
}

private class ClearMemoryClickListener(private val activity: MainActivity) : View.OnClickListener {
    override fun onClick(v: View) {
        activity.clearMemory()
    }
}

private class HomeSessionListener(private val activity: MainActivity) : VoiceSessionListener {
    override fun onOrbState(state: SessionOrbState) {
        activity.applyOrbState(state)
    }
    override fun onStatusText(text: String) {
        activity.updateStatusText(text)
    }
}

class MainActivity : Activity() {

    private lateinit var contentFrame: FrameLayout
    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabMemory: TextView
    private lateinit var tabSettings: TextView

    private var currentPage = -1
    private var orbView: OrbView? = null
    private var statusTextView: TextView? = null
    private var apiKeyInput: EditText? = null
    private var modelStatusTextView: TextView? = null
    private var overlayToggleButton: TextView? = null
    private var overlayServiceRunning = false

    private lateinit var sessionController: VoiceSessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        contentFrame = findViewById(R.id.content_frame)
        tabHome = findViewById(R.id.tab_home)
        tabHistory = findViewById(R.id.tab_history)
        tabMemory = findViewById(R.id.tab_memory)
        tabSettings = findViewById(R.id.tab_settings)

        tabHome.setOnClickListener(TabClickListener(this, PAGE_HOME))
        tabHistory.setOnClickListener(TabClickListener(this, PAGE_HISTORY))
        tabMemory.setOnClickListener(TabClickListener(this, PAGE_MEMORY))
        tabSettings.setOnClickListener(TabClickListener(this, PAGE_SETTINGS))

        showPage(PAGE_HOME)

        sessionController = VoiceSessionController(this)
        sessionController.listener = HomeSessionListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionController.release()
    }

    fun saveApiKeyFromInput() {
        val key = apiKeyInput?.text?.toString() ?: ""
        ApiKeyStore.setKey(this, key)
        modelStatusTextView?.text = if (key.isNotBlank()) "API key saved" else "API key cleared"
    }

    fun toggleFloatingOrb() {
        if (!PermissionManager.canDrawOverlays(this)) {
            updateOverlayButtonText("Grant overlay permission in Settings first")
            PermissionManager.requestOverlayPermission(this)
            return
        }
        if (overlayServiceRunning) {
            stopService(Intent(this, IrisOverlayService::class.java))
            overlayServiceRunning = false
            updateOverlayButtonText("Enable Floating Orb")
        } else {
            startForegroundService(Intent(this, IrisOverlayService::class.java))
            overlayServiceRunning = true
            updateOverlayButtonText("Disable Floating Orb")
        }
    }

    private fun updateOverlayButtonText(text: String) {
        overlayToggleButton?.text = text
    }

    fun showPage(page: Int) {
        if (page == currentPage) return
        currentPage = page
        contentFrame.removeAllViews()

        resetTabColors()
        when (page) {
            PAGE_HOME -> {
                tabHome.setTextColor(getColor(R.color.iris_text_primary))
                inflateHome()
            }
            PAGE_HISTORY -> {
                tabHistory.setTextColor(getColor(R.color.iris_text_primary))
                inflateHistory()
            }
            PAGE_MEMORY -> {
                tabMemory.setTextColor(getColor(R.color.iris_text_primary))
                inflateMemory()
            }
            PAGE_SETTINGS -> {
                tabSettings.setTextColor(getColor(R.color.iris_text_primary))
                inflateSettings()
            }
        }
    }

    private fun resetTabColors() {
        val secondary = getColor(R.color.iris_text_secondary)
        tabHome.setTextColor(secondary)
        tabHistory.setTextColor(secondary)
        tabMemory.setTextColor(secondary)
        tabSettings.setTextColor(secondary)
    }

    private fun inflateHome() {
        val view = LayoutInflater.from(this).inflate(R.layout.page_home, contentFrame, false)
        orbView = view.findViewById(R.id.orb_view)
        statusTextView = view.findViewById(R.id.status_text)
        val micButton: TextView = view.findViewById(R.id.mic_button)
        micButton.setOnClickListener(MicButtonClickListener(this))
        orbView?.setOnClickListener(MicButtonClickListener(this))
        contentFrame.addView(view)
    }

    private fun inflateHistory() {
        val view = LayoutInflater.from(this).inflate(R.layout.page_history, contentFrame, false)
        val list: ListView = view.findViewById(R.id.history_list)
        val entries = MemoryStore.readConversationHistory()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        contentFrame.addView(view)
    }

    private fun inflateMemory() {
        val view = LayoutInflater.from(this).inflate(R.layout.page_memory, contentFrame, false)
        val list: ListView = view.findViewById(R.id.memory_list)
        val facts = MemoryStore.readMemoryFacts()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, facts)
        val clearButton: TextView = view.findViewById(R.id.clear_memory_button)
        clearButton.setOnClickListener(ClearMemoryClickListener(this))
        contentFrame.addView(view)
    }

    private fun inflateSettings() {
        val view = LayoutInflater.from(this).inflate(R.layout.page_settings, contentFrame, false)
        val requestAllButton: TextView = view.findViewById(R.id.request_all_button)
        requestAllButton.setOnClickListener(RequestAllClickListener(this))

        apiKeyInput = view.findViewById(R.id.api_key_input)
        apiKeyInput?.setText(ApiKeyStore.getKey(this))
        val saveButton: TextView = view.findViewById(R.id.save_api_key_button)
        saveButton.setOnClickListener(SaveApiKeyClickListener(this))

        modelStatusTextView = view.findViewById(R.id.model_status_text)
        modelStatusTextView?.text = if (ApiKeyStore.hasKey(this)) {
            "API key set — IRIS is ready"
        } else {
            "Get a free key at console.groq.com, paste it above, and tap Save"
        }

        overlayToggleButton = view.findViewById(R.id.toggle_overlay_button)
        overlayToggleButton?.text = if (overlayServiceRunning) "Disable Floating Orb" else "Enable Floating Orb"
        overlayToggleButton?.setOnClickListener(ToggleOverlayClickListener(this))

        val rowsContainer: LinearLayout = view.findViewById(R.id.permission_rows_container)
        rowsContainer.removeAllViews()

        val allSpecs = PermissionManager.runtimeSpecs + PermissionManager.specialSpecs
        for (spec in allSpecs) {
            val row = LayoutInflater.from(this).inflate(R.layout.row_permission, rowsContainer, false)
            val label: TextView = row.findViewById(R.id.perm_label)
            val status: TextView = row.findViewById(R.id.perm_status)
            label.text = spec.label
            updateRowStatus(status, spec)
            status.setOnClickListener(PermissionRowClickListener(this, spec))
            rowsContainer.addView(row)
        }
        contentFrame.addView(view)
    }

    private fun updateRowStatus(status: TextView, spec: PermissionSpec) {
        val granted = when (spec.type) {
            com.aetherai.iris.core.PermType.RUNTIME ->
                spec.manifestPermission != null &&
                    checkSelfPermission(spec.manifestPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED
            com.aetherai.iris.core.PermType.SPECIAL ->
                PermissionManager.isSpecialGranted(this, spec.id, IrisAccessibilityService::class.java.name)
        }
        status.text = if (granted) "Granted" else "Grant"
        status.setBackgroundColor(getColor(if (granted) R.color.orb_listening else R.color.iris_surface_alt))
    }

    fun requestSinglePermission(spec: PermissionSpec) {
        when (spec.type) {
            com.aetherai.iris.core.PermType.RUNTIME -> {
                if (spec.manifestPermission != null) {
                    requestPermissions(arrayOf(spec.manifestPermission), PermissionManager.RUNTIME_REQUEST_CODE)
                }
            }
            com.aetherai.iris.core.PermType.SPECIAL -> {
                PermissionManager.openSpecialSettings(this, spec.id)
            }
        }
    }

    fun requestAllPermissions() {
        PermissionManager.requestMissingRuntimePermissions(this)
        val firstMissingSpecial = PermissionManager.specialSpecs.firstOrNull {
            !PermissionManager.isSpecialGranted(this, it.id, IrisAccessibilityService::class.java.name)
        }
        if (firstMissingSpecial != null) {
            PermissionManager.openSpecialSettings(this, firstMissingSpecial.id)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (currentPage == PAGE_SETTINGS) {
            showPage(-1)
            showPage(PAGE_SETTINGS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentPage == PAGE_SETTINGS) {
            val page = currentPage
            currentPage = -1
            showPage(page)
        }
    }

    fun onMicTapped() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            statusTextView?.text = "Grant microphone permission in Settings first"
            return
        }
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

    fun updateStatusText(text: String) {
        statusTextView?.text = text
    }

    fun clearMemory() {
        MemoryStore.clearAll()
        showPage(-1)
        showPage(PAGE_MEMORY)
    }
}

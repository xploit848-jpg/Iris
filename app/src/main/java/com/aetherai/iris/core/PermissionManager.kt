package com.aetherai.iris.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

enum class PermType { RUNTIME, SPECIAL }

data class PermissionSpec(
    val id: String,
    val label: String,
    val type: PermType,
    val manifestPermission: String? = null // null for SPECIAL entries handled via intents
)

/**
 * Single source of truth for every permission IRIS ever asks for.
 * Runtime perms are requested in one batch via ActivityCompat.
 * Special perms (overlay, accessibility, notif listener, battery-opt,
 * exact alarm, all-files) each need their own Settings screen intent —
 * Android does not allow batching these.
 */
object PermissionManager {

    val runtimeSpecs: List<PermissionSpec> = buildList {
        add(PermissionSpec("mic", "Microphone", PermType.RUNTIME, android.Manifest.permission.RECORD_AUDIO))
        add(PermissionSpec("contacts_read", "Read Contacts", PermType.RUNTIME, android.Manifest.permission.READ_CONTACTS))
        add(PermissionSpec("contacts_write", "Write Contacts", PermType.RUNTIME, android.Manifest.permission.WRITE_CONTACTS))
        add(PermissionSpec("call_phone", "Phone Calls", PermType.RUNTIME, android.Manifest.permission.CALL_PHONE))
        add(PermissionSpec("phone_state", "Phone State", PermType.RUNTIME, android.Manifest.permission.READ_PHONE_STATE))
        add(PermissionSpec("sms_send", "Send SMS", PermType.RUNTIME, android.Manifest.permission.SEND_SMS))
        add(PermissionSpec("sms_read", "Read SMS", PermType.RUNTIME, android.Manifest.permission.READ_SMS))
        add(PermissionSpec("calendar_read", "Read Calendar", PermType.RUNTIME, android.Manifest.permission.READ_CALENDAR))
        add(PermissionSpec("calendar_write", "Write Calendar", PermType.RUNTIME, android.Manifest.permission.WRITE_CALENDAR))
        if (Build.VERSION.SDK_INT >= 33) {
            add(PermissionSpec("notifications", "Post Notifications", PermType.RUNTIME, android.Manifest.permission.POST_NOTIFICATIONS))
            add(PermissionSpec("media_images", "Media Images", PermType.RUNTIME, android.Manifest.permission.READ_MEDIA_IMAGES))
            add(PermissionSpec("media_audio", "Media Audio", PermType.RUNTIME, android.Manifest.permission.READ_MEDIA_AUDIO))
        } else {
            add(PermissionSpec("storage_read", "Read Storage", PermType.RUNTIME, android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(PermissionSpec("exact_alarm", "Exact Alarms", PermType.RUNTIME, "android.permission.SCHEDULE_EXACT_ALARM"))
        }
    }

    val specialSpecs: List<PermissionSpec> = listOf(
        PermissionSpec("accessibility", "Accessibility Service", PermType.SPECIAL),
        PermissionSpec("notif_listener", "Notification Listener", PermType.SPECIAL),
        PermissionSpec("overlay", "Display Over Other Apps", PermType.SPECIAL),
        PermissionSpec("battery_opt", "Ignore Battery Optimization", PermType.SPECIAL),
        PermissionSpec("all_files", "All Files Access", PermType.SPECIAL)
    )

    const val RUNTIME_REQUEST_CODE = 4200

    fun missingRuntimePermissions(context: Context): List<PermissionSpec> {
        return runtimeSpecs.filter { spec ->
            spec.manifestPermission != null &&
                context.checkSelfPermission(spec.manifestPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestMissingRuntimePermissions(activity: Activity) {
        val missing = missingRuntimePermissions(activity)
        if (missing.isEmpty()) return
        val perms = missing.mapNotNull { it.manifestPermission }.toTypedArray()
        activity.requestPermissions(perms, RUNTIME_REQUEST_CODE)
    }

    fun isAccessibilityServiceEnabled(context: Context, serviceClassName: String): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (info in enabledServices) {
            if (info.resolveInfo.serviceInfo.name == serviceClassName) return true
        }
        return false
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(context.packageName)
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else true
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openNotificationListenerSettings(context: Context) {
        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun isSpecialGranted(context: Context, id: String, accessibilityServiceClassName: String): Boolean {
        return when (id) {
            "accessibility" -> isAccessibilityServiceEnabled(context, accessibilityServiceClassName)
            "notif_listener" -> isNotificationListenerEnabled(context)
            "overlay" -> canDrawOverlays(context)
            "battery_opt" -> isIgnoringBatteryOptimizations(context)
            "all_files" -> hasAllFilesAccess()
            else -> false
        }
    }

    fun openSpecialSettings(context: Context, id: String) {
        when (id) {
            "accessibility" -> openAccessibilitySettings(context)
            "notif_listener" -> openNotificationListenerSettings(context)
            "overlay" -> requestOverlayPermission(context)
            "battery_opt" -> requestIgnoreBatteryOptimizations(context)
            "all_files" -> requestAllFilesAccess(context)
        }
    }
}

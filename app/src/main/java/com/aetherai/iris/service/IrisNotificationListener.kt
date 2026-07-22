package com.aetherai.iris.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aetherai.iris.core.NotificationEntry
import com.aetherai.iris.core.NotificationStore

class IrisNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return // don't log IRIS's own notifications

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val appLabel = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        NotificationStore.addEntry(
            NotificationEntry(
                appLabel = appLabel,
                title = title,
                text = text,
                timestampMillis = sbn.postTime
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op — entries age out of NotificationStore's window naturally.
    }
}

package com.aetherai.iris.core

data class NotificationEntry(
    val appLabel: String,
    val title: String,
    val text: String,
    val timestampMillis: Long
)

/**
 * In-memory, most-recent-first log of captured notifications. Nothing
 * here is spoken aloud automatically — IRIS only summarizes counts by
 * app ("three notifications: WhatsApp times two, Instagram times one")
 * and reads actual content only when explicitly asked for a specific
 * sender/app, per the privacy-first spec.
 */
object NotificationStore {

    private const val MAX_ENTRIES = 50
    private val entries = mutableListOf<NotificationEntry>()

    @Synchronized
    fun addEntry(entry: NotificationEntry) {
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.size - 1)
        }
    }

    @Synchronized
    fun recentSummary(withinMillis: Long = 15 * 60 * 1000L): String {
        val cutoff = System.currentTimeMillis() - withinMillis
        val recent = entries.filter { it.timestampMillis >= cutoff }
        if (recent.isEmpty()) return "No new notifications."

        val counts = LinkedHashMap<String, Int>()
        for (entry in recent) {
            counts[entry.appLabel] = (counts[entry.appLabel] ?: 0) + 1
        }
        val parts = counts.entries.joinToString(", ") { (app, count) ->
            if (count == 1) app else "$app times $count"
        }
        val total = recent.size
        val noun = if (total == 1) "notification" else "notifications"
        return "$total $noun: $parts."
    }

    @Synchronized
    fun findLatestMatching(nameOrApp: String): NotificationEntry? {
        val target = nameOrApp.lowercase()
        return entries.firstOrNull {
            it.appLabel.lowercase().contains(target) || it.title.lowercase().contains(target)
        }
    }
}

package com.aetherai.iris.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Named-class singleton (no companion-object lambdas) storing conversation turns
 * and long-term memory facts as newline-delimited JSON on internal storage.
 * Nothing here ever leaves the device — no network calls exist in this class.
 */
object MemoryStore {

    private lateinit var conversationsFile: File
    private lateinit var memoryFile: File
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    fun init(context: Context) {
        val dir = File(context.filesDir, "iris_data")
        if (!dir.exists()) dir.mkdirs()
        conversationsFile = File(dir, "conversations.ndjson")
        memoryFile = File(dir, "memory.ndjson")
    }

    fun appendConversationTurn(speaker: String, text: String) {
        val ts = timestampFormat.format(Date())
        val escaped = text.replace("\"", "\\\"").replace("\n", "\\n")
        val line = "{\"ts\":\"$ts\",\"speaker\":\"$speaker\",\"text\":\"$escaped\"}\n"
        conversationsFile.appendText(line)
    }

    fun readConversationHistory(): List<String> {
        if (!conversationsFile.exists()) return emptyList()
        return conversationsFile.readLines()
    }

    fun appendMemoryFact(fact: String) {
        val ts = timestampFormat.format(Date())
        val escaped = fact.replace("\"", "\\\"").replace("\n", "\\n")
        memoryFile.appendText("{\"ts\":\"$ts\",\"fact\":\"$escaped\"}\n")
    }

    fun readMemoryFacts(): List<String> {
        if (!memoryFile.exists()) return emptyList()
        return memoryFile.readLines()
    }

    fun clearAll() {
        if (conversationsFile.exists()) conversationsFile.delete()
        if (memoryFile.exists()) memoryFile.delete()
    }
}

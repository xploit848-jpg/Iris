package com.aetherai.iris.core

import android.content.Context
import com.aetherai.iris.service.IrisAccessibilityService

data class RouteResult(val spokenReply: String, val wasCommand: Boolean, val shouldStopListening: Boolean = false)

/**
 * Pattern-matched commands first (deterministic, no LLM/network needed).
 * Anything else falls through to the LLM for a real conversational reply.
 */
class CommandRouter(private val context: Context, private val llmEngine: LlmEngine) {

    private val appControl = AppControlManager(context)
    private val phoneControl = PhoneControlManager(context)
    private val smsControl = SmsControlManager(context)

    private val trailingFiller = listOf(" please", " for me", " app", " now")

    private val openAndSearchRegex = Regex("open\\s+(.+?)\\s+and\\s+search\\s+(.+)")
    private val searchOnAppRegex = Regex("search\\s+(.+?)\\s+(?:on|in)\\s+(.+)")

    fun route(transcript: String): RouteResult {
        val text = transcript.trim().lowercase()

        if (text.contains("stop listening") || text == "stop" || text.contains("that's all") || text.contains("thats all")) {
            return RouteResult("Okay, let me know if you need anything.", true, shouldStopListening = true)
        }

        // "open Facebook and search Bijay"
        val combo1 = openAndSearchRegex.find(text)
        if (combo1 != null) {
            val appName = combo1.groupValues[1].trim()
            val query = combo1.groupValues[2].trim()
            return performOpenAndSearch(appName, query)
        }

        // "search Bijay on Facebook" / "search Bijay in Facebook"
        val combo2 = searchOnAppRegex.find(text)
        if (combo2 != null) {
            val query = combo2.groupValues[1].trim()
            val appName = combo2.groupValues[2].trim()
            return performOpenAndSearch(appName, query)
        }

        val smsResult = tryHandleSms(text)
        if (smsResult != null) return smsResult

        if (text.contains("notification")) {
            return RouteResult(NotificationStore.recentSummary(), true)
        }

        val readNotifTarget = extractAfterKeyword(text, listOf("read "))
        if (readNotifTarget != null && readNotifTarget != "screen" && readNotifTarget != "the screen") {
            val entry = NotificationStore.findLatestMatching(readNotifTarget)
            return if (entry != null) {
                val body = if (entry.text.isNotBlank()) entry.text else entry.title
                RouteResult("From ${entry.appLabel}: $body", true)
            } else {
                RouteResult("I don't see a recent notification from $readNotifTarget", true)
            }
        }

        val callTarget = extractAfterKeyword(text, listOf("call ", "phone ", "dial "))
        if (callTarget != null) {
            val called = phoneControl.callByNameOrNumber(callTarget)
            return if (called) {
                RouteResult("Calling $callTarget", true)
            } else {
                RouteResult("I couldn't find a contact or number for $callTarget", true)
            }
        }

        val openTarget = extractAfterKeyword(text, listOf("open ", "launch "))
        if (openTarget != null) {
            val launched = appControl.launchAppByName(openTarget)
            return if (launched) {
                RouteResult("Opening $openTarget", true)
            } else {
                RouteResult("I couldn't find an app called $openTarget", true)
            }
        }

        if (text.contains("scroll down")) {
            val service = IrisAccessibilityService.instance
                ?: return RouteResult("Accessibility service isn't enabled yet — turn it on in Settings", true)
            return if (service.swipe("down")) RouteResult("Scrolling down", true)
            else RouteResult("I couldn't scroll the current screen", true)
        }

        if (text.contains("scroll up")) {
            val service = IrisAccessibilityService.instance
                ?: return RouteResult("Accessibility service isn't enabled yet — turn it on in Settings", true)
            return if (service.swipe("up")) RouteResult("Scrolling up", true)
            else RouteResult("I couldn't scroll the current screen", true)
        }

        val tapTarget = extractAfterKeyword(text, listOf("tap ", "press ", "click "))
        if (tapTarget != null) {
            val service = IrisAccessibilityService.instance
            if (service == null) {
                return RouteResult("Accessibility service isn't enabled yet — turn it on in Settings", true)
            }
            val tapped = service.tapByText(tapTarget)
            return if (tapped) {
                RouteResult("Tapped $tapTarget", true)
            } else {
                RouteResult("I couldn't find $tapTarget on screen", true)
            }
        }

        if (text.contains("what's on screen") || text.contains("whats on screen") || text.contains("what is on screen") || text == "read screen" || text.contains("read the screen")) {
            val service = IrisAccessibilityService.instance
            if (service == null) {
                return RouteResult("Accessibility service isn't enabled yet — turn it on in Settings", true)
            }
            val items = service.readScreenText()
            val summary = if (items.isEmpty()) "I don't see any text on screen" else items.take(6).joinToString(", ")
            return RouteResult(summary, true)
        }

        val conversationContext = recentConversationContext()
        val reply = llmEngine.generate(conversationContext, transcript)
        return RouteResult(reply, false)
    }

    /**
     * Best-effort multi-step: open the app, wait for it to load, tap a
     * "search" icon if one is visible, type the query, then try tapping
     * "search" again in case that submits it. Every step is independent
     * and failure-tolerant since on-screen layout varies a lot between
     * apps — this can partially succeed (app opens, search doesn't).
     */
    private fun performOpenAndSearch(appName: String, query: String): RouteResult {
        val launched = appControl.launchAppByName(appName)
        if (!launched) {
            return RouteResult("I couldn't find an app called $appName", true)
        }

        Thread.sleep(1800)
        val service = IrisAccessibilityService.instance
        if (service == null) {
            return RouteResult("Opened $appName, but I need the Accessibility service on to search inside it — turn it on in Settings", true)
        }

        service.tapByText("search")
        Thread.sleep(700)
        val typed = service.typeText(query)
        Thread.sleep(400)
        service.tapByText("search")

        return if (typed) {
            RouteResult("Opened $appName and searched for $query", true)
        } else {
            RouteResult("Opened $appName, but I couldn't find a search field to type into", true)
        }
    }

    private fun tryHandleSms(text: String): RouteResult? {
        val smsKeywords = listOf("text ", "message ", "sms ")
        var matchedKeyword: String? = null
        var startIdx = -1
        for (keyword in smsKeywords) {
            val idx = text.indexOf(keyword)
            if (idx != -1 && (startIdx == -1 || idx < startIdx)) {
                startIdx = idx
                matchedKeyword = keyword
            }
        }
        if (matchedKeyword == null) return null

        val remainder = text.substring(startIdx + matchedKeyword.length)
        val separators = listOf(" saying ", " that says ", " saying, ")
        var recipient: String? = null
        var body: String? = null
        for (sep in separators) {
            val sepIdx = remainder.indexOf(sep)
            if (sepIdx != -1) {
                recipient = remainder.substring(0, sepIdx).trim()
                body = remainder.substring(sepIdx + sep.length).trim()
                break
            }
        }

        if (recipient == null || body == null || recipient.isBlank() || body.isBlank()) {
            return RouteResult("Who should I text, and what should I say? Try \"text Bijay saying I'll be late\".", true)
        }

        val sent = smsControl.sendSms(recipient, body)
        return if (sent) {
            RouteResult("Texted $recipient: $body", true)
        } else {
            RouteResult("I couldn't find a contact for $recipient, or the message failed to send", true)
        }
    }

    private fun extractAfterKeyword(text: String, keywords: List<String>): String? {
        var bestIndex = -1
        var matchedKeyword = ""
        for (keyword in keywords) {
            val idx = text.indexOf(keyword)
            if (idx != -1 && (bestIndex == -1 || idx < bestIndex)) {
                bestIndex = idx
                matchedKeyword = keyword
            }
        }
        if (bestIndex == -1) return null

        var result = text.substring(bestIndex + matchedKeyword.length).trim()
        var changed = true
        while (changed) {
            changed = false
            for (filler in trailingFiller) {
                if (result.endsWith(filler.trim())) {
                    result = result.removeSuffix(filler.trim()).trim()
                    changed = true
                }
            }
        }
        return if (result.isBlank()) null else result
    }

    private fun recentConversationContext(): String {
        val lines = MemoryStore.readConversationHistory().takeLast(10)
        val sb = StringBuilder()
        for (line in lines) {
            val speaker = if (line.contains("\"speaker\":\"user\"")) "User" else "IRIS"
            val marker = "\"text\":\""
            val start = line.indexOf(marker)
            if (start == -1) continue
            val from = start + marker.length
            val end = line.indexOf("\"", from)
            if (end == -1) continue
            val lineText = line.substring(from, end)
            sb.append(speaker).append(": ").append(lineText).append("\n")
        }
        return sb.toString()
    }
}

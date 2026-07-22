package com.aetherai.iris.core

import android.content.Context
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object GreetingHelper {

    fun buildGreeting(context: Context): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour < 5 -> "night"
            hour < 12 -> "morning"
            hour < 17 -> "afternoon"
            hour < 21 -> "evening"
            else -> "night"
        }

        val locale = Locale.getDefault()
        val timeFormat = SimpleDateFormat("h:mm a", locale)
        val timeString = timeFormat.format(calendar.time)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryPart = if (batteryLevel >= 0) ", battery is at $batteryLevel percent" else ""

        return "Good $timeOfDay. It's $timeString$batteryPart. How can I help?"
    }
}

package com.aetherai.iris.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class AppControlManager(private val context: Context) {

    /** Fuzzy-matches [spokenName] against installed app labels and launches the closest match. Returns true if something launched. */
    fun launchAppByName(spokenName: String): Boolean {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolvedApps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)

        val target = normalize(spokenName)
        var bestPackage: String? = null
        var bestActivity: String? = null

        for (info in resolvedApps) {
            val label = normalize(info.loadLabel(pm).toString())
            if (label == target || label.contains(target) || target.contains(label)) {
                bestPackage = info.activityInfo.packageName
                bestActivity = info.activityInfo.name
                break
            }
        }

        if (bestPackage == null || bestActivity == null) return false

        val launchIntent = Intent(Intent.ACTION_MAIN)
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        launchIntent.setClassName(bestPackage, bestActivity)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    private fun normalize(s: String): String {
        return s.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
    }
}

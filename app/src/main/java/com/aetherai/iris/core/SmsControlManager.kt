package com.aetherai.iris.core

import android.content.Context
import android.provider.ContactsContract
import android.telephony.SmsManager

class SmsControlManager(private val context: Context) {

    /** Returns true if the SMS was handed off to the system for sending. */
    fun sendSms(target: String, message: String): Boolean {
        val trimmed = target.trim()
        if (trimmed.isBlank() || message.isBlank()) return false

        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")
        val looksLikeNumber = digitsOnly.length >= 6 && digitsOnly.length.toDouble() / trimmed.length > 0.6

        val number = if (looksLikeNumber) digitsOnly else lookupNumberByName(trimmed) ?: return false

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun lookupNumberByName(name: String): String? {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) return cursor.getString(numberIndex)
            }
        }
        return null
    }
}

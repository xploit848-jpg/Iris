package com.aetherai.iris.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract

/**
 * Resolves a spoken name to a phone number via the Contacts provider and
 * places the call. Falls back to treating the input as a literal number
 * if it's mostly digits (e.g. "call 98411234567").
 */
class PhoneControlManager(private val context: Context) {

    /** Returns true if a call intent was launched. */
    fun callByNameOrNumber(target: String): Boolean {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return false

        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")
        val looksLikeNumber = digitsOnly.length >= 6 && digitsOnly.length.toDouble() / trimmed.length > 0.6

        val number = if (looksLikeNumber) {
            digitsOnly
        } else {
            lookupNumberByName(trimmed) ?: return false
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: SecurityException) {
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

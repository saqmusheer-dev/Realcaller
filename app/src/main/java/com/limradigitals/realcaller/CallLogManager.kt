package com.limradigitals.realcaller

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog

/**
 * System call-log operations used by the default dialer UI.
 * Matching uses SmartCaller's normalized 10-digit key so formatted numbers
 * such as +91 90147 95445 and 9014795445 are treated as the same caller.
 */
object CallLogManager {
    fun canModifyCallLog(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /**
     * Deletes every call-log entry belonging to the supplied caller.
     * Returns the number of deleted rows, or -1 when permission is unavailable.
     */
    fun deleteCallerHistory(context: Context, number: String): Int {
        if (!canModifyCallLog(context)) return -1
        val target = normalize(number)
        if (target.isBlank()) return 0

        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                while (cursor.moveToNext()) {
                    val rowNumber = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    if (normalize(rowNumber) == target && idIndex >= 0) {
                        ids.add(cursor.getLong(idIndex))
                    }
                }
            }

            var deleted = 0
            ids.forEach { id ->
                deleted += context.contentResolver.delete(
                    ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, id),
                    null,
                    null
                )
            }
            return deleted
        } catch (_: SecurityException) {
            return -1
        } catch (_: Exception) {
            return 0
        }
    }

    private fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }
}

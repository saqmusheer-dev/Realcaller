package com.limradigitals.realcaller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog

object CallLogManager {
    fun canModifyCallLog(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun deleteCallerHistory(context: Context, number: String): Int {
        if (!canModifyCallLog(context)) return -1
        val target = normalize(number)
        if (target.isBlank()) return 0

        return try {
            val actualNumbers = linkedSetOf<String>()
            val ids = mutableListOf<Long>()

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
                    if (normalize(rowNumber) == target) {
                        if (rowNumber.isNotBlank()) actualNumbers.add(rowNumber)
                        if (idIndex >= 0) ids.add(cursor.getLong(idIndex))
                    }
                }
            }

            if (actualNumbers.isEmpty() && ids.isEmpty()) return 0

            var deleted = 0
            actualNumbers.forEach { actual ->
                deleted += context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    CallLog.Calls.NUMBER + " = ?",
                    arrayOf(actual)
                )
            }

            if (deleted == 0 && ids.isNotEmpty()) {
                ids.forEach { id ->
                    deleted += context.contentResolver.delete(
                        CallLog.Calls.CONTENT_URI,
                        CallLog.Calls._ID + " = ?",
                        arrayOf(id.toString())
                    )
                }
            }
            deleted
        } catch (_: SecurityException) {
            -1
        } catch (_: Exception) {
            0
        }
    }

    private fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }
}

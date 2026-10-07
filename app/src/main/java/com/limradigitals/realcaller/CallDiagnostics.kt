package com.limradigitals.realcaller

import android.content.Context

/** Local-only Telecom outgoing-call diagnostic trail. */
object CallDiagnostics {
    private const val PREFS = "smartcaller_call_diagnostics"
    private const val KEY_LAST = "last_event"
    private const val KEY_PENDING = "pending_number"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markOutgoingRequest(context: Context, number: String, accountLabel: String) {
        prefs(context).edit()
            .putString(KEY_PENDING, number)
            .putString(KEY_LAST, "REQUESTED • account=$accountLabel • " + now())
            .apply()
    }

    fun markRetry(context: Context, number: String, reason: String) {
        prefs(context).edit()
            .putString(KEY_PENDING, number)
            .putString(KEY_LAST, "RETRY • $reason • " + now())
            .apply()
    }

    fun markTelecomAccepted(context: Context, number: String) {
        prefs(context).edit()
            .putString(KEY_PENDING, number)
            .putString(KEY_LAST, "TELECOM LIVE • $number • " + now())
            .apply()
    }

    fun markCallAdded(context: Context, number: String, state: Int, direction: String) {
        prefs(context).edit()
            .putString(KEY_PENDING, number)
            .putString(KEY_LAST, "CALL ADDED • $direction • state=$state • " + now())
            .apply()
    }

    fun markDisconnected(context: Context, number: String, code: Int, label: String?, description: String?) {
        val detail = listOfNotNull(label?.takeIf { it.isNotBlank() }, description?.takeIf { it.isNotBlank() }).joinToString(" / ")
        val suffix = if (detail.isNotBlank()) " • $detail" else ""
        prefs(context).edit().putString(KEY_PENDING, number).putString(KEY_LAST, "DISCONNECTED • code=$code$suffix • " + now()).apply()
    }

    fun markFailure(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_LAST, "FAILURE • $reason • " + now()).apply()
    }

    fun lastEvent(context: Context): String = prefs(context).getString(KEY_LAST, "No outgoing-call diagnostic yet").orEmpty()

    private fun now(): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}
package com.limradigitals.realcaller.data

import android.content.Context

class CallerRepository(context: Context) {
    private val db = LocalDatabase(context.applicationContext)

    fun lookup(rawNumber: String): CallerRecord? {
        val normalized = NumberNormalizer.normalize(rawNumber)
        return normalized.takeIf { it.isNotEmpty() }?.let(db::findCaller)
    }

    fun recordIncomingCall(rawNumber: String) {
        val normalized = NumberNormalizer.normalize(rawNumber)
        if (normalized.isEmpty()) return
        val record = db.findCaller(normalized)
        db.addCallEvent(
            CallEvent(
                number = normalized,
                timestamp = System.currentTimeMillis(),
                reputationScore = record?.reputationScore ?: 50
            )
        )
    }

    fun seedDemoData() = db.seedDemoData()
}

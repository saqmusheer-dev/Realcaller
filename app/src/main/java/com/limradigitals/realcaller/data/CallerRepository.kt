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

    fun markAsSpam(rawNumber: String) {
        val normalized = NumberNormalizer.normalize(rawNumber)
        if (normalized.isEmpty()) return
        val current = db.findCaller(normalized)
            ?: CallerRecord(number = normalized)
        val updated = current.copy(
            category = "Reported Spam",
            reputationScore = (current.reputationScore - 25).coerceAtLeast(0),
            reportCount = current.reportCount + 1
        )
        db.upsertCaller(updated)
    }

    fun updateCallerInfo(rawNumber: String, name: String?, category: String?) {
        val normalized = NumberNormalizer.normalize(rawNumber)
        if (normalized.isEmpty()) return
        val current = db.findCaller(normalized) ?: CallerRecord(number = normalized)
        db.upsertCaller(
            current.copy(
                displayName = name?.trim().takeUnless { it.isNullOrEmpty() } ?: current.displayName,
                category = category?.trim().takeUnless { it.isNullOrEmpty() } ?: current.category
            )
        )
    }

    fun seedDemoData() = db.seedDemoData()
}

package com.limradigitals.realcaller.data

object ReputationEngine {
    fun classify(record: CallerRecord?): ReputationLevel = record?.level ?: ReputationLevel.UNKNOWN

    fun shouldSilence(record: CallerRecord?): Boolean =
        record != null && record.reputationScore <= 15

    fun label(record: CallerRecord?): String = when (classify(record)) {
        ReputationLevel.SAFE -> "Trusted"
        ReputationLevel.UNKNOWN -> "Unknown caller"
        ReputationLevel.SUSPICIOUS -> "Suspicious caller"
        ReputationLevel.SPAM -> "Likely spam"
        ReputationLevel.SCAM -> "Known scam"
    }
}

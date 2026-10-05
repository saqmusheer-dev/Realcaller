package com.limradigitals.realcaller.data

enum class ReputationLevel { SAFE, UNKNOWN, SUSPICIOUS, SPAM, SCAM }

data class CallerRecord(
    val number: String,
    val displayName: String? = null,
    val category: String? = null,
    val reputationScore: Int = 50,
    val reportCount: Int = 0,
    val isVerifiedBusiness: Boolean = false,
    val address: String? = null,
    val rating: Double? = null,
    val website: String? = null,
    val openingHours: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    val level: ReputationLevel
        get() = when {
            reputationScore <= 15 -> ReputationLevel.SCAM
            reputationScore <= 35 -> ReputationLevel.SPAM
            reputationScore <= 55 -> ReputationLevel.SUSPICIOUS
            reputationScore >= 75 -> ReputationLevel.SAFE
            else -> ReputationLevel.UNKNOWN
        }
}

data class CallEvent(
    val number: String,
    val timestamp: Long,
    val direction: String = "INCOMING",
    val reputationScore: Int = 50
)

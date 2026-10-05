package com.limradigitals.realcaller.data

object NumberNormalizer {
    fun normalize(raw: String, defaultCountryCode: String = "91"): String {
        val digits = raw.filter(Char::isDigit)
        if (digits.isEmpty()) return ""
        return when {
            raw.trim().startsWith("+") -> "+$digits"
            digits.startsWith("00") -> "+${digits.drop(2)}"
            digits.length == 10 && defaultCountryCode.isNotEmpty() -> "+$defaultCountryCode$digits"
            digits.length > 10 -> "+$digits"
            else -> digits
        }
    }
}

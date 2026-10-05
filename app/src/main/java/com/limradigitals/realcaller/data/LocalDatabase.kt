package com.limradigitals.realcaller.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "realcaller.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE callers (
                number TEXT PRIMARY KEY,
                display_name TEXT,
                category TEXT,
                reputation_score INTEGER NOT NULL DEFAULT 50,
                report_count INTEGER NOT NULL DEFAULT 0,
                verified_business INTEGER NOT NULL DEFAULT 0,
                address TEXT,
                rating REAL,
                website TEXT,
                opening_hours TEXT,
                latitude REAL,
                longitude REAL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE call_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                number TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                direction TEXT NOT NULL,
                reputation_score INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_call_events_number ON call_events(number)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Reserved for the first schema migration.
        }
    }

    fun findCaller(number: String): CallerRecord? {
        readableDatabase.query(
            "callers", null, "number = ?", arrayOf(number), null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return CallerRecord(
                number = c.getString(c.getColumnIndexOrThrow("number")),
                displayName = c.getStringOrNull("display_name"),
                category = c.getStringOrNull("category"),
                reputationScore = c.getInt(c.getColumnIndexOrThrow("reputation_score")),
                reportCount = c.getInt(c.getColumnIndexOrThrow("report_count")),
                isVerifiedBusiness = c.getInt(c.getColumnIndexOrThrow("verified_business")) == 1,
                address = c.getStringOrNull("address"),
                rating = c.getDoubleOrNull("rating"),
                website = c.getStringOrNull("website"),
                openingHours = c.getStringOrNull("opening_hours"),
                latitude = c.getDoubleOrNull("latitude"),
                longitude = c.getDoubleOrNull("longitude")
            )
        }
    }

    fun upsertCaller(record: CallerRecord) {
        val values = ContentValues().apply {
            put("number", record.number)
            put("display_name", record.displayName)
            put("category", record.category)
            put("reputation_score", record.reputationScore)
            put("report_count", record.reportCount)
            put("verified_business", if (record.isVerifiedBusiness) 1 else 0)
            put("address", record.address)
            put("rating", record.rating)
            put("website", record.website)
            put("opening_hours", record.openingHours)
            put("latitude", record.latitude)
            put("longitude", record.longitude)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("callers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addCallEvent(event: CallEvent) {
        val values = ContentValues().apply {
            put("number", event.number)
            put("timestamp", event.timestamp)
            put("direction", event.direction)
            put("reputation_score", event.reputationScore)
        }
        writableDatabase.insert("call_events", null, values)
    }

    fun seedDemoData() {
        upsertCaller(
            CallerRecord(
                number = "+919999999999",
                displayName = "Limra Digitals",
                category = "Digital Marketing & Web Design",
                reputationScore = 92,
                reportCount = 0,
                isVerifiedBusiness = true,
                address = "Hyderabad, Telangana",
                rating = 4.8,
                website = "https://limradigitals.com",
                openingHours = "Mon-Sat · 10:00 AM-8:00 PM"
            )
        )
        upsertCaller(
            CallerRecord(
                number = "+918888888888",
                displayName = "Telemarketing Number",
                category = "Telemarketing",
                reputationScore = 28,
                reportCount = 14
            )
        )
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? =
        getString(getColumnIndexOrThrow(column)).takeUnless { isNull(getColumnIndexOrThrow(column)) }

    private fun android.database.Cursor.getDoubleOrNull(column: String): Double? =
        getDouble(getColumnIndexOrThrow(column)).takeUnless { isNull(getColumnIndexOrThrow(column)) }
}

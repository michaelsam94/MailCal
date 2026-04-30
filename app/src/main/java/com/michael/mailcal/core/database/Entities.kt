package com.michael.mailcal.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "emails")
data class EmailEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val body: String,
    val receivedAtMillis: Long,
    val fromAddress: String? = null,
    val ccAddressesCsv: String? = null,
    val processed: Boolean = false
)

@Entity(
    tableName = "parsed_events",
    indices = [Index(value = ["emailId"], unique = true)]
)
data class ParsedEventEntity(
    @PrimaryKey val id: String,
    val emailId: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long?,
    val location: String?,
    val meetingLink: String? = null,
    val attendeesCsv: String? = null,
    val syncedToCalendar: Boolean = false,
    val calendarEventId: String? = null
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String
)

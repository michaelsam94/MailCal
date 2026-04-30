package com.michael.mailcal.feature_sync.domain

import com.michael.mailcal.core.common.AppResult

data class EmailMessage(
    val id: String,
    val subject: String,
    val body: String,
    val receivedAtMillis: Long
)

data class ParsedEvent(
    val id: String,
    val emailId: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long?,
    val location: String?,
    val meetingLink: String?,
    val attendees: List<String>
)

interface SyncRepository {
    suspend fun syncNewEmails(): AppResult<Int>
    suspend fun getPendingParsedEvents(): List<ParsedEvent>
    suspend fun markEmailProcessed(emailId: String): AppResult<Unit>
    suspend fun markParsedEventSynced(parsedEventId: String, calendarEventId: String): AppResult<Unit>
    suspend fun setGmailAccessToken(token: String): AppResult<Unit>
    suspend fun getParserStatus(): String?
}

package com.michael.mailcal.feature_calendar.domain

import com.michael.mailcal.core.common.AppResult

data class CalendarEvent(
    val id: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long?,
    val location: String?,
    val meetingLink: String?,
    val attendees: List<String>
)

interface CalendarRepository {
    suspend fun createEvent(event: CalendarEvent): AppResult<String>
    suspend fun deleteEvent(eventId: String): AppResult<Unit>
}

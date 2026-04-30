package com.michael.mailcal.core.network

data class GmailMessageDto(
    val id: String,
    val subject: String,
    val body: String,
    val receivedAtMillis: Long,
    val icsContents: List<String> = emptyList(),
    val from: String? = null,
    val cc: List<String> = emptyList()
)

data class CalendarEventDto(
    val id: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long?,
    val location: String?
)

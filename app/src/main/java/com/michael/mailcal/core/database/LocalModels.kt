package com.michael.mailcal.core.database

data class LocalEmail(
    val id: String,
    val subject: String,
    val body: String,
    val receivedAtMillis: Long
)

data class LocalParsedEvent(
    val id: String,
    val emailId: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long?,
    val location: String?
)

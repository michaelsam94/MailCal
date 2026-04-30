package com.michael.mailcal.feature_sync.data

import android.util.Log
import com.michael.mailcal.core.database.EmailEntity
import com.michael.mailcal.core.database.MailCalDao
import com.michael.mailcal.core.database.SyncStateEntity
import com.michael.mailcal.core.common.AppResult
import com.michael.mailcal.core.network.GmailApiClient
import com.michael.mailcal.feature_sync.domain.ParsedEvent
import com.michael.mailcal.feature_sync.domain.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncRepositoryImpl(
    private val dao: MailCalDao,
    private val gmailApiClient: GmailApiClient,
    private val parser: EmailEventParser
) : SyncRepository {
    override suspend fun syncNewEmails(): AppResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "syncNewEmails() started")
                val token = dao.getSyncState(GMAIL_ACCESS_TOKEN_KEY)
                if (token == null) {
                    Log.e(TAG, "Gmail access token not found")
                    AppResult.Error("Gmail access token not found")
                } else {
                    val cursor = dao.getSyncState(SYNC_CURSOR_KEY)?.toLongOrNull() ?: 0L
                    val query = if (cursor <= 0L) {
                        DEFAULT_INITIAL_QUERY
                    } else {
                        val bufferedAfterSeconds = ((cursor / 1000) - SYNC_BUFFER_SECONDS).coerceAtLeast(0L)
                        "after:$bufferedAfterSeconds $DEFAULT_INITIAL_QUERY"
                    }
                    Log.d(TAG, "Using query=$query, cursor=$cursor")
                    val ids = fetchMessageIds(token, query)
                    Log.d(TAG, "Fetched message ids count=${ids.size}")
                    if (ids.isEmpty()) {
                        dao.upsertSyncState(SyncStateEntity(SYNC_CURSOR_KEY, System.currentTimeMillis().toString()))
                        Log.d(TAG, "No new emails found")
                        AppResult.Success(0)
                    } else {
                        val emails = ids.map { id ->
                            val message = gmailApiClient.getMessage(token, id)
                            message to EmailEntity(
                                id = message.id,
                                subject = message.subject,
                                body = message.body,
                                receivedAtMillis = message.receivedAtMillis,
                                fromAddress = message.from,
                                ccAddressesCsv = message.cc.joinToString(",")
                            )
                        }
                        val insertResults = dao.insertEmails(emails.map { it.second })

                        var parsedCount = 0
                        var parserStatus = dao.getSyncState(PARSER_STATUS_KEY) ?: DEFAULT_PARSER_STATUS
                        val now = System.currentTimeMillis()
                        emails.forEachIndexed { index, pair ->
                            val message = pair.first
                            val email = pair.second
                            val existingParsed = dao.getParsedEventByEmailId(email.id)
                            val shouldAttemptParse =
                                insertResults[index] != -1L ||
                                    (existingParsed != null &&
                                        !existingParsed.syncedToCalendar &&
                                        existingParsed.startAtMillis < now)

                            if (!shouldAttemptParse) {
                                Log.d(TAG, "Email already exists and parse refresh not needed id=${email.id}")
                                return@forEachIndexed
                            }

                            val parsed = parser.parse(email, message.icsContents)
                            if (parsed != null && parsed.event.startAtMillis >= now) {
                                val eventToStore = if (existingParsed != null) {
                                    parsed.event.copy(
                                        id = existingParsed.id,
                                        syncedToCalendar = existingParsed.syncedToCalendar,
                                        calendarEventId = existingParsed.calendarEventId
                                    )
                                } else {
                                    parsed.event
                                }
                                dao.upsertParsedEvent(eventToStore)
                                parsedCount += 1
                                parserStatus = when (parsed.source) {
                                    EmailEventParser.ParserSource.ICS -> "Parser: ICS attachment"
                                    EmailEventParser.ParserSource.ML_KIT -> "Parser: ML Kit model ready"
                                    EmailEventParser.ParserSource.FALLBACK -> "Parser: Using fallback parser"
                                }
                                Log.d(TAG, "Parsed event for email=${email.id}, source=${parsed.source}")
                            } else {
                                if (parsed == null) {
                                    Log.d(
                                        TAG,
                                        "No event parsed for email=${email.id} subject='${email.subject.take(90)}' bodyPreview='${email.body.take(140)}'"
                                    )
                                } else {
                                    Log.d(
                                        TAG,
                                        "Parsed event rejected as past for email=${email.id} start=${parsed.event.startAtMillis} now=$now subject='${email.subject.take(90)}'"
                                    )
                                }
                            }
                        }

                        dao.upsertSyncState(SyncStateEntity(SYNC_CURSOR_KEY, System.currentTimeMillis().toString()))
                        dao.upsertSyncState(SyncStateEntity(PARSER_STATUS_KEY, parserStatus))
                        Log.d(TAG, "syncNewEmails() completed parsedCount=$parsedCount parserStatus=$parserStatus")
                        AppResult.Success(parsedCount)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "syncNewEmails() failed", t)
                if (t is IllegalStateException && t.message?.contains("HTTP 401") == true) {
                    AppResult.Error("Gmail session expired. Please sign in with Gmail again.", t)
                } else {
                    AppResult.Error("Email sync failed", t)
                }
            }
        }
    }

    override suspend fun getPendingParsedEvents(): List<ParsedEvent> {
        val now = System.currentTimeMillis()
        return dao.getPendingParsedEvents(now).map { entity ->
            ParsedEvent(
                id = entity.id,
                emailId = entity.emailId,
                title = entity.title,
                startAtMillis = entity.startAtMillis,
                endAtMillis = entity.endAtMillis,
                location = entity.location,
                meetingLink = entity.meetingLink,
                attendees = entity.attendeesCsv
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            )
        }
    }

    override suspend fun markEmailProcessed(emailId: String): AppResult<Unit> {
        return try {
            dao.markEmailProcessed(emailId)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error("Failed to mark email as processed", t)
        }
    }

    override suspend fun markParsedEventSynced(parsedEventId: String, calendarEventId: String): AppResult<Unit> {
        return try {
            dao.markParsedEventSynced(parsedEventId, calendarEventId)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error("Failed to mark parsed event as synced", t)
        }
    }

    override suspend fun setGmailAccessToken(token: String): AppResult<Unit> {
        return try {
            dao.upsertSyncState(SyncStateEntity(GMAIL_ACCESS_TOKEN_KEY, token))
            Log.d(TAG, "Stored Gmail access token")
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to store Gmail token", t)
            AppResult.Error("Failed to store Gmail token", t)
        }
    }

    override suspend fun getParserStatus(): String? {
        return dao.getSyncState(PARSER_STATUS_KEY)
    }

    private fun fetchMessageIds(token: String, query: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val result = gmailApiClient.listMessageIds(token, query, pageToken)
            ids += result.messageIds
            pageToken = result.nextPageToken
        } while (!pageToken.isNullOrBlank() && ids.size < MAX_FETCH_MESSAGES)
        return ids.distinct().take(MAX_FETCH_MESSAGES)
    }

    private companion object {
        const val TAG = "SyncRepositoryImpl"
        const val SYNC_CURSOR_KEY = "gmail_sync_cursor"
        const val GMAIL_ACCESS_TOKEN_KEY = "gmail_access_token"
        const val PARSER_STATUS_KEY = "parser_status"
        const val DEFAULT_INITIAL_QUERY = "in:anywhere"
        const val SYNC_BUFFER_SECONDS = 15 * 60L
        const val MAX_FETCH_MESSAGES = 250
        const val DEFAULT_PARSER_STATUS = "Parser: Waiting for first parsed email"
    }
}

package com.michael.mailcal.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MailCalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmails(emails: List<EmailEntity>): List<Long>

    @Query("SELECT * FROM parsed_events WHERE syncedToCalendar = 0 AND startAtMillis >= :fromMillis ORDER BY startAtMillis ASC")
    suspend fun getPendingParsedEvents(fromMillis: Long): List<ParsedEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertParsedEvent(event: ParsedEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParsedEvent(event: ParsedEventEntity): Long

    @Query("SELECT * FROM parsed_events WHERE emailId = :emailId LIMIT 1")
    suspend fun getParsedEventByEmailId(emailId: String): ParsedEventEntity?

    @Query("UPDATE emails SET processed = 1 WHERE id = :emailId")
    suspend fun markEmailProcessed(emailId: String): Int

    @Query("UPDATE parsed_events SET syncedToCalendar = 1, calendarEventId = :calendarEventId WHERE id = :parsedEventId")
    suspend fun markParsedEventSynced(parsedEventId: String, calendarEventId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("SELECT value FROM sync_state WHERE key = :key LIMIT 1")
    suspend fun getSyncState(key: String): String?
}

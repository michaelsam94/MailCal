package com.michael.mailcal.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.michael.mailcal.core.common.AppContainer
import com.michael.mailcal.core.common.AppResult
import com.michael.mailcal.feature_calendar.domain.CalendarEvent

class EmailSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d(TAG, "EmailSyncWorker started")
        val container = AppContainer.from(applicationContext)
        val syncRepository = container.syncRepository
        val calendarRepository = container.calendarRepository

        return when (syncRepository.syncNewEmails()) {
            is AppResult.Error -> {
                Log.e(TAG, "syncNewEmails returned error, retrying")
                Result.retry()
            }
            is AppResult.Success -> {
                val pending = syncRepository.getPendingParsedEvents()
                Log.d(TAG, "Pending parsed events count=${pending.size}")
                pending.forEach { parsed ->
                    val createResult = calendarRepository.createEvent(
                        CalendarEvent(
                            id = "",
                            title = parsed.title,
                            startAtMillis = parsed.startAtMillis,
                            endAtMillis = parsed.endAtMillis,
                            location = parsed.location,
                            meetingLink = parsed.meetingLink,
                            attendees = parsed.attendees
                        )
                    )
                    if (createResult is AppResult.Success) {
                        Log.d(TAG, "Created calendar event id=${createResult.data} for parsedId=${parsed.id}")
                        syncRepository.markParsedEventSynced(parsed.id, createResult.data)
                        syncRepository.markEmailProcessed(parsed.emailId)
                    } else if (createResult is AppResult.Error) {
                        Log.e(TAG, "Failed creating calendar event for parsedId=${parsed.id}: ${createResult.message}")
                    }
                }
                Log.d(TAG, "EmailSyncWorker completed successfully")
                Result.success()
            }
        }
    }

    private companion object {
        const val TAG = "EmailSyncWorker"
    }
}

package com.michael.mailcal.feature_calendar.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.michael.mailcal.core.common.AppResult
import com.michael.mailcal.feature_calendar.domain.CalendarEvent
import com.michael.mailcal.feature_calendar.domain.CalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

class CalendarRepositoryImpl(
    private val context: Context
) : CalendarRepository {
    override suspend fun createEvent(event: CalendarEvent): AppResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (!hasCalendarPermission()) {
                    return@withContext AppResult.Error("Calendar permission missing. Grant READ/WRITE calendar permission.")
                }
                val calendarId = getWritableCalendarId()
                    ?: return@withContext AppResult.Error("No writable calendar found on device")

                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DTSTART, event.startAtMillis)
                    put(CalendarContract.Events.DTEND, event.endAtMillis ?: (event.startAtMillis + ONE_HOUR_MS))
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.EVENT_LOCATION, event.location ?: event.meetingLink)
                    if (!event.meetingLink.isNullOrBlank()) {
                        put(CalendarContract.Events.DESCRIPTION, "Join meeting: ${event.meetingLink}")
                    }
                }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: return@withContext AppResult.Error("Calendar insert failed")
                val eventId = uri.lastPathSegment ?: return@withContext AppResult.Error("Calendar event id missing")
                AppResult.Success(eventId)
            } catch (t: Throwable) {
                AppResult.Error("Failed to create calendar event", t)
            }
        }
    }

    override suspend fun deleteEvent(eventId: String): AppResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (!hasCalendarPermission()) {
                    return@withContext AppResult.Error("Calendar permission missing. Grant READ/WRITE calendar permission.")
                }
                val eventUri = CalendarContract.Events.CONTENT_URI.buildUpon()
                    .appendPath(eventId)
                    .build()
                context.contentResolver.delete(eventUri, null, null)
                AppResult.Success(Unit)
            } catch (t: Throwable) {
                AppResult.Error("Failed to delete calendar event", t)
            }
        }
    }

    private fun hasCalendarPermission(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    private fun getWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getLong(0)
        }
    }

    private companion object {
        const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}

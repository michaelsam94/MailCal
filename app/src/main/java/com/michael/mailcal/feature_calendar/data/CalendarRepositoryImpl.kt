package com.michael.mailcal.feature_calendar.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.michael.mailcal.core.common.AppResult
import com.michael.mailcal.feature_auth.data.AuthSessionStore
import com.michael.mailcal.feature_calendar.domain.CalendarEvent
import com.michael.mailcal.feature_calendar.domain.CalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

class CalendarRepositoryImpl(
    private val context: Context,
    private val sessionStore: AuthSessionStore = AuthSessionStore(context)
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
                Log.d(TAG, "Inserted event id=$eventId into calendar_id=$calendarId title='${event.title}'")
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

    /**
     * Pick the calendar to write into, in this priority order:
     *   1. The signed-in user's primary Google calendar (account_name == signed-in email,
     *      account_type == com.google, ownerAccount == account_name, sync_events=1,
     *      calendar_access_level >= CAL_ACCESS_CONTRIBUTOR).
     *   2. Any writable calendar belonging to the signed-in account.
     *   3. The device's LOCAL calendar (account_type == LOCAL).
     *   4. As a last resort, any visible writable calendar.
     *
     * On devices with many Google accounts (common on Xiaomi/HyperOS), the previous
     * "first row of VISIBLE=1 ordered by IS_PRIMARY DESC" picker was unreliable -- it
     * silently inserted events on whichever account happened to sort first by _id.
     */
    private fun getWritableCalendarId(): Long? {
        val signedInEmail = sessionStore.currentUser()?.email?.takeIf { it.isNotBlank() }
        val resolver = context.contentResolver
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.IS_PRIMARY
        )

        fun queryFirstId(selection: String, args: Array<String>?): Long? {
            val cursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
            ) ?: return null
            return cursor.use {
                if (!it.moveToFirst()) null
                else {
                    val id = it.getLong(0)
                    val accountName = it.getString(1)
                    val accountType = it.getString(2)
                    Log.d(
                        TAG,
                        "Selected calendar id=$id account=$accountName type=$accountType " +
                            "for signedInEmail=$signedInEmail (selection=\"$selection\")"
                    )
                    id
                }
            }
        }

        if (signedInEmail != null) {
            val ownedPrimary = queryFirstId(
                selection = "${CalendarContract.Calendars.ACCOUNT_NAME}=? " +
                    "AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? " +
                    "AND ${CalendarContract.Calendars.OWNER_ACCOUNT}=? " +
                    "AND ${CalendarContract.Calendars.SYNC_EVENTS}=1 " +
                    "AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                args = arrayOf(signedInEmail, "com.google", signedInEmail)
            )
            if (ownedPrimary != null) return ownedPrimary

            val anyOwnedByEmail = queryFirstId(
                selection = "${CalendarContract.Calendars.ACCOUNT_NAME}=? " +
                    "AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                args = arrayOf(signedInEmail)
            )
            if (anyOwnedByEmail != null) return anyOwnedByEmail
        }

        val local = queryFirstId(
            selection = "${CalendarContract.Calendars.ACCOUNT_TYPE}=? " +
                "AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            args = arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL)
        )
        if (local != null) return local

        return queryFirstId(
            selection = "${CalendarContract.Calendars.VISIBLE}=1 " +
                "AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            args = null
        )
    }

    private companion object {
        const val TAG = "CalendarRepositoryImpl"
        const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}

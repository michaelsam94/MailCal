package com.michael.mailcal.feature_sync.data

import android.util.Log
import com.michael.mailcal.core.database.EmailEntity
import com.michael.mailcal.core.database.ParsedEventEntity
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class EmailEventParser {
    enum class ParserSource {
        ICS,
        ML_KIT,
        FALLBACK
    }

    data class ParseResult(
        val event: ParsedEventEntity,
        val source: ParserSource
    )

    private val entityExtractor: EntityExtractor by lazy {
        val options = EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
        EntityExtraction.getClient(options)
    }

    suspend fun parse(email: EmailEntity, icsContents: List<String>): ParseResult? {
        val fromIcs = parseIcs(email, icsContents)
        if (fromIcs != null) {
            Log.d(TAG, "parse(): ICS parsed for emailId=${email.id} start=${fromIcs.startAtMillis}")
            return ParseResult(fromIcs, ParserSource.ICS)
        }

        val source = "${email.subject} ${email.body}".lowercase(Locale.US)
        val mlKitStart = extractDateTimeWithMlKitIfAvailable(email.body)
        val looksLikeEvent = isLikelyEventEmail(source)
        if (mlKitStart == null && !looksLikeEvent) {
            Log.d(
                TAG,
                "parse(): rejected emailId=${email.id} reason=no_mlkit_datetime_and_no_event_keywords subject='${email.subject.take(90)}'"
            )
            return null
        }

        val fallbackStart = inferStartIfPossible(email.receivedAtMillis, source)
        val now = System.currentTimeMillis()
        val startAt = chooseBestStart(mlKitStart, fallbackStart, now) ?: return null
        val sourceUsed = when {
            mlKitStart != null && startAt == mlKitStart -> ParserSource.ML_KIT
            else -> ParserSource.FALLBACK
        }
        Log.d(
            TAG,
            "parse(): emailId=${email.id} mlKitStart=$mlKitStart fallbackStart=$fallbackStart chosenStart=$startAt source=$sourceUsed subject='${email.subject.take(90)}'"
        )
        return ParseResult(
            event = ParsedEventEntity(
                id = UUID.randomUUID().toString(),
                emailId = email.id,
                title = email.subject.ifBlank { "Meeting" },
                startAtMillis = startAt,
                endAtMillis = startAt + ONE_HOUR_MS,
                location = inferLocation(email.body),
                meetingLink = extractMeetingLink(email.body),
                attendeesCsv = buildAttendeesCsv(email.fromAddress, email.ccAddressesCsv)
            ),
            source = sourceUsed
        )
    }

    private fun parseIcs(email: EmailEntity, icsContents: List<String>): ParsedEventEntity? {
        if (icsContents.isEmpty()) return null
        val raw = icsContents.joinToString("\n")
        val eventBlock = Regex("""BEGIN:VEVENT(.*?)END:VEVENT""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        val summary = Regex("""(?m)^SUMMARY:(.+)$""").find(eventBlock)?.groupValues?.getOrNull(1)?.trim()
        val location = Regex("""(?m)^LOCATION:(.+)$""").find(eventBlock)?.groupValues?.getOrNull(1)?.trim()
        val dtStartRaw = Regex("""(?m)^DTSTART(?:;[^:]+)?:([0-9TZ]+)$""").find(eventBlock)?.groupValues?.getOrNull(1)
        val startAt = parseIcsDate(dtStartRaw) ?: return null
        val dtEndRaw = Regex("""(?m)^DTEND(?:;[^:]+)?:([0-9TZ]+)$""").find(eventBlock)?.groupValues?.getOrNull(1)
        val endAt = parseIcsDate(dtEndRaw) ?: (startAt + ONE_HOUR_MS)
        return ParsedEventEntity(
            id = UUID.randomUUID().toString(),
            emailId = email.id,
            title = summary ?: email.subject.ifBlank { "Calendar event" },
            startAtMillis = startAt,
            endAtMillis = endAt,
            location = location,
            meetingLink = extractMeetingLink("$raw ${email.body}"),
            attendeesCsv = buildAttendeesCsv(email.fromAddress, email.ccAddressesCsv)
        )
    }

    private fun parseIcsDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim()
        return runCatching {
            val year = normalized.substring(0, 4).toInt()
            if (year !in 2000..2100) return null
            val month = normalized.substring(4, 6).toInt() - 1
            val day = normalized.substring(6, 8).toInt()
            val hasTime = normalized.length >= 15
            val hour = if (hasTime) normalized.substring(9, 11).toInt() else 9
            val minute = if (hasTime) normalized.substring(11, 13).toInt() else 0
            Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.getOrNull()
    }

    private suspend fun extractDateTimeWithMlKitIfAvailable(text: String): Long? {
        if (text.isBlank()) return null
        return runCatching {
            entityExtractor.downloadModelIfNeeded().await()
            val params = EntityExtractionParams.Builder(text).build()
            val annotations: List<EntityAnnotation> = entityExtractor.annotate(params).await()
            extractEarliestDateTime(annotations)
        }.onFailure {
            Log.e(TAG, "extractDateTimeWithMlKitIfAvailable(): ML Kit failed", it)
        }.getOrNull()
    }

    private fun extractEarliestDateTime(annotations: List<EntityAnnotation>): Long? {
        var best: Long? = null
        for (annotation in annotations) {
            for (entity in annotation.entities) {
                if (entity.type == Entity.TYPE_DATE_TIME) {
                    val dateTimeEntity = entity as? DateTimeEntity ?: continue
                    val value = dateTimeEntity.timestampMillis
                    if (best == null || value < best) {
                        best = value
                    }
                }
            }
        }
        return best
    }

    private fun inferStartIfPossible(baseMillis: Long, source: String): Long? {
        val calendar = Calendar.getInstance().apply { timeInMillis = baseMillis }
        val explicitDate = Regex("""\b(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b""").find(source)
        if (explicitDate != null) {
            val day = explicitDate.groupValues[1].toIntOrNull()
            val month = explicitDate.groupValues[2].toIntOrNull()
            val year = explicitDate.groupValues[3].toIntOrNull()
            if (day != null && month != null && year != null && month in 1..12 && day in 1..31) {
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month - 1)
                calendar.set(Calendar.DAY_OF_MONTH, day)
            }
        }
        val monthNameDate = Regex("""\b([a-z]{3,9})\s+(\d{1,2}),?\s+(\d{4})\b""").find(source)
        if (monthNameDate != null) {
            val monthName = monthNameDate.groupValues[1]
            val day = monthNameDate.groupValues[2].toIntOrNull()
            val year = monthNameDate.groupValues[3].toIntOrNull()
            val monthIndex = monthNameToIndex(monthName)
            if (day != null && year != null && monthIndex != null) {
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, monthIndex)
                calendar.set(Calendar.DAY_OF_MONTH, day)
            }
        }
        val hasTomorrow = source.contains("tomorrow")
        val hasToday = source.contains("today")
        if (hasTomorrow) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        val timeRegex = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
        val match = timeRegex.find(source)
        if (match == null && !hasTomorrow && !hasToday) return null
        val hour = match?.groupValues?.get(1)?.toIntOrNull() ?: 10
        val minute = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun isLikelyEventEmail(source: String): Boolean {
        val eventKeywords = listOf(
            "meeting", "invite", "invitation", "calendar", "event", "appointment",
            "call", "webinar", "schedule", "scheduled", "reschedule", "interview", "calendly"
        )
        return eventKeywords.any { source.contains(it) }
    }

    private fun monthNameToIndex(month: String): Int? {
        return when (month.lowercase(Locale.US).take(3)) {
            "jan" -> 0
            "feb" -> 1
            "mar" -> 2
            "apr" -> 3
            "may" -> 4
            "jun" -> 5
            "jul" -> 6
            "aug" -> 7
            "sep" -> 8
            "oct" -> 9
            "nov" -> 10
            "dec" -> 11
            else -> null
        }
    }

    private fun chooseBestStart(
        mlKitStart: Long?,
        fallbackStart: Long?,
        nowMillis: Long
    ): Long? {
        val graceWindowMillis = 5 * 60 * 1000L
        val mlKitIsUpcoming = mlKitStart != null && mlKitStart >= (nowMillis - graceWindowMillis)
        val fallbackIsUpcoming = fallbackStart != null && fallbackStart >= (nowMillis - graceWindowMillis)

        return when {
            mlKitIsUpcoming -> mlKitStart
            fallbackIsUpcoming -> fallbackStart
            mlKitStart != null -> mlKitStart
            else -> fallbackStart
        }
    }

    private fun inferLocation(body: String): String? {
        val locationRegex = Regex("""\b(room|office|meet|zoom)\b.*""", RegexOption.IGNORE_CASE)
        return locationRegex.find(body)?.value?.trim()?.take(80)
    }

    private fun extractMeetingLink(text: String): String? {
        val meetingRegex = Regex(
            """https?://[^\s]+(?:teams\.microsoft\.com|meet\.google\.com|zoom\.us|webex\.com|calendly\.com|whereby\.com)[^\s]*""",
            RegexOption.IGNORE_CASE
        )
        return meetingRegex.find(text)?.value
    }

    private fun buildAttendeesCsv(fromAddress: String?, ccCsv: String?): String? {
        val all = mutableListOf<String>()
        extractEmails(fromAddress).forEach { all += it }
        ccCsv?.split(",")?.forEach { extractEmails(it).forEach { email -> all += email } }
        val cleaned = all.map { it.lowercase(Locale.US).trim() }.filter { it.isNotBlank() }.distinct()
        return cleaned.joinToString(",").ifBlank { null }
    }

    private fun extractEmails(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val regex = Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
        return regex.findAll(value).map { it.value }.toList()
    }

    private companion object {
        const val TAG = "EmailEventParser"
        const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}

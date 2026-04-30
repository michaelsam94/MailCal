package com.michael.mailcal.core.network

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

data class GmailListResult(
    val messageIds: List<String>,
    val nextPageToken: String?
)

class GmailApiClient {
    fun listMessageIds(
        accessToken: String,
        query: String,
        pageToken: String?
    ): GmailListResult {
        val url = buildString {
            append("https://gmail.googleapis.com/gmail/v1/users/me/messages")
            append("?maxResults=50")
            append("&includeSpamTrash=true")
            append("&q=${query.encodeUrlParam()}")
            if (!pageToken.isNullOrBlank()) append("&pageToken=${pageToken.encodeUrlParam()}")
        }
        val response = executeGet(url, accessToken)
        val json = JSONObject(response)
        val ids = mutableListOf<String>()
        val messages = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) ids += id
        }
        return GmailListResult(ids, json.optString("nextPageToken").ifBlank { null })
    }

    fun getMessage(accessToken: String, id: String): GmailMessageDto {
        val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full"
        val response = executeGet(url, accessToken)
        val json = JSONObject(response)
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val headers = payload.optJSONArray("headers") ?: JSONArray()

        var subject = ""
        var from: String? = null
        val ccValues = mutableListOf<String>()
        for (i in 0 until headers.length()) {
            val header = headers.optJSONObject(i) ?: continue
            val name = header.optString("name")
            val value = header.optString("value")
            when {
                name.equals("Subject", ignoreCase = true) -> subject = value
                name.equals("From", ignoreCase = true) -> from = value
                name.equals("Cc", ignoreCase = true) -> {
                    ccValues += value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                }
            }
        }
        val snippet = json.optString("snippet")
        val plainParts = mutableListOf<String>()
        val htmlParts = mutableListOf<String>()
        val icsContents = mutableListOf<String>()
        collectParts(accessToken, id, payload, plainParts, htmlParts, icsContents)
        val htmlAsText = htmlParts.joinToString("\n") { stripHtmlToText(it) }
        val mergedBody = buildString {
            append(snippet)
            if (plainParts.isNotEmpty()) {
                append("\n")
                append(plainParts.joinToString("\n"))
            }
            if (htmlAsText.isNotBlank()) {
                append("\n")
                append(htmlAsText)
            }
        }.trim()
        val dateMillis = json.optLong("internalDate", System.currentTimeMillis())
        return GmailMessageDto(
            id = id,
            subject = subject,
            body = mergedBody,
            receivedAtMillis = dateMillis,
            icsContents = icsContents,
            from = from,
            cc = ccValues
        )
    }

    private fun collectParts(
        accessToken: String,
        messageId: String,
        part: JSONObject,
        plainParts: MutableList<String>,
        htmlParts: MutableList<String>,
        icsContents: MutableList<String>
    ) {
        val mimeType = part.optString("mimeType")
        val filename = part.optString("filename")
        val body = part.optJSONObject("body") ?: JSONObject()
        val data = body.optString("data")
        val attachmentId = body.optString("attachmentId")

        val decodedInline = decodeBase64Url(data)
        if (mimeType.equals("text/plain", ignoreCase = true) && decodedInline.isNotBlank()) {
            plainParts += decodedInline
        }
        if (mimeType.equals("text/html", ignoreCase = true) && decodedInline.isNotBlank()) {
            htmlParts += decodedInline
        }
        if ((mimeType.equals("text/calendar", ignoreCase = true) || filename.endsWith(".ics", ignoreCase = true))
            && decodedInline.isNotBlank()
        ) {
            icsContents += decodedInline
        }
        if ((mimeType.equals("text/calendar", ignoreCase = true) || filename.endsWith(".ics", ignoreCase = true))
            && attachmentId.isNotBlank()
        ) {
            val attachment = getAttachmentContent(accessToken, messageId, attachmentId)
            if (attachment.isNotBlank()) icsContents += attachment
        }

        val parts = part.optJSONArray("parts") ?: JSONArray()
        for (i in 0 until parts.length()) {
            val child = parts.optJSONObject(i) ?: continue
            collectParts(accessToken, messageId, child, plainParts, htmlParts, icsContents)
        }
    }

    private fun getAttachmentContent(accessToken: String, messageId: String, attachmentId: String): String {
        val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/attachments/$attachmentId"
        val response = executeGet(url, accessToken)
        val json = JSONObject(response)
        return decodeBase64Url(json.optString("data"))
    }

    private fun executeGet(url: String, accessToken: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return connection.useAndRead()
    }
}

private fun HttpURLConnection.useAndRead(): String {
    val status = responseCode
    val stream = if (status in 200..299) inputStream else errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (status !in 200..299) {
        throw IllegalStateException("HTTP $status: $text")
    }
    disconnect()
    return text
}

private fun String.encodeUrlParam(): String = java.net.URLEncoder.encode(this, "UTF-8")

private fun decodeBase64Url(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return runCatching {
        val decoded = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        String(decoded, Charsets.UTF_8)
    }.getOrDefault("")
}

private fun stripHtmlToText(html: String): String {
    return html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}

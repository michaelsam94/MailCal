package com.michael.mailcal.feature_sync.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.michael.mailcal.core.common.AppContainer
import com.michael.mailcal.core.common.AppResult
import com.michael.mailcal.feature_calendar.domain.CalendarEvent
import com.michael.mailcal.feature_sync.domain.ParsedEvent
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PendingEventUi(
    val parsedEventId: String,
    val emailId: String,
    val title: String,
    val startAtLabel: String,
    val startAtMillis: Long,
    val endAtMillis: Long?,
    val location: String?
)

data class InboxSyncUiState(
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val pendingEvents: List<PendingEventUi> = emptyList(),
    val lastSyncStatus: String? = null,
    val lastSyncAtLabel: String? = null,
    val parserStatus: String? = null,
    val error: String? = null
)

class InboxSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppContainer.from(application).syncRepository
    private val calendarRepository = AppContainer.from(application).calendarRepository
    private val dateFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private val _uiState = MutableStateFlow(InboxSyncUiState())
    val uiState: StateFlow<InboxSyncUiState> = _uiState.asStateFlow()

    fun refreshPendingEvents() {
        viewModelScope.launch {
            val pending = repository.getPendingParsedEvents().toPendingUi()
            _uiState.value = _uiState.value.copy(
                pendingCount = pending.size,
                pendingEvents = pending,
                parserStatus = repository.getParserStatus()
            )
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, error = null)
            try {
                when (val result = repository.syncNewEmails()) {
                    is AppResult.Error -> {
                        Log.e(TAG, "Sync now failed: ${result.message}", result.throwable)
                        _uiState.value = _uiState.value.copy(
                            isSyncing = false,
                            lastSyncStatus = "Sync failed",
                            error = result.message
                        )
                    }
                    is AppResult.Success -> {
                        val pending = repository.getPendingParsedEvents().toPendingUi()
                        _uiState.value = _uiState.value.copy(
                            isSyncing = false,
                            pendingCount = pending.size,
                            pendingEvents = pending,
                            lastSyncStatus = "Signed in successfully. Sync completed.",
                            lastSyncAtLabel = dateFormat.format(Date()),
                            parserStatus = repository.getParserStatus()
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected exception during syncNow()", t)
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    lastSyncStatus = "Sync failed",
                    error = t.message ?: "Unexpected sync error"
                )
            }
        }
    }

    fun addPendingEventToCalendar(parsedEventId: String) {
        viewModelScope.launch {
            val pending = repository.getPendingParsedEvents()
            val target = pending.firstOrNull { it.id == parsedEventId } ?: return@launch
            val createResult = calendarRepository.createEvent(
                CalendarEvent(
                    id = "",
                    title = target.title,
                    startAtMillis = target.startAtMillis,
                    endAtMillis = target.endAtMillis,
                    location = target.location,
                    meetingLink = target.meetingLink,
                    attendees = target.attendees
                )
            )
            when (createResult) {
                is AppResult.Error -> {
                    Log.e(TAG, "Failed to add single event parsedId=${target.id}: ${createResult.message}", createResult.throwable)
                    _uiState.value = _uiState.value.copy(error = createResult.message, lastSyncStatus = "Add event failed")
                }
                is AppResult.Success -> {
                    repository.markParsedEventSynced(target.id, createResult.data)
                    repository.markEmailProcessed(target.emailId)
                    refreshPendingEvents()
                    _uiState.value = _uiState.value.copy(lastSyncStatus = "Event added to Google Calendar")
                }
            }
        }
    }

    fun addAllPendingEventsToCalendar() {
        viewModelScope.launch {
            val pending = repository.getPendingParsedEvents()
            var addedCount = 0
            pending.forEach { event ->
                when (val createResult = calendarRepository.createEvent(
                    CalendarEvent(
                        id = "",
                        title = event.title,
                        startAtMillis = event.startAtMillis,
                        endAtMillis = event.endAtMillis,
                        location = event.location,
                        meetingLink = event.meetingLink,
                        attendees = event.attendees
                    )
                )) {
                    is AppResult.Error -> Log.e(TAG, "Failed adding parsedId=${event.id}: ${createResult.message}", createResult.throwable)
                    is AppResult.Success -> {
                        repository.markParsedEventSynced(event.id, createResult.data)
                        repository.markEmailProcessed(event.emailId)
                        addedCount += 1
                    }
                }
            }
            refreshPendingEvents()
            _uiState.value = _uiState.value.copy(lastSyncStatus = "Added $addedCount event(s) to Google Calendar")
        }
    }

    fun setGmailAccessToken(token: String) {
        viewModelScope.launch {
            when (val result = repository.setGmailAccessToken(token)) {
                is AppResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(error = null)
                }
            }
        }
    }

    private companion object {
        const val TAG = "InboxSyncViewModel"
    }

    private fun List<ParsedEvent>.toPendingUi(): List<PendingEventUi> {
        return map {
            PendingEventUi(
                parsedEventId = it.id,
                emailId = it.emailId,
                title = it.title,
                startAtLabel = dateFormat.format(Date(it.startAtMillis)),
                startAtMillis = it.startAtMillis,
                endAtMillis = it.endAtMillis,
                location = it.location
            )
        }
    }
}

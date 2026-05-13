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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

data class SnackbarMessage(
    val text: String,
    val isError: Boolean = false
)

class InboxSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppContainer.from(application).syncRepository
    private val calendarRepository = AppContainer.from(application).calendarRepository
    private val dateFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private val _uiState = MutableStateFlow(InboxSyncUiState())
    val uiState: StateFlow<InboxSyncUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<SnackbarMessage>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val snackbarEvents: SharedFlow<SnackbarMessage> = _snackbarEvents.asSharedFlow()

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
                        _snackbarEvents.tryEmit(SnackbarMessage(result.message, isError = true))
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
                _snackbarEvents.tryEmit(
                    SnackbarMessage(t.message ?: "Unexpected sync error", isError = true)
                )
            }
        }
    }

    fun addPendingEventToCalendar(parsedEventId: String) {
        viewModelScope.launch {
            val currentList = _uiState.value.pendingEvents
            val targetUi = currentList.firstOrNull { it.parsedEventId == parsedEventId } ?: return@launch
            val pending = repository.getPendingParsedEvents()
            val target = pending.firstOrNull { it.id == parsedEventId } ?: run {
                _uiState.value = _uiState.value.copy(
                    pendingEvents = currentList.filterNot { it.parsedEventId == parsedEventId },
                    pendingCount = (currentList.size - 1).coerceAtLeast(0)
                )
                return@launch
            }

            val optimisticList = currentList.filterNot { it.parsedEventId == parsedEventId }
            _uiState.value = _uiState.value.copy(
                pendingEvents = optimisticList,
                pendingCount = optimisticList.size
            )

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
                    Log.e(
                        TAG,
                        "Failed to add single event parsedId=${target.id}: ${createResult.message}",
                        createResult.throwable
                    )
                    val rolledBack = (_uiState.value.pendingEvents + targetUi)
                        .sortedBy { it.startAtMillis }
                    _uiState.value = _uiState.value.copy(
                        pendingEvents = rolledBack,
                        pendingCount = rolledBack.size,
                        error = createResult.message,
                        lastSyncStatus = "Add event failed"
                    )
                    _snackbarEvents.tryEmit(
                        SnackbarMessage(
                            text = createResult.message ?: "Failed to add event",
                            isError = true
                        )
                    )
                }
                is AppResult.Success -> {
                    repository.markParsedEventSynced(target.id, createResult.data)
                    repository.markEmailProcessed(target.emailId)
                    _uiState.value = _uiState.value.copy(
                        lastSyncStatus = "Event added to your calendar"
                    )
                    _snackbarEvents.tryEmit(
                        SnackbarMessage("Event added to your calendar")
                    )
                    refreshPendingEvents()
                }
            }
        }
    }

    fun addAllPendingEventsToCalendar() {
        viewModelScope.launch {
            val snapshot = _uiState.value.pendingEvents
            if (snapshot.isEmpty()) return@launch
            _uiState.value = _uiState.value.copy(pendingEvents = emptyList(), pendingCount = 0)

            val pending = repository.getPendingParsedEvents()
            var addedCount = 0
            val failed = mutableListOf<PendingEventUi>()
            pending.forEach { event ->
                val uiRow = snapshot.firstOrNull { it.parsedEventId == event.id }
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
                    is AppResult.Error -> {
                        Log.e(
                            TAG,
                            "Failed adding parsedId=${event.id}: ${createResult.message}",
                            createResult.throwable
                        )
                        if (uiRow != null) failed += uiRow
                    }
                    is AppResult.Success -> {
                        repository.markParsedEventSynced(event.id, createResult.data)
                        repository.markEmailProcessed(event.emailId)
                        addedCount += 1
                    }
                }
            }

            if (failed.isNotEmpty()) {
                val restored = (_uiState.value.pendingEvents + failed).sortedBy { it.startAtMillis }
                _uiState.value = _uiState.value.copy(
                    pendingEvents = restored,
                    pendingCount = restored.size
                )
            }
            refreshPendingEvents()

            val statusText = when {
                addedCount > 0 && failed.isEmpty() ->
                    "Added $addedCount event(s) to your calendar"
                addedCount > 0 && failed.isNotEmpty() ->
                    "Added $addedCount, ${failed.size} failed"
                else -> "Failed to add ${failed.size} event(s)"
            }
            _uiState.value = _uiState.value.copy(lastSyncStatus = statusText)
            _snackbarEvents.tryEmit(
                SnackbarMessage(
                    text = statusText,
                    isError = addedCount == 0 && failed.isNotEmpty()
                )
            )
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

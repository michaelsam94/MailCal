package com.michael.mailcal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.michael.mailcal.ui.theme.MailCalTheme
import com.michael.mailcal.feature_auth.presentation.AuthViewModel
import com.michael.mailcal.feature_auth.presentation.AuthUiState
import com.michael.mailcal.feature_sync.presentation.InboxSyncUiState
import com.michael.mailcal.feature_sync.presentation.InboxSyncViewModel
import com.michael.mailcal.feature_sync.presentation.SnackbarMessage
import com.michael.mailcal.worker.EmailSyncWorkerRunner

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()
    private val syncViewModel: InboxSyncViewModel by viewModels()
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authViewModel.loadCurrentUser()
        syncViewModel.refreshPendingEvents()
        requestCalendarPermissionsIfNeeded()
        EmailSyncWorkerRunner().enqueuePeriodicSync(this)
        enableEdgeToEdge()
        setContent {
            MailCalTheme {
                val authUiState by authViewModel.uiState.collectAsState()
                val syncUiState by syncViewModel.uiState.collectAsState()
                val signInLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    result.data?.let { authViewModel.completeSignIn(it) }
                }
                LaunchedEffect(authUiState.signInIntent) {
                    authUiState.signInIntent?.let {
                        signInLauncher.launch(it)
                        authViewModel.consumeSignInIntent()
                    }
                }

                val snackbarHostState = remember { SnackbarHostState() }
                var lastSnackbarIsError by remember { mutableStateOf(false) }
                LaunchedEffect(syncViewModel) {
                    syncViewModel.snackbarEvents.collect { message ->
                        lastSnackbarIsError = message.isError
                        snackbarHostState.showSnackbar(
                            message = message.text,
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            val container = if (lastSnackbarIsError) {
                                Color(0xFFB00020)
                            } else {
                                Color(0xFF1B5E20)
                            }
                            Snackbar(
                                snackbarData = data,
                                containerColor = container,
                                contentColor = Color.White,
                                actionColor = Color.White
                            )
                        }
                    }
                ) { innerPadding ->
                    if (authUiState.currentUser == null) {
                        SignInScreen(
                            authState = authUiState,
                            onSignInClicked = { authViewModel.prepareSignIn() },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        MailCalHomeScreen(
                            authState = authUiState,
                            syncState = syncUiState,
                            onSyncClicked = { syncViewModel.syncNow() },
                            onAddAllClicked = { syncViewModel.addAllPendingEventsToCalendar() },
                            onAddOneClicked = { parsedId -> syncViewModel.addPendingEventToCalendar(parsedId) },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun requestCalendarPermissionsIfNeeded() {
        val readGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!readGranted || !writeGranted) {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }
}

@Composable
fun MailCalHomeScreen(
    authState: AuthUiState,
    syncState: InboxSyncUiState,
    onSyncClicked: () -> Unit,
    onAddAllClicked: () -> Unit,
    onAddOneClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MailCal", style = MaterialTheme.typography.headlineSmall)
        Text("Offline-first email to calendar", style = MaterialTheme.typography.bodyMedium)

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("User: ${authState.currentUser?.email ?: "Not signed in"}")
                if (authState.error != null) Text("Auth error: ${authState.error}")
                if (syncState.error != null) Text("Sync error: ${syncState.error}")
                Text("Pending parsed events: ${syncState.pendingCount}")
                if (syncState.lastSyncStatus != null) Text(syncState.lastSyncStatus)
                if (syncState.lastSyncAtLabel != null) Text("Last sync: ${syncState.lastSyncAtLabel}")
                ParserStatusChip(syncState.parserStatus)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSyncClicked,
                enabled = !syncState.isSyncing
            ) {
                Text(if (syncState.isSyncing) "Syncing..." else "Sync now")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pending events", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onAddAllClicked,
                enabled = syncState.pendingEvents.isNotEmpty()
            ) {
                Text("Add all")
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(syncState.pendingEvents) { event ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    val locationText = event.location?.let { " - $it" } ?: ""
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${event.title}\n${event.startAtLabel}$locationText",
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { onAddOneClicked(event.parsedEventId) },
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInScreen(
    authState: AuthUiState,
    onSignInClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "MailCal app icon",
            modifier = Modifier.size(88.dp)
        )
        Text("Welcome to MailCal", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sign in with Google to let the app read Gmail emails and detect meeting events for your calendar.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )
        Button(
            onClick = onSignInClicked,
            enabled = !authState.isLoading
        ) {
            if (authState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Sign in with Gmail")
            }
        }
        if (authState.error != null) {
            Text(
                text = authState.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun ParserStatusChip(parserStatus: String?) {
    if (parserStatus.isNullOrBlank()) return
    val lower = parserStatus.lowercase()
    val isGood = "ml kit" in lower || "ics" in lower
    val isWarn = "fallback" in lower
    val bgColor = when {
        isGood -> Color(0xFF1B5E20)
        isWarn -> Color(0xFFF57F17)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        isGood || isWarn -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        isGood -> Icons.Default.CheckCircle
        isWarn -> Icons.Default.Warning
        else -> Icons.Default.Info
    }
    Surface(
        color = bgColor,
        shape = MaterialTheme.shapes.small
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = "parser status",
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "  $parserStatus",
                color = textColor,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MailCalHomeScreenPreview() {
    MailCalTheme {
        MailCalHomeScreen(
            authState = AuthUiState(currentUser = null),
            syncState = InboxSyncUiState(),
            onSyncClicked = {},
            onAddAllClicked = {},
            onAddOneClicked = {}
        )
    }
}
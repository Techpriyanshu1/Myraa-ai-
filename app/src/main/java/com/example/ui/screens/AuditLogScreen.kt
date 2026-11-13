package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AppDatabase
import com.example.data.db.AutomationLogEntity
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.AuroraPink
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class StatusFilter(val label: String) {
    ALL("All Activities"),
    SUCCESS("Successful"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

private enum class DateFilter(val label: String) {
    ALL("All Dates"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    OLDER("Older")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val logs by db.automationLogDao().getAllLogs().collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf(StatusFilter.ALL) }
    var selectedDate by remember { mutableStateOf(DateFilter.ALL) }
    var selectedDetailLog by remember { mutableStateOf<AutomationLogEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val startOfTodayMs = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val sevenDaysAgoMs = remember(startOfTodayMs) { startOfTodayMs - 6L * 24L * 60L * 60L * 1000L }

    val filteredLogs = remember(logs, searchQuery, selectedStatus, selectedDate) {
        logs.filter { log ->
            val matchesStatus = when (selectedStatus) {
                StatusFilter.ALL -> true
                StatusFilter.SUCCESS -> log.status == "SUCCESS"
                StatusFilter.FAILED -> log.status == "FAILED"
                StatusFilter.CANCELLED -> log.status == "CANCELLED" || log.status == "BLOCKED"
            }

            val matchesDate = when (selectedDate) {
                DateFilter.ALL -> true
                DateFilter.TODAY -> log.timestamp >= startOfTodayMs
                DateFilter.LAST_7_DAYS -> log.timestamp >= sevenDaysAgoMs
                DateFilter.OLDER -> log.timestamp < sevenDaysAgoMs
            }

            val q = searchQuery.trim()
            val matchesSearch = q.isBlank() ||
                log.voicePrompt.contains(q, ignoreCase = true) ||
                log.recognizedIntent.contains(q, ignoreCase = true) ||
                log.actionType.contains(q, ignoreCase = true) ||
                log.target.contains(q, ignoreCase = true) ||
                log.agentInvolved.contains(q, ignoreCase = true) ||
                log.errorInfo.contains(q, ignoreCase = true)

            matchesStatus && matchesDate && matchesSearch
        }
    }

    fun exportLocalHistory() {
        coroutineScope.launch {
            val exportList = filteredLogs.ifEmpty { logs }
            val jsonArray = JSONArray()
            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            exportList.forEach { entry ->
                jsonArray.put(JSONObject().apply {
                    put("id", entry.id)
                    put("dateTime", dateFmt.format(Date(entry.timestamp)))
                    put("commandText", entry.voicePrompt)
                    put("recognizedIntent", entry.recognizedIntent)
                    put("requestedOperation", entry.actionType)
                    put("target", entry.target)
                    put("agentInvolved", entry.agentInvolved)
                    put("permissionStatus", entry.permissionStatus)
                    put("confirmationStatus", entry.confirmationStatus)
                    put("status", entry.status)
                    put("executionResult", entry.executionDetails)
                    put("errorInfo", entry.errorInfo)
                    put("executionDurationMs", entry.executionDurationMs)
                })
            }
            val payload = JSONObject().apply {
                put("app", "MYRAA AI")
                put("exportedAt", dateFmt.format(Date()))
                put("totalEntries", exportList.size)
                put("entries", jsonArray)
            }.toString(2)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MYRAA AI Local Activity History")
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export MYRAA Activity History"))
        }
    }

    selectedDetailLog?.let { detail ->
        ActivityEntryDetailDialog(
            log = detail,
            onDelete = {
                coroutineScope.launch {
                    db.automationLogDao().deleteLogById(detail.id)
                    selectedDetailLog = null
                }
            },
            onDismiss = { selectedDetailLog = null }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Activity History?") },
            text = { Text("This permanently deletes all local Room DB activity records on this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            db.automationLogDao().clearAllLogs()
                            showClearConfirm = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.testTag("confirm_clear_logs_button")
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Local Activity History",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${filteredLogs.size} of ${logs.size} local records",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("audit_log_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(
                            onClick = { exportLocalHistory() },
                            modifier = Modifier.testTag("export_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Export History",
                                tint = CyanPrimary
                            )
                        }
                        IconButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.testTag("clear_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear History",
                                tint = DangerRed
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search command, intent, agent, or error...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanPrimary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DarkSurfaceElevated,
                    unfocusedContainerColor = DarkSurfaceElevated
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag("search_logs_input")
            )

            // Status filter chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(StatusFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedStatus == filter,
                        onClick = { selectedStatus = filter },
                        label = { Text(filter.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = CyanPrimary,
                            containerColor = DarkSurfaceElevated,
                            labelColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("status_filter_${filter.name}")
                    )
                }
            }

            // Date filter chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(DateFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedDate == filter,
                        onClick = { selectedDate = filter },
                        label = { Text(filter.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VioletSecondary.copy(alpha = 0.25f),
                            selectedLabelColor = VioletSecondary,
                            containerColor = DarkSurfaceElevated,
                            labelColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("date_filter_${filter.name}")
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (logs.isEmpty()) "No local activity logs yet" else "No entries match your filters",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "All activity logs stay strictly on your device in local Room storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        AuditLogItemCard(
                            log = log,
                            onClickDetails = { selectedDetailLog = log },
                            onDeleteEntry = {
                                coroutineScope.launch {
                                    db.automationLogDao().deleteLogById(log.id)
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditLogItemCard(
    log: AutomationLogEntity,
    onClickDetails: () -> Unit,
    onDeleteEntry: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("hh:mm:ss a • MMM dd", Locale.getDefault()) }
    val formattedTime = remember(log.timestamp) { dateFormat.format(Date(log.timestamp)) }

    val statusColor = when (log.status) {
        "SUCCESS" -> AuroraEmerald
        "CANCELLED", "BLOCKED" -> AuroraPink
        else -> DangerRed
    }
    val statusIcon = when (log.status) {
        "SUCCESS" -> Icons.Default.CheckCircle
        "CANCELLED", "BLOCKED" -> Icons.Default.Cancel
        else -> Icons.Default.Error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp))
            .clickable { onClickDetails() }
            .testTag("log_card_${log.id}"),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CyanPrimary.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = log.actionType,
                            color = CyanPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = formattedTime, fontSize = 11.sp, color = TextSecondary)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = log.status,
                        tint = statusColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = log.status,
                        fontSize = 10.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDeleteEntry,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("delete_log_${log.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Entry",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "\"${log.voicePrompt}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Agent: ${log.agentInvolved} • Intent: ${log.recognizedIntent} • ${log.executionDurationMs}ms",
                fontSize = 11.sp,
                color = TextSecondary
            )

            if (log.errorInfo.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${log.errorInfo}",
                    fontSize = 11.sp,
                    color = DangerRed
                )
            }
        }
    }
}

@Composable
private fun ActivityEntryDetailDialog(
    log: AutomationLogEntity,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "Activity Entry Details",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DetailRow("Date & Time", dateFormat.format(Date(log.timestamp)))
                DetailRow("User Command", log.voicePrompt)
                DetailRow("Recognized Intent", log.recognizedIntent)
                DetailRow("Requested Operation", "${log.actionType} (${log.target})")
                DetailRow("Agent Involved", log.agentInvolved)
                DetailRow("Permission Status", log.permissionStatus)
                DetailRow("Confirmation Status", log.confirmationStatus)
                DetailRow("Execution Status", log.status)
                DetailRow("Execution Result", log.executionDetails)
                if (log.errorInfo.isNotBlank()) {
                    DetailRow("Error Info", log.errorInfo)
                }
                DetailRow("Duration", "${log.executionDurationMs} ms")
                if (log.aiResponse.isNotBlank()) {
                    DetailRow("Mahi Response", log.aiResponse)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDelete,
                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f))
            ) {
                Text("Delete Entry", color = DangerRed)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = CyanPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(text = value.ifBlank { "—" }, color = TextPrimary, fontSize = 12.sp)
    }
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.events

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.ui.ErrorText
import com.myhobbyislearning.fibersocial.ui.SendingSpinner
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Full-screen composer for creating a new event in [group] — moderator-only (issue: no
 * tracker entry, requested directly). Fields for online vs in-person events differ (see
 * [NewEventInput]); [state] carries the dropdown option lists scraped live from
 * Ravelry's own form, so this screen shows a loading spinner until [NewEventState.Ready]
 * arrives.
 */
@Composable
fun NewEventScreen(
    group: Group,
    state: NewEventState,
    statesForCountry: List<EventState>,
    onBack: () -> Unit,
    onCountrySelected: (Long) -> Unit,
    onCreate: (NewEventInput) -> Unit,
    onCreated: (String) -> Unit,
) {
    val sending = (state as? NewEventState.Ready)?.sending == true

    var name by rememberSaveable { mutableStateOf("") }
    var online by rememberSaveable { mutableStateOf(false) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var startDate by rememberSaveable { mutableStateOf("") }
    var startTime by rememberSaveable { mutableStateOf("") }
    var endDate by rememberSaveable { mutableStateOf("") }
    var endTime by rememberSaveable { mutableStateOf("") }
    var timezone by rememberSaveable { mutableStateOf<String?>(null) }
    var countryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var stateId by rememberSaveable { mutableStateOf<Long?>(null) }
    var city by rememberSaveable { mutableStateOf("") }
    var venueName by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var estimatedAttendance by rememberSaveable { mutableStateOf<Long?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var editorList by rememberSaveable { mutableStateOf("") }

    // Anything worth confirming before discarding — a bare setting-type toggle with
    // nothing else filled in isn't "progress" worth a confirmation prompt.
    val hasProgress = name.isNotBlank() || categoryId != null || startDate.isNotBlank() ||
        startTime.isNotBlank() || endDate.isNotBlank() || endTime.isNotBlank() ||
        timezone != null || countryId != null || city.isNotBlank() ||
        venueName.isNotBlank() || address.isNotBlank() ||
        estimatedAttendance != null || url.isNotBlank() || description.isNotBlank() ||
        editorList.isNotBlank()
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    val attemptBack = { if (hasProgress) showDiscardConfirm = true else onBack() }

    BackHandler(enabled = !sending, onBack = attemptBack)

    val created = state as? NewEventState.Created
    LaunchedEffect(created) {
        if (created != null && created.venueWarning == null) onCreated(created.permalink)
    }

    // A rejected venue step still created the event (see NewEventState.Created.venueWarning);
    // tell the user why it won't be listed before navigating into it.
    val venueWarning = created?.venueWarning
    if (created != null && venueWarning != null) {
        AlertDialog(
            onDismissRequest = { onCreated(created.permalink) },
            title = { Text("Event created") },
            text = { Text(venueWarning) },
            confirmButton = {
                TextButton(onClick = { onCreated(created.permalink) }) { Text("OK") }
            },
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard this event?") },
            text = { Text("You'll lose everything you've entered.") },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onBack() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Keep editing") }
            },
        )
    }

    val canSubmit = name.isNotBlank() && startDate.isNotBlank() && startTime.isNotBlank() &&
        categoryId != null &&
        (
            online || (
                countryId != null && city.isNotBlank() &&
                    venueName.isNotBlank() && address.isNotBlank() &&
                    // Ravelry's venue step rejects countries that have a state list
                    // with "State can't be blank" unless one is chosen.
                    (statesForCountry.isEmpty() || stateId != null)
                )
            )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New event") },
                navigationIcon = {
                    IconButton(onClick = attemptBack, enabled = !sending) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sending) {
                        SendingSpinner()
                    } else if (state is NewEventState.Ready) {
                        TextButton(
                            enabled = canSubmit,
                            onClick = {
                                onCreate(
                                    NewEventInput(
                                        groupId = group.id,
                                        name = name.trim(),
                                        online = online,
                                        categoryId = categoryId,
                                        startDate = startDate,
                                        startTime = startTime,
                                        endDate = endDate.ifBlank { null },
                                        endTime = endTime.ifBlank { null },
                                        description = description.ifBlank { null },
                                        url = url.ifBlank { null },
                                        editorList = editorList.ifBlank { null },
                                        startTimezone = if (online) timezone else null,
                                        endTimezone = if (online) timezone else null,
                                        countryId = if (online) null else countryId,
                                        stateId = if (online) null else stateId,
                                        city = if (online) null else city.ifBlank { null },
                                        venueName = if (online) null else venueName.ifBlank { null },
                                        address = if (online) null else address.ifBlank { null },
                                        estimatedAttendance = if (online) null else estimatedAttendance,
                                    ),
                                )
                            },
                        ) { Text("Create") }
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is NewEventState.Loading -> Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.padding(32.dp))
            }

            is NewEventState.LoadError -> Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                ErrorText(text = state.message)
            }

            is NewEventState.Created -> Unit // Handled by the LaunchedEffect above.

            is NewEventState.Ready -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val submitError = state.error
                if (submitError != null) {
                    ErrorText(text = submitError, modifier = Modifier.padding(bottom = 8.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Event name") },
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                Row(modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(
                        selected = !online,
                        onClick = { online = false },
                        enabled = !sending,
                        label = { Text("In person") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = online,
                        onClick = { online = true },
                        enabled = !sending,
                        label = { Text("Online") },
                    )
                }

                DropdownField(
                    label = "Category",
                    selectedLabel = categoryId?.let { id ->
                        (if (online) state.form.onlineCategories else state.form.inPersonCategories)
                            .find { it.id == id }?.label
                    }.orEmpty(),
                    options = if (online) state.form.onlineCategories else state.form.inPersonCategories,
                    optionLabel = { it.label },
                    enabled = !sending,
                    modifier = Modifier.padding(top = 8.dp),
                    onSelect = { categoryId = it.id },
                )

                Row(modifier = Modifier.padding(top = 8.dp)) {
                    DateField(
                        label = "Start date",
                        value = startDate,
                        enabled = !sending,
                        modifier = Modifier.weight(1f),
                        onSelected = { startDate = it.toString() },
                    )
                    Spacer(Modifier.width(8.dp))
                    TimeField(
                        label = "Start time",
                        value = startTime,
                        enabled = !sending,
                        modifier = Modifier.weight(1f),
                        onSelected = { h, m -> startTime = formatEventTime(h, m) },
                    )
                }

                Row(modifier = Modifier.padding(top = 8.dp)) {
                    DateField(
                        label = "End date (optional)",
                        value = endDate,
                        enabled = !sending,
                        modifier = Modifier.weight(1f),
                        onSelected = { endDate = it.toString() },
                    )
                    Spacer(Modifier.width(8.dp))
                    TimeField(
                        label = "End time (optional)",
                        value = endTime,
                        enabled = !sending,
                        modifier = Modifier.weight(1f),
                        onSelected = { h, m -> endTime = formatEventTime(h, m) },
                    )
                }

                if (online) {
                    DropdownField(
                        label = "Time zone",
                        selectedLabel = timezone.orEmpty(),
                        options = state.form.timezones,
                        optionLabel = { it },
                        enabled = !sending,
                        modifier = Modifier.padding(top = 8.dp),
                        onSelect = { timezone = it },
                    )
                } else {
                    // Required, not just descriptive: Ravelry won't list an in-person
                    // event in the group's "upcoming events" box without a venue name
                    // (confirmed on-device — see RavelryApiClient.setEventVenue).
                    OutlinedTextField(
                        value = venueName,
                        onValueChange = { venueName = it },
                        label = { Text("Venue name") },
                        singleLine = true,
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    DropdownField(
                        label = "Country",
                        selectedLabel = countryId?.let { id -> state.form.countries.find { it.id == id }?.label }.orEmpty(),
                        options = state.form.countries,
                        optionLabel = { it.label },
                        enabled = !sending,
                        modifier = Modifier.padding(top = 8.dp),
                        onSelect = { countryId = it.id; stateId = null; onCountrySelected(it.id) },
                    )
                    if (statesForCountry.isNotEmpty()) {
                        DropdownField(
                            label = "State/Region",
                            selectedLabel = stateId?.let { id -> statesForCountry.find { it.id == id }?.name }.orEmpty(),
                            options = statesForCountry,
                            optionLabel = { it.name },
                            enabled = !sending,
                            modifier = Modifier.padding(top = 8.dp),
                            onSelect = { stateId = it.id },
                        )
                    }
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = { Text("City") },
                        singleLine = true,
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Street address") },
                        singleLine = true,
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    DropdownField(
                        label = "# of attendees (estimate)",
                        selectedLabel = estimatedAttendance?.let { id ->
                            state.form.estimatedAttendanceOptions.find { it.id == id }?.label
                        }.orEmpty(),
                        options = state.form.estimatedAttendanceOptions,
                        optionLabel = { it.label },
                        enabled = !sending,
                        modifier = Modifier.padding(top = 8.dp),
                        onSelect = { estimatedAttendance = it.id },
                    )
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Event URL (optional)") },
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                OutlinedTextField(
                    value = editorList,
                    onValueChange = { editorList = it },
                    label = { Text("Other editors (optional)") },
                    placeholder = { Text("username1 username2 ...") },
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun <T> DropdownField(
    label: String,
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

/** A read-only field showing [value] that opens a date picker via its trailing icon. */
@Composable
private fun DateField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (LocalDate) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        enabled = enabled,
        trailingIcon = {
            IconButton(onClick = { showPicker = true }, enabled = enabled) {
                Icon(Icons.Default.DateRange, contentDescription = "Pick a date")
            }
        },
        modifier = modifier,
    )
    if (showPicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                        onSelected(date)
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** A read-only field showing [value] that opens a time picker via its trailing icon. */
@Composable
private fun TimeField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (hour: Int, minute: Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        enabled = enabled,
        trailingIcon = {
            IconButton(onClick = { showPicker = true }, enabled = enabled) {
                Icon(Icons.Default.DateRange, contentDescription = "Pick a time")
            }
        },
        modifier = modifier,
    )
    if (showPicker) {
        val pickerState = rememberTimePickerState(is24Hour = false)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onSelected(pickerState.hour, pickerState.minute)
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = pickerState) },
        )
    }
}

/** Formats a 24-hour [hour]/[minute] pair into Ravelry's expected `"hh:mm AM/PM"` form. */
private fun formatEventTime(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "${hour12.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')} $amPm"
}

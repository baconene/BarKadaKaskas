@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.barkadakaskas.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read-only text field that opens a picker when tapped — no more typing YYYY-MM-DD. */
@Composable private fun PickerField(label: String, display: String, icon: ImageVector, modifier: Modifier, open: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(display, {}, label = { Text(label) }, readOnly = true, singleLine = true, trailingIcon = { Icon(icon, null) }, modifier = Modifier.fillMaxWidth())
        // Covers the field so a tap anywhere opens the picker (read-only fields swallow clicks).
        Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Choose $label", onClick = open))
    }
}

/** [value] and the callback use ISO dates (yyyy-MM-dd). */
@Composable fun DateField(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), min: String? = null) {
    var open by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
    PickerField(label, date?.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH)) ?: "Choose a date", Icons.Default.CalendarMonth, modifier) { open = true }
    if (open) {
        val minMillis = min?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = minMillis == null || utcTimeMillis >= minMillis },
        )
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { change(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }, enabled = state.selectedDateMillis != null) { Text("Done") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }) { DatePicker(state) }
    }
}

/** [value] and the callback use 24-hour HH:mm. */
@Composable fun TimeField(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    var open by remember { mutableStateOf(false) }
    val time = runCatching { LocalTime.parse(value) }.getOrNull()
    PickerField(label, time?.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) ?: "Choose a time", Icons.Default.Schedule, modifier) { open = true }
    if (open) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 9, initialMinute = time?.minute ?: 0, is24Hour = false)
        AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { change(LocalTime.of(state.hour, state.minute).toString()); open = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
    }
}

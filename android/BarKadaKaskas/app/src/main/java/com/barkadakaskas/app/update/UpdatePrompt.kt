package com.barkadakaskas.app.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private val Forest = Color(0xFF214E3E)

/**
 * Checks for a newer app when the app opens (and when [forceShow] is set by the update notification) and offers
 * "Update now": downloads the APK with progress, then opens Android's installer. If installing from this app isn't
 * allowed yet, it explains and opens the setting, then continues when the user comes back.
 */
@Composable fun UpdatePrompt(forceShow: Boolean, onShown: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var visible by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(forceShow) {
        val found = AppUpdates.check(context) ?: return@LaunchedEffect
        release = found
        if (forceShow || AppUpdates.shouldPrompt(context, found)) visible = true
        if (forceShow) onShown()
    }
    fun installNow(file: File) {
        if (AppUpdates.canInstall(context)) { needsPermission = false; AppUpdates.install(context, file) } else needsPermission = true
    }
    // Back from the "install unknown apps" setting: carry on.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && needsPermission && AppUpdates.canInstall(context)) apk?.let { installNow(it) } }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val r = release
    if (!visible || r == null) return
    val downloading = progress != null && apk == null
    AlertDialog(
        onDismissRequest = { if (!r.required && !downloading) { AppUpdates.snooze(context, r); visible = false } },
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = Forest) },
        title = { Text(if (r.required) "Update required" else "New version available", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("BarkadaKasKas ${r.versionName}" + (if (r.size > 0) " · ${"%.1f".format(r.size / 1_048_576.0)} MB" else ""), fontWeight = FontWeight.SemiBold)
                if (r.required) Text("This version of the app is no longer supported. Please update to keep using it.")
                if (r.notes.isNotBlank()) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    Text("What's new", style = MaterialTheme.typography.labelLarge, color = Forest)
                    Text(r.notes, style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    needsPermission -> Text("Allow BarkadaKasKas to install updates: turn on \"Allow from this source\", then come back.", color = MaterialTheme.colorScheme.error)
                    downloading -> {
                        val p = progress ?: 0f
                        if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest) else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Forest)
                        Text(if (p < 0f) "Downloading…" else "Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    apk != null -> Text("Downloaded. Tap Install and confirm on the next screen; your trips and login stay.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !downloading, colors = ButtonDefaults.buttonColors(containerColor = Forest), onClick = {
                error = null
                val file = apk
                when {
                    needsPermission -> AppUpdates.openInstallSettings(context)
                    file != null -> installNow(file)
                    else -> scope.launch {
                        progress = 0f
                        try { val downloaded = AppUpdates.download(context, r) { progress = it }; apk = downloaded; installNow(downloaded) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { progress = null; error = failure.message ?: "The download failed. Check your connection and try again." }
                    }
                }
            }) { Text(when { needsPermission -> "Open settings"; apk != null -> "Install"; downloading -> "Downloading…"; else -> "Update now" }) }
        },
        dismissButton = if (r.required) null else ({ TextButton(enabled = !downloading, onClick = { AppUpdates.snooze(context, r); visible = false }) { Text("Later") } }),
    )
}

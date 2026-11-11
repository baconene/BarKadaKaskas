package com.barkadakaskas.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.barkadakaskas.app.data.LaravelRepository
import com.barkadakaskas.app.data.TokenStore
import com.barkadakaskas.app.ui.TravelApp
import com.barkadakaskas.app.ui.TravelViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recordCrashes()
        // Laravel API with the token kept encrypted in the Android Keystore.
        val repository = LaravelRepository(TokenStore(applicationContext))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TravelViewModel(repository) as T
        }
        // Background checks for trip alerts (phone notifications); tapping one opens its trip on the right tab.
        com.barkadakaskas.app.alerts.AlertNotifications.schedule(applicationContext)
        setContent {
            val vm: TravelViewModel = viewModel(factory = factory)
            androidx.compose.runtime.LaunchedEffect(Unit) {
                vm.onAlertsSeen = { id -> com.barkadakaskas.app.alerts.AlertNotifications.markSeen(applicationContext, id) }
                openFromNotification(intent, vm)
            }
            androidx.compose.runtime.DisposableEffect(Unit) {
                val listener = androidx.core.util.Consumer<android.content.Intent> { openFromNotification(it, vm) }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }
            // New app versions: a prompt on opening (and from the update notification) with download and install.
            var showUpdate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(intent?.getBooleanExtra(com.barkadakaskas.app.update.AppUpdates.EXTRA_SHOW_UPDATE, false) == true) }
            androidx.compose.runtime.DisposableEffect(Unit) {
                val listener = androidx.core.util.Consumer<android.content.Intent> { if (it.getBooleanExtra(com.barkadakaskas.app.update.AppUpdates.EXTRA_SHOW_UPDATE, false)) showUpdate = true }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }
            TravelApp(vm)
            com.barkadakaskas.app.update.UpdatePrompt(forceShow = showUpdate) { showUpdate = false; intent?.removeExtra(com.barkadakaskas.app.update.AppUpdates.EXTRA_SHOW_UPDATE) }
        }
    }

    /**
     * Saves the details of a crash and shows them the next time the app opens, with a button to copy them,
     * so a problem can be reported without developer tools.
     */
    private fun recordCrashes() {
        val file = File(filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText("BarkadaKasKas ${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL})\n\n${error.stackTraceToString()}") }
            previous?.uncaughtException(thread, error)
        }
        if (!file.exists()) return
        val details = runCatching { file.readText() }.getOrDefault("").take(6000)
        file.delete()
        if (details.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("The app closed unexpectedly last time")
            .setMessage("Copy the details and send them to the developer.\n\n${details.take(1500)}")
            .setPositiveButton("Copy details") { _, _ -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BarkadaKasKas crash", details)) }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun openFromNotification(intent: android.content.Intent?, vm: TravelViewModel) {
        val tripId = intent?.getLongExtra(com.barkadakaskas.app.alerts.AlertNotifications.EXTRA_TRIP, -1L) ?: -1L
        if (tripId <= 0) return
        val tab = intent?.getIntExtra(com.barkadakaskas.app.alerts.AlertNotifications.EXTRA_TAB, 0) ?: 0
        intent?.removeExtra(com.barkadakaskas.app.alerts.AlertNotifications.EXTRA_TRIP)
        vm.openAlertTrip(tripId, tab)
    }
}

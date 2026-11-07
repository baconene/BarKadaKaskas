package com.barkadakaskas.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barkadakaskas.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class TravelViewModel(val repository: TravelRepository) : ViewModel() {
    var user by mutableStateOf<User?>(null); private set
    var trips by mutableStateOf<List<Trip>>(emptyList()); private set
    var detail by mutableStateOf<TripDetail?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var invitationUrl by mutableStateOf<String?>(null); private set
    var serverUrl by mutableStateOf(repository.serverUrl); private set
    /** True while a bottom-sheet form is open; it shows errors inline, so screens skip their snackbar. */
    var formOpen by mutableStateOf(false)

    init { if (repository.hasSession) launch { user = repository.currentUser(); trips = repository.trips() } }
    fun clearError() { error = null }
    fun consumeInvitation() { invitationUrl = null }
    fun launch(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.message ?: "Something went wrong. Please try again."
                if (!repository.hasSession) { user = null; detail = null; trips = emptyList() }
            } finally { busy = false }
        }
    }
    fun authenticate(server: String, name: String, email: String, password: String, register: Boolean) = launch {
        repository.configureServer(server.trim()); serverUrl = repository.serverUrl
        user = if (register) repository.register(name.trim(), email.trim(), password) else repository.login(email.trim(), password)
        trips = repository.trips()
    }
    fun refresh() = launch { detail?.let { detail = repository.trip(it.trip.id) } ?: run { trips = repository.trips() } }
    fun openTrip(trip: Trip) = launch { detail = repository.trip(trip.id) }
    fun backToTrips() { if (!busy) detail = null }
    fun signOut() = launch { repository.logout(); user = null; detail = null; trips = emptyList() }
    fun createTrip(draft: TripDraft, done: () -> Unit) = launch {
        val trip = repository.createTrip(draft); trips = repository.trips(); detail = repository.trip(trip.id); done()
    }
    fun mutate(done: () -> Unit = {}, action: suspend (TravelRepository, TripDetail) -> Unit) = launch {
        val current = detail ?: return@launch
        action(repository, current)
        detail = repository.trip(current.trip.id)
        done()
    }
    /** Hiding leaves the trip screen, since the trip no longer appears in the list. */
    fun cancelTrip(reason: String, hide: Boolean, done: () -> Unit) = launch {
        val current = detail ?: return@launch
        repository.cancelTrip(current.trip.id, reason, hide)
        trips = repository.trips()
        detail = if (hide) null else repository.trip(current.trip.id)
        done()
    }
    fun reopenTrip() = launch { detail?.let { repository.reopenTrip(it.trip.id); trips = repository.trips(); detail = repository.trip(it.trip.id) } }
    fun setTripHidden(hidden: Boolean) = launch {
        val current = detail ?: return@launch
        repository.setTripHidden(current.trip.id, hidden)
        trips = repository.trips()
        detail = if (hidden) null else repository.trip(current.trip.id)
    }
    fun invite() = launch { detail?.let { invitationUrl = repository.createInvitation(it.trip.id) } }
}

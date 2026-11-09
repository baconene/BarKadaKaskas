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
    fun backToTrips() { if (!busy) { detail = null; posts = emptyList(); postsLoadedFor = null; postsNextBefore = null } }
    fun signOut() = launch { repository.logout(); user = null; detail = null; trips = emptyList() }
    /** [aiPrompt], when given, opens the AI planner on the new trip and starts suggesting. */
    fun createTrip(draft: TripDraft, aiPrompt: String = "", done: () -> Unit) = launch {
        val trip = repository.createTrip(draft); trips = repository.trips(); detail = repository.trip(trip.id)
        pendingAiPrompt = aiPrompt.trim().ifBlank { null }
        done()
    }

    // AI trip planner (organizer). Suggestions are only a draft until addSuggestions saves the approved ones.
    var pendingAiPrompt by mutableStateOf<String?>(null)
    var suggestions by mutableStateOf<List<StopSuggestion>>(emptyList()); private set
    var planning by mutableStateOf(false); private set
    fun suggest(prompt: String) {
        val tripId = detail?.trip?.id ?: return
        if (planning || prompt.isBlank()) return
        planning = true; error = null
        viewModelScope.launch {
            try { suggestions = repository.suggestStops(tripId, prompt) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "The AI planner could not answer. Try again." }
            finally { planning = false }
        }
    }
    fun clearSuggestions() { suggestions = emptyList() }
    fun addSuggestions(approved: List<StopSuggestion>, done: () -> Unit) = mutate({ suggestions = emptyList(); done() }) { repo, current -> repo.addStops(current.trip.id, approved) }
    fun mutate(done: () -> Unit = {}, action: suspend (TravelRepository, TripDetail) -> Unit) = launch {
        val current = detail ?: return@launch
        action(repository, current)
        detail = repository.trip(current.trip.id)
        done()
    }
    /** "Done" shows at once and is saved for everyone on the trip; it rolls back if the save fails. */
    fun toggleStopDone(stop: Stop) {
        val tripId = detail?.trip?.id ?: return
        val done = !stop.done
        fun replace(value: String?) { detail = detail?.let { d -> d.copy(stops = d.stops.map { if (it.id == stop.id) it.copy(completedAt = value) else it }) } }
        replace(if (done) java.time.Instant.now().toString() else null)
        viewModelScope.launch {
            try { repository.setStopDone(tripId, stop.id, done) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { replace(stop.completedAt); error = failure.message ?: "Could not save. Please try again." }
        }
    }
    // Trip photo feed, loaded when the Photos tab opens.
    var posts by mutableStateOf<List<Post>>(emptyList()); private set
    var postsNextBefore by mutableStateOf<Long?>(null); private set
    var postsLoadedFor by mutableStateOf<Long?>(null); private set
    var postsLoading by mutableStateOf(false); private set

    fun loadPosts(more: Boolean = false) {
        val id = detail?.trip?.id ?: return
        if (postsLoading) return
        postsLoading = true
        viewModelScope.launch {
            try {
                val page = repository.posts(id, if (more) postsNextBefore else null)
                posts = if (more) posts + page.posts else page.posts
                postsNextBefore = page.nextBefore; postsLoadedFor = id
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not load photos." }
            finally { postsLoading = false }
        }
    }
    fun createPost(caption: String, jpegs: List<ByteArray>, done: () -> Unit) = launch {
        val id = detail?.trip?.id ?: return@launch
        posts = listOf(repository.createPost(id, caption, jpegs)) + posts
        done()
    }
    fun deletePost(post: Post) = launch {
        val id = detail?.trip?.id ?: return@launch
        repository.deletePost(id, post.id)
        posts = posts.filterNot { it.id == post.id }
    }
    /** Hearts update instantly and reconcile with the server, without blocking the screen. */
    fun toggleLike(post: Post) {
        val id = detail?.trip?.id ?: return
        fun replace(update: (Post) -> Post) { posts = posts.map { if (it.id == post.id) update(it) else it } }
        replace { it.copy(liked = !it.liked, likesCount = it.likesCount + if (it.liked) -1 else 1) }
        viewModelScope.launch {
            try { val (liked, count) = repository.toggleLike(id, post.id); replace { it.copy(liked = liked, likesCount = count) } }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { replace { it.copy(liked = post.liked, likesCount = post.likesCount) } }
        }
    }

    fun updateProfile(draft: ProfileDraft, done: () -> Unit) = editTrip(done) { repository.updateProfile(draft) }
    fun uploadAvatar(jpeg: ByteArray) = editTrip { repository.uploadAvatar(jpeg) }
    fun removeAvatar() = editTrip { repository.removeAvatar() }

    /** Reads a receipt photo; [done] receives the draft to fill the expense form with. */
    fun scanReceipt(jpeg: ByteArray, done: (ReceiptDraft) -> Unit) = launch {
        val id = detail?.trip?.id ?: return@launch
        done(repository.scanReceipt(id, jpeg))
    }

    /** Edits that change the trip card (name, dates, cover) also reload the trips list. */
    private fun editTrip(done: () -> Unit = {}, action: suspend (Long) -> Unit) = launch {
        val id = detail?.trip?.id ?: return@launch
        action(id)
        detail = repository.trip(id)
        trips = repository.trips()
        done()
    }
    fun updateTrip(draft: TripDraft, done: () -> Unit) = editTrip(done) { repository.updateTrip(it, draft) }
    fun uploadCover(jpeg: ByteArray) = editTrip { repository.uploadCover(it, jpeg) }
    fun removeCover() = editTrip { repository.removeCover(it) }

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

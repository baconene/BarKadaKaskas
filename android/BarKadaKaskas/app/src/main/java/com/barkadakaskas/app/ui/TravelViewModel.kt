package com.barkadakaskas.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

    /** True while a saved session is being checked at start-up; the app shows its loading screen, not the login page. */
    var restoring by mutableStateOf(repository.hasSession); private set
    /** Set when that check couldn't reach the server (the session is kept; the loading screen offers Retry). */
    var restoreFailed by mutableStateOf<String?>(null); private set

    init { if (repository.hasSession) restoreSession() }

    fun restoreSession() {
        restoring = true; restoreFailed = null
        viewModelScope.launch {
            try {
                user = repository.currentUser()
                restoring = false
                launch { trips = repository.trips() } // the dashboard shows skeleton cards meanwhile
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // A rejected token clears the session (go to login); a network problem keeps it so Retry works.
                if (!repository.hasSession) restoring = false else restoreFailed = failure.message ?: "Can't reach the server."
            }
        }
    }
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
    /** Reloads the open trip without the busy state, ignoring failures (used when returning to the app). */
    fun refreshQuietly() {
        val id = detail?.trip?.id ?: return
        if (busy) return
        viewModelScope.launch { runCatching { repository.trip(id) }.getOrNull()?.let { fresh -> if (detail?.trip?.id == id) detail = fresh } }
    }
    fun refresh() = launch { detail?.let { detail = repository.trip(it.trip.id) } ?: run { trips = repository.trips() } }
    fun openTrip(trip: Trip) = launch { detail = repository.trip(trip.id) }

    // Alerts (bell). [requestedTab] tells the trip screen which tab an alert or notification should open.
    var alerts by mutableStateOf<List<Alert>>(emptyList()); private set
    var unreadAlerts by mutableStateOf(0); private set
    var alertsLoading by mutableStateOf(false); private set
    var requestedTab by mutableStateOf<Int?>(null)
    /** Called with the newest alert id the app has shown, so background notifications don't repeat it. */
    var onAlertsSeen: (Long) -> Unit = {}
    fun loadAlerts(quiet: Boolean = false) {
        if (user == null) return
        if (!quiet) alertsLoading = true
        viewModelScope.launch {
            try { val page = repository.alerts(); alerts = page.alerts; unreadAlerts = page.unreadCount; page.alerts.maxOfOrNull { it.id }?.let(onAlertsSeen) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* offline: keep what we have */ }
            finally { alertsLoading = false }
        }
    }
    fun markAlertsRead(ids: List<Long> = emptyList()) {
        alerts = alerts.map { if (ids.isEmpty() || it.id in ids) it.copy(read = true) else it }
        viewModelScope.launch { runCatching { unreadAlerts = repository.markAlertsRead(ids) } }
    }
    /** Opens the alert's trip on the right tab (also used when a phone notification is tapped). */
    fun openAlertTrip(tripId: Long, tab: Int) = launch {
        requestedTab = tab
        if (detail?.trip?.id != tripId) { detail = repository.trip(tripId); posts = emptyList(); postsLoadedFor = null; postsNextBefore = null }
    }
    fun backToTrips() { if (!busy) { detail = null; posts = emptyList(); postsLoadedFor = null; postsNextBefore = null } }
    fun signOut() = launch { repository.logout(); user = null; detail = null; trips = emptyList() }
    /** [aiPrompt], when given, opens the AI planner on the new trip and starts suggesting. */
    fun createTrip(draft: TripDraft, aiPrompt: String = "", done: () -> Unit) = launch {
        val trip = repository.createTrip(draft); trips = repository.trips(); detail = repository.trip(trip.id)
        pendingAiPrompt = aiPrompt.trim().ifBlank { null }
        done()
    }

    // Traveller documents (boarding passes, IDs, bank QR codes), loaded when a traveller card opens.
    var documents by mutableStateOf<List<TravelerDocument>>(emptyList()); private set
    var documentsLoading by mutableStateOf(false); private set
    /** Downloaded files by path, so thumbnails and re-opening don't download again. */
    val documentFiles = mutableStateMapOf<String, ByteArray>()
    fun loadDocuments() {
        val tripId = detail?.trip?.id ?: return
        documentsLoading = true
        viewModelScope.launch {
            try { documents = repository.documents(tripId) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not load documents." }
            finally { documentsLoading = false }
        }
    }
    fun uploadDocument(type: String, label: String, visibility: String, bytes: ByteArray, mimeType: String, fileName: String, done: () -> Unit) = launch {
        val tripId = detail?.trip?.id ?: return@launch
        documents = listOf(repository.uploadDocument(tripId, type, label, visibility, bytes, mimeType, fileName)) + documents
        done()
    }
    fun toggleDocumentVisibility(document: TravelerDocument) = launch {
        val tripId = detail?.trip?.id ?: return@launch
        val updated = repository.setDocumentVisibility(tripId, document.id, if (document.visibility == "trip") "private" else "trip")
        documents = documents.map { if (it.id == document.id) updated else it }
    }
    fun deleteDocument(document: TravelerDocument) = launch {
        val tripId = detail?.trip?.id ?: return@launch
        repository.deleteDocument(tripId, document.id)
        documents = documents.filterNot { it.id == document.id }
    }
    /** Downloads (once) and hands the file to [use]; errors show in the app's error banner. */
    fun withDocumentFile(document: TravelerDocument, use: (ByteArray) -> Unit) {
        documentFiles[document.filePath]?.let { use(it); return }
        viewModelScope.launch {
            try { repository.documentFile(document.filePath).also { documentFiles[document.filePath] = it; use(it) } }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not open the document." }
        }
    }

    // AI trip planner (organizer). Suggestions are only a draft until addSuggestions saves the approved ones.
    var pendingAiPrompt by mutableStateOf<String?>(null)
    var suggestions by mutableStateOf<List<StopSuggestion>>(emptyList()); private set
    var stopChanges by mutableStateOf<List<StopChange>>(emptyList()); private set
    var planning by mutableStateOf(false); private set
    fun suggest(prompt: String) {
        val tripId = detail?.trip?.id ?: return
        if (planning || prompt.isBlank()) return
        planning = true; error = null
        viewModelScope.launch {
            try { val plan = repository.suggestStops(tripId, prompt); suggestions = plan.stops; stopChanges = plan.changes }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "The AI planner could not answer. Try again." }
            finally { planning = false }
        }
    }
    fun clearSuggestions() { suggestions = emptyList(); stopChanges = emptyList() }
    fun addSuggestions(approved: List<StopSuggestion>, changes: List<StopChange>, done: () -> Unit) = mutate({ clearSuggestions(); done() }) { repo, current -> repo.addStops(current.trip.id, approved, changes) }
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
    // Photos of a stop's place from the web, remembered per stop while the trip is open.
    private val stopPhotoCache = mutableMapOf<Long, List<PlacePhoto>>()
    suspend fun stopPhotos(stopId: Long): List<PlacePhoto> {
        stopPhotoCache[stopId]?.let { return it }
        val tripId = detail?.trip?.id ?: return emptyList()
        return runCatching { repository.stopPhotos(tripId, stopId) }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) stopPhotoCache[stopId] = it }
    }

    // Day highlight (Photos tab).
    var highlight by mutableStateOf<Highlight?>(null); private set
    var highlightLoading by mutableStateOf(false); private set
    var highlightCovering by mutableStateOf(false); private set
    fun loadHighlight(date: String? = null) {
        val tripId = detail?.trip?.id ?: return
        highlightLoading = true
        viewModelScope.launch {
            try { highlight = repository.highlight(tripId, date) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not load the highlight." }
            finally { highlightLoading = false }
        }
    }
    fun makeHighlightCover() {
        val tripId = detail?.trip?.id ?: return
        val current = highlight ?: return
        highlightCovering = true
        viewModelScope.launch {
            try { val url = repository.highlightCover(tripId, current.date); highlight = highlight?.copy(coverUrl = url) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not make the cover." }
            finally { highlightCovering = false }
        }
    }

    // AI photo edits (3 every 4 hours per traveller, enforced by the server).
    var aiQuota by mutableStateOf<AiQuota?>(null); private set
    var aiEditing by mutableStateOf(false); private set
    fun loadAiQuota() { viewModelScope.launch { runCatching { aiQuota = repository.aiQuota() } } }
    /** Runs an AI edit; [done] gets the new JPEG/PNG bytes. Errors (incl. the limit) show in the app's error banner. */
    fun aiEdit(jpeg: ByteArray, style: String?, prompt: String?, done: (ByteArray) -> Unit) {
        if (aiEditing) return
        aiEditing = true; error = null
        viewModelScope.launch {
            try { val (image, quota) = repository.aiEditPhoto(jpeg, style, prompt); quota?.let { aiQuota = it }; done(image) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "The AI couldn't edit the photo."; loadAiQuota() }
            finally { aiEditing = false }
        }
    }

    // Comments for the post whose comment sheet is open.
    var comments by mutableStateOf<List<PostComment>>(emptyList()); private set
    var commentsLoading by mutableStateOf(false); private set
    var commentSending by mutableStateOf(false); private set
    private fun setCommentCount(postId: Long, count: Int) { posts = posts.map { if (it.id == postId) it.copy(commentsCount = count) else it } }
    fun loadComments(post: Post) {
        val tripId = detail?.trip?.id ?: return
        comments = emptyList(); commentsLoading = true
        viewModelScope.launch {
            try { comments = repository.comments(tripId, post.id) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not load comments." }
            finally { commentsLoading = false }
        }
    }
    fun addComment(post: Post, body: String, done: () -> Unit) {
        val tripId = detail?.trip?.id ?: return
        if (body.isBlank() || commentSending) return
        commentSending = true
        viewModelScope.launch {
            try { val (comment, count) = repository.addComment(tripId, post.id, body); comments = comments + comment; setCommentCount(post.id, count); done() }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not post your comment." }
            finally { commentSending = false }
        }
    }
    fun deleteComment(post: Post, comment: PostComment) {
        val tripId = detail?.trip?.id ?: return
        viewModelScope.launch {
            try { val count = repository.deleteComment(tripId, post.id, comment.id); comments = comments.filterNot { it.id == comment.id }; setCommentCount(post.id, count) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "Could not remove the comment." }
        }
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

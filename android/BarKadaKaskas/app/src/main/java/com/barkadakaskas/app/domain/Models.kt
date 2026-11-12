package com.barkadakaskas.app.domain

data class User(val id: Long, val name: String, val email: String)
data class Trip(val id: Long, val name: String, val destination: String, val startsOn: String, val endsOn: String, val currency: String = "PHP", val timezone: String = "Asia/Manila", val memberCount: Int = 0, val status: String = "planning", val cancellationReason: String? = null, val hidden: Boolean = false, val description: String = "", val coverUrl: String? = null) {
    val cancelled: Boolean get() = status == "cancelled"
}
/** A trip member, with the contact details they chose to share with their trip-mates. */
data class Member(val id: Long, val name: String, val phone: String? = null, val otherContact: String? = null, val avatarUrl: String? = null,
                  val emergencyName: String? = null, val emergencyRelationship: String? = null, val emergencyPhone: String? = null)
data class ProfileDraft(val phone: String, val otherContact: String, val emergencyName: String, val emergencyRelationship: String, val emergencyPhone: String)
data class PostPhoto(val url: String, val width: Int?, val height: Int?)
data class Post(val id: Long, val caption: String?, val createdAt: String, val authorName: String, val authorAvatarUrl: String?, val photos: List<PostPhoto>, val likesCount: Int, val liked: Boolean, val canDelete: Boolean, val commentsCount: Int = 0)
/** Something another traveller did on one of your trips (comment, photos, expense, itinerary change). */
data class Alert(val id: Long, val type: String, val title: String, val body: String?, val tripId: Long, val tripName: String?, val read: Boolean, val createdAt: String) {
    /** The trip tab the alert opens: Itinerary 0, Expenses 1, Photos 2. */
    val tab: Int get() = when (type) { "expense" -> 1; "comment", "post" -> 2; else -> 0 }
}
data class AlertPage(val alerts: List<Alert>, val unreadCount: Int)
/** A day's highlight: cover, then itinerary stops with that day's photos, then an outro (about 30 s, with music). */
data class HighlightSlide(val time: String, val title: String, val location: String?, val photoUrl: String, val caption: String?, val duration: Double)
/** The cover's caption: "Day 3 of 8", trip name, full date, place, first → last stop, and counts. */
/** The reel: per stop, the trip there (transport, and a map with both stops' positions 0–1 on it), then its photos. */
data class HighlightTravel(val mode: String, val label: String, val from: String, val mapUrl: String?, val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val duration: Double)
data class HighlightPhoto(val url: String, val caption: String?, val by: String?, val duration: Double)
data class HighlightScene(val time: String, val title: String, val location: String?, val travel: HighlightTravel?, val photos: List<HighlightPhoto>)
data class HighlightCover(val eyebrow: String, val title: String, val date: String, val destination: String?, val route: String?, val stats: String)
data class Highlight(val date: String, val dayNumber: Int, val title: String, val subtitle: String, val coverUrl: String?, val basePhotoUrl: String?, val cover: HighlightCover?, val musicUrl: String,
    val coverDuration: Double, val outroDuration: Double, val slides: List<HighlightSlide>, val days: List<String>,
    val scenes: List<HighlightScene> = slides.map { HighlightScene(it.time, it.title, it.location, null, listOf(HighlightPhoto(it.photoUrl, it.caption, null, it.duration))) })
/** AI photo edits left in the current window (3 every 4 hours per traveller). */
data class AiQuota(val limit: Int, val remaining: Int, val resetsInSeconds: Int)
/** A comment under a photo post. */
data class PostComment(val id: Long, val body: String, val createdAt: String, val authorName: String, val authorAvatarUrl: String?, val canDelete: Boolean)
data class PostPage(val posts: List<Post>, val nextBefore: Long?)
data class Category(val id: Long, val name: String)
data class Stop(val id: Long, val title: String, val location: String, val description: String, val startsAt: String, val endsAt: String, val transport: String?, val latitude: Double? = null, val longitude: Double? = null, val costMinMinor: Long? = null, val costMaxMinor: Long? = null, val costNote: String? = null, val completedAt: String? = null) {
    val done: Boolean get() = completedAt != null
}
data class Share(val memberId: Long, val amountMinor: Long, val coveredMinor: Long = 0, val remainingMinor: Long = amountMinor)
data class Payment(val memberId: Long, val amountMinor: Long)
data class Expense(val id: Long, val title: String, val merchant: String, val category: String, val categoryId: Long, val totalMinor: Long, val status: String, val version: Int, val incurredAt: String, val splitMethod: String, val shares: List<Share>, val payments: List<Payment>, val createdByMemberId: Long? = null)
data class Balance(val memberId: Long, val name: String, val netMinor: Long)
/** The two sides the net balance hides: still to pay for one's own shares, and still to be paid back by others. */
data class Dues(val memberId: Long, val owesMinor: Long, val owedMinor: Long)
data class Settlement(val id: Long, val fromMemberId: Long, val toMemberId: Long, val amountMinor: Long, val method: String, val status: String, val expenseId: Long? = null)
/** What you and one other traveller owe each other: net > 0 they owe you, < 0 you owe them; the bills and repayments behind it. */
data class BetweenLine(val type: String, val label: String, val date: String?, val detail: String, val amountMinor: Long, val pending: Boolean)
data class Between(val memberId: Long, val name: String, val netMinor: Long, val lines: List<BetweenLine>)
data class TripDetail(val trip: Trip, val members: List<Member>, val categories: List<Category>, val stops: List<Stop>, val expenses: List<Expense>, val balances: List<Balance>, val settlements: List<Settlement>, val currentMemberId: Long, val isOrganizer: Boolean, val dues: List<Dues> = emptyList(), val aiPlanner: Boolean = false, val between: List<Between>? = null) {
    fun duesOf(memberId: Long): Dues = dues.find { it.memberId == memberId } ?: Dues(memberId, 0, 0)
}
data class TripDraft(val name: String, val destination: String, val startsOn: String, val endsOn: String, val currency: String = "PHP", val timezone: String = "Asia/Manila", val description: String = "")
/** latitude/longitude keep (or set) the map pin; the server clears it when they are omitted. */
/** A traveller's document for a trip (boarding pass, ID, bank QR...). [visibility] is "trip" (everyone) or "private" (owner and organizer). */
data class TravelerDocument(val id: Long, val memberId: Long, val type: String, val label: String?, val visibility: String, val mimeType: String, val isImage: Boolean, val mine: Boolean, val filePath: String) {
    val title: String get() = label ?: typeName(type)
    companion object {
        val TYPES = listOf("boarding_pass" to "Boarding pass", "id" to "ID", "bank_qr" to "Bank / e-wallet QR", "booking" to "Booking", "other" to "Other")
        fun typeName(type: String) = TYPES.find { it.first == type }?.second ?: "Document"
    }
}
/** An activity suggested by the AI planner; times are local to the trip ("yyyy-MM-ddTHH:mm"), costs per person in minor units. */
/** A new time for an activity already planned, suggested by the AI planner (local times, "yyyy-MM-ddTHH:mm"). */
/** A photo of a stop's place from the web (Google Places or Wikimedia Commons), with its credit. */
/** A recommended thing to do at a stop's place (AI suggestion). */
/** "Prep for the day" from the itinerary (AI): summary, what to wear, what to bring, tips. */
data class DayPrep(val summary: String?, val outfit: List<String>, val bring: List<String>, val tips: List<String>)
data class PlaceTip(val title: String, val detail: String?)
data class PlacePhoto(val url: String, val credit: String?, val source: String)
data class StopChange(val itemId: Long, val title: String, val fromStartsAt: String, val fromEndsAt: String, val startsAt: String, val endsAt: String, val reason: String?)
data class PlanSuggestion(val stops: List<StopSuggestion>, val changes: List<StopChange>)
data class StopSuggestion(val title: String, val location: String?, val description: String?, val startsAt: String, val endsAt: String, val transport: String?, val costMinMinor: Long?, val costMaxMinor: Long?, val costNote: String?)
data class StopDraft(val title: String, val location: String, val description: String, val startsAt: String, val endsAt: String, val transport: String?, val latitude: Double? = null, val longitude: Double? = null)
/** One receipt line in an itemized split, shared equally by [memberIds]. */
data class ItemDraft(val description: String, val amountMinor: Long, val memberIds: List<Long>)
/** With [items] the expense is split per item (lines must add up to [totalMinor]); otherwise equally between [memberIds]. */
data class ExpenseDraft(val title: String, val merchant: String, val categoryId: Long, val totalMinor: Long, val incurredAt: String, val memberIds: List<Long>, val paidByMemberId: Long?, val items: List<ItemDraft> = emptyList(), val receiptScanId: Long? = null)
data class ReceiptLine(val description: String, val amountMinor: Long)
/** What the server read from a receipt photo. Everything is a suggestion the traveller can edit. */
data class ReceiptDraft(val scanId: Long, val title: String, val merchant: String?, val incurredAt: String?, val categoryId: Long?, val totalMinor: Long?, val lines: List<ReceiptLine>, val warnings: List<String>)

/** HTTP adapter owns token persistence, JSON mapping, 401 handling and validation errors. */
interface TravelRepository {
    val serverUrl: String
    val hasSession: Boolean
    suspend fun configureServer(url: String)
    suspend fun login(email: String, password: String): User
    suspend fun register(name: String, email: String, password: String): User
    suspend fun currentUser(): User
    suspend fun logout()
    suspend fun trips(): List<Trip>
    suspend fun createTrip(draft: TripDraft): Trip
    suspend fun trip(id: Long): TripDetail
    suspend fun saveStop(tripId: Long, stopId: Long?, draft: StopDraft)
    /** Ticks an activity off for everyone on the trip, or undoes it. Any traveller on the trip may do this. */
    suspend fun setStopDone(tripId: Long, stopId: Long, done: Boolean)
    /** Organizer: the AI planner drafts activities for [prompt]; nothing is saved. */
    suspend fun suggestStops(tripId: Long, prompt: String): PlanSuggestion
    /** Documents on the trip this traveller may see (shared ones, plus their own and, for the organizer, everyone's). */
    suspend fun documents(tripId: Long): List<TravelerDocument>
    suspend fun uploadDocument(tripId: Long, type: String, label: String, visibility: String, bytes: ByteArray, mimeType: String, fileName: String): TravelerDocument
    suspend fun setDocumentVisibility(tripId: Long, documentId: Long, visibility: String): TravelerDocument
    suspend fun deleteDocument(tripId: Long, documentId: Long)
    /** The file's bytes (sent with the session token; documents are never public). */
    suspend fun documentFile(filePath: String): ByteArray
    /** Organizer: adds the approved suggestions to the itinerary in one go. */
    suspend fun addStops(tripId: Long, stops: List<StopSuggestion>, changes: List<StopChange> = emptyList())
    suspend fun createExpense(tripId: Long, draft: ExpenseDraft)
    suspend fun updateProfile(draft: ProfileDraft)
    suspend fun uploadAvatar(jpeg: ByteArray)
    suspend fun removeAvatar()
    suspend fun posts(tripId: Long, before: Long? = null): PostPage
    /** [jpegs] are downscaled photos (1 to 10). */
    suspend fun createPost(tripId: Long, caption: String, jpegs: List<ByteArray>): Post
    suspend fun deletePost(tripId: Long, postId: Long)
    /** Toggles the viewer's heart; returns (liked, likesCount). */
    suspend fun toggleLike(tripId: Long, postId: Long): Pair<Boolean, Int>
    suspend fun comments(tripId: Long, postId: Long): List<PostComment>
    /** AI-edits a photo with a template ([style]) or a request ([prompt]); returns the new image and the quota left. */
    suspend fun aiEditPhoto(jpeg: ByteArray, style: String?, prompt: String?): Pair<ByteArray, AiQuota?>
    suspend fun aiQuota(): AiQuota
    /** Photos of a stop's place from the web (empty when none are found). */
    suspend fun stopPhotos(tripId: Long, stopId: Long): List<PlacePhoto>
    /** Recommended things to do at a stop's place (empty when the AI isn't available). */
    suspend fun stopTips(tripId: Long, stopId: Long): List<PlaceTip>
    /** "Prep for the day" for [date] (yyyy-MM-dd); null when the AI isn't available or the day is empty. */
    suspend fun dayPrep(tripId: Long, date: String): DayPrep?
    /** The day's highlight ([date] yyyy-MM-dd; today in the trip's time zone when null). */
    suspend fun highlight(tripId: Long, date: String? = null): Highlight
    /** Makes the day's AI cover (one AI edit); returns its URL. */
    suspend fun highlightCover(tripId: Long, date: String): String
    /** Your latest alerts (or only those newer than [after]) and the unread count. */
    suspend fun alerts(after: Long? = null): AlertPage
    /** Marks [ids] (or all, when empty) read; returns the unread count. */
    suspend fun markAlertsRead(ids: List<Long> = emptyList()): Int
    /** Returns the new comment and the post's comment count. */
    suspend fun addComment(tripId: Long, postId: Long, body: String): Pair<PostComment, Int>
    /** Returns the post's comment count. */
    suspend fun deleteComment(tripId: Long, postId: Long, commentId: Long): Int
    /** Removes an expense entered by mistake; only while no payments are recorded (organizer or its creator). */
    suspend fun deleteExpense(tripId: Long, expenseId: Long)
    /** Reads a receipt photo (downscaled JPEG) into an expense draft. */
    suspend fun scanReceipt(tripId: Long, jpeg: ByteArray): ReceiptDraft
    suspend fun changeExpenseStatus(tripId: Long, expense: Expense, status: String, reason: String? = null, voidPayments: Boolean = false)
    suspend fun markSharePaid(tripId: Long, expense: Expense, memberId: Long, settlementId: Long? = null, newPayment: Boolean = false)
    suspend fun recordMerchantPayment(tripId: Long, expense: Expense, memberId: Long, amountMinor: Long)
    /** Organizer replaces who paid the merchant ([payments] = member id to amount; empty means unpaid). Shares are unchanged. */
    suspend fun setExpensePayers(tripId: Long, expense: Expense, payments: List<Payment>)
    suspend fun recordSettlement(tripId: Long, recipientId: Long, amountMinor: Long, method: String)
    suspend fun confirmSettlement(tripId: Long, settlementId: Long)
    suspend fun createInvitation(tripId: Long): String
    /** Organizer only. Edits the trip details (currency is locked by the server once expenses exist). */
    suspend fun updateTrip(tripId: Long, draft: TripDraft)
    /** Organizer only. [jpeg] is an already downscaled JPEG. */
    suspend fun uploadCover(tripId: Long, jpeg: ByteArray)
    suspend fun removeCover(tripId: Long)
    /** Organizer only. Cancels the trip; [hide] also removes it from everyone's trip list. */
    suspend fun cancelTrip(tripId: Long, reason: String?, hide: Boolean)
    suspend fun reopenTrip(tripId: Long)
    suspend fun setTripHidden(tripId: Long, hidden: Boolean)
}

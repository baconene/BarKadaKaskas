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
data class Post(val id: Long, val caption: String?, val createdAt: String, val authorName: String, val authorAvatarUrl: String?, val photos: List<PostPhoto>, val likesCount: Int, val liked: Boolean, val canDelete: Boolean)
data class PostPage(val posts: List<Post>, val nextBefore: Long?)
data class Category(val id: Long, val name: String)
data class Stop(val id: Long, val title: String, val location: String, val description: String, val startsAt: String, val endsAt: String, val transport: String?, val latitude: Double? = null, val longitude: Double? = null, val costMinMinor: Long? = null, val costMaxMinor: Long? = null, val costNote: String? = null)
data class Share(val memberId: Long, val amountMinor: Long, val coveredMinor: Long = 0, val remainingMinor: Long = amountMinor)
data class Payment(val memberId: Long, val amountMinor: Long)
data class Expense(val id: Long, val title: String, val merchant: String, val category: String, val categoryId: Long, val totalMinor: Long, val status: String, val version: Int, val incurredAt: String, val splitMethod: String, val shares: List<Share>, val payments: List<Payment>, val createdByMemberId: Long? = null)
data class Balance(val memberId: Long, val name: String, val netMinor: Long)
data class Settlement(val id: Long, val fromMemberId: Long, val toMemberId: Long, val amountMinor: Long, val method: String, val status: String, val expenseId: Long? = null)
data class TripDetail(val trip: Trip, val members: List<Member>, val categories: List<Category>, val stops: List<Stop>, val expenses: List<Expense>, val balances: List<Balance>, val settlements: List<Settlement>, val currentMemberId: Long, val isOrganizer: Boolean)
data class TripDraft(val name: String, val destination: String, val startsOn: String, val endsOn: String, val currency: String = "PHP", val timezone: String = "Asia/Manila", val description: String = "")
/** latitude/longitude keep (or set) the map pin; the server clears it when they are omitted. */
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
    /** Removes an expense entered by mistake; only while no payments are recorded (organizer or its creator). */
    suspend fun deleteExpense(tripId: Long, expenseId: Long)
    /** Reads a receipt photo (downscaled JPEG) into an expense draft. */
    suspend fun scanReceipt(tripId: Long, jpeg: ByteArray): ReceiptDraft
    suspend fun changeExpenseStatus(tripId: Long, expense: Expense, status: String, reason: String? = null, voidPayments: Boolean = false)
    suspend fun markSharePaid(tripId: Long, expense: Expense, memberId: Long, settlementId: Long? = null, newPayment: Boolean = false)
    suspend fun recordMerchantPayment(tripId: Long, expense: Expense, memberId: Long, amountMinor: Long)
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

package com.barkadakaskas.app.domain

data class User(val id: Long, val name: String, val email: String)
data class Trip(val id: Long, val name: String, val destination: String, val startsOn: String, val endsOn: String, val currency: String = "PHP", val timezone: String = "Asia/Manila", val memberCount: Int = 0)
data class Member(val id: Long, val name: String)
data class Category(val id: Long, val name: String)
data class Stop(val id: Long, val title: String, val location: String, val description: String, val startsAt: String, val endsAt: String, val transport: String?, val latitude: Double? = null, val longitude: Double? = null, val costMinMinor: Long? = null, val costMaxMinor: Long? = null, val costNote: String? = null)
data class Share(val memberId: Long, val amountMinor: Long, val coveredMinor: Long = 0, val remainingMinor: Long = amountMinor)
data class Payment(val memberId: Long, val amountMinor: Long)
data class Expense(val id: Long, val title: String, val merchant: String, val category: String, val categoryId: Long, val totalMinor: Long, val status: String, val version: Int, val incurredAt: String, val splitMethod: String, val shares: List<Share>, val payments: List<Payment>)
data class Balance(val memberId: Long, val name: String, val netMinor: Long)
data class Settlement(val id: Long, val fromMemberId: Long, val toMemberId: Long, val amountMinor: Long, val method: String, val status: String, val expenseId: Long? = null)
data class TripDetail(val trip: Trip, val members: List<Member>, val categories: List<Category>, val stops: List<Stop>, val expenses: List<Expense>, val balances: List<Balance>, val settlements: List<Settlement>, val currentMemberId: Long, val isOrganizer: Boolean)
data class TripDraft(val name: String, val destination: String, val startsOn: String, val endsOn: String, val currency: String = "PHP", val timezone: String = "Asia/Manila")
/** latitude/longitude keep (or set) the map pin; the server clears it when they are omitted. */
data class StopDraft(val title: String, val location: String, val description: String, val startsAt: String, val endsAt: String, val transport: String?, val latitude: Double? = null, val longitude: Double? = null)
data class ExpenseDraft(val title: String, val merchant: String, val categoryId: Long, val totalMinor: Long, val incurredAt: String, val memberIds: List<Long>, val paidByMemberId: Long?)

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
    suspend fun changeExpenseStatus(tripId: Long, expense: Expense, status: String, reason: String? = null, voidPayments: Boolean = false)
    suspend fun markSharePaid(tripId: Long, expense: Expense, memberId: Long, settlementId: Long? = null, newPayment: Boolean = false)
    suspend fun recordMerchantPayment(tripId: Long, expense: Expense, memberId: Long, amountMinor: Long)
    suspend fun recordSettlement(tripId: Long, recipientId: Long, amountMinor: Long, method: String)
    suspend fun confirmSettlement(tripId: Long, settlementId: Long)
    suspend fun createInvitation(tripId: Long): String
}

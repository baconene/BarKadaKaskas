package com.barkadakaskas.app.data

import com.barkadakaskas.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps Laravel API JSON (docs/mobile-api.md) to domain models.
 * Android's org.json turns JSON null into the string "null" for optString, so use the helpers below.
 */
internal object ApiJson {
    private fun JSONObject.text(name: String): String = if (!has(name) || isNull(name)) "" else optString(name)
    private fun JSONObject.textOrNull(name: String): String? = text(name).ifBlank { null }
    private fun JSONObject.longOrNull(name: String): Long? = if (!has(name) || isNull(name)) null else optLong(name)
    private fun JSONObject.doubleOrNull(name: String): Double? = if (!has(name) || isNull(name)) null else optDouble(name).takeUnless { it.isNaN() }
    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }

    fun user(json: JSONObject) = User(json.getLong("id"), json.text("name"), json.text("email"))

    fun trip(json: JSONObject) = Trip(
        id = json.getLong("id"),
        name = json.text("name"),
        destination = json.text("destination"),
        startsOn = json.text("starts_on").take(10),
        endsOn = json.text("ends_on").take(10),
        currency = json.text("currency_code").ifBlank { "PHP" },
        timezone = json.text("timezone").ifBlank { "Asia/Manila" },
        memberCount = json.optInt("members_count", json.optJSONArray("members")?.length() ?: 0),
        status = json.text("status").ifBlank { "planning" },
        cancellationReason = json.textOrNull("cancellation_reason"),
        hidden = json.textOrNull("hidden_at") != null,
        description = json.text("description"),
        coverUrl = json.textOrNull("cover_url"),
    )

    fun trips(data: JSONArray?): List<Trip> = data.mapObjects(::trip)

    /** `data` from GET /trips/{id}: { trip, categories, balances, expense_shares, current_member_id, is_organizer }. */
    fun tripDetail(data: JSONObject): TripDetail {
        val trip = data.getJSONObject("trip")
        val shares = data.optJSONObject("expense_shares")
        return TripDetail(
            trip = trip(trip),
            members = trip.optJSONArray("members").mapObjects { Member(it.getLong("id"), it.optJSONObject("user")?.text("name").orEmpty().ifBlank { "Traveler" }) },
            categories = data.optJSONArray("categories").mapObjects { Category(it.getLong("id"), it.text("name")) },
            stops = trip.optJSONArray("itinerary_items").mapObjects { stop(it) },
            expenses = trip.optJSONArray("expenses").mapObjects { expense(it, shares?.optJSONArray(it.getLong("id").toString())) },
            balances = data.optJSONArray("balances").mapObjects { Balance(it.getLong("member_id"), it.text("name"), it.optLong("net_minor")) },
            settlements = trip.optJSONArray("settlements").mapObjects { settlement(it) },
            currentMemberId = data.optLong("current_member_id"),
            isOrganizer = data.optBoolean("is_organizer"),
        )
    }

    fun stop(json: JSONObject) = Stop(
        id = json.getLong("id"),
        title = json.text("title"),
        location = json.text("location"),
        description = json.text("description"),
        startsAt = json.text("starts_at"),
        endsAt = json.text("ends_at"),
        transport = json.textOrNull("transport_mode"),
        latitude = json.doubleOrNull("latitude"),
        longitude = json.doubleOrNull("longitude"),
        costMinMinor = json.longOrNull("cost_min_minor"),
        costMaxMinor = json.longOrNull("cost_max_minor"),
        costNote = json.textOrNull("cost_note"),
    )

    fun expense(json: JSONObject, shareRows: JSONArray?): Expense {
        val payments = json.optJSONArray("payments").mapObjects { Payment(it.getLong("trip_member_id"), it.optLong("amount_minor")) }
        // Prefer the server's computed share rows (they include what is already covered); fall back to allocations.
        val shares = if (shareRows != null && shareRows.length() > 0) {
            shareRows.mapObjects { Share(it.getLong("member_id"), it.optLong("share_minor"), it.optLong("covered_minor"), it.optLong("remaining_minor")) }
                .filter { it.amountMinor > 0 }
        } else {
            json.optJSONArray("allocations").mapObjects { it.getLong("trip_member_id") to it.optLong("amount_minor") }
                .groupBy({ it.first }, { it.second }).map { (member, amounts) -> Share(member, amounts.sum()) }
        }
        return Expense(
            id = json.getLong("id"),
            title = json.text("title"),
            merchant = json.text("merchant"),
            category = json.optJSONObject("category")?.text("name").orEmpty(),
            categoryId = json.optLong("category_id"),
            totalMinor = json.optLong("total_minor"),
            status = json.text("status"),
            version = json.optInt("version", 1),
            incurredAt = json.text("incurred_at").take(10),
            splitMethod = json.text("split_method"),
            shares = shares,
            payments = payments,
        )
    }

    fun settlement(json: JSONObject) = Settlement(
        id = json.getLong("id"),
        fromMemberId = json.getLong("from_member_id"),
        toMemberId = json.getLong("to_member_id"),
        amountMinor = json.optLong("amount_minor"),
        method = json.text("payment_method"),
        status = json.text("status"),
        expenseId = json.longOrNull("expense_id"),
    )

    /** First readable message from a Laravel error body ({ message, errors: { field: [..] } }). */
    fun errorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        json.optJSONObject("errors")?.let { errors ->
            val keys = errors.keys()
            if (keys.hasNext()) errors.optJSONArray(keys.next())?.optString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return json.textOrNull("message")
    }

    fun cancelBody(reason: String?, hide: Boolean) = JSONObject().put("reason", reason?.trim()?.ifBlank { null } ?: JSONObject.NULL).put("hide", hide)

    fun tripBody(draft: TripDraft) = JSONObject()
        .put("name", draft.name.trim()).put("destination", draft.destination.trim())
        .put("starts_on", draft.startsOn.trim()).put("ends_on", draft.endsOn.trim())
        .put("timezone", draft.timezone.trim()).put("currency_code", draft.currency)
        .put("description", draft.description.trim().ifBlank { null } ?: JSONObject.NULL)

    fun stopBody(draft: StopDraft) = JSONObject()
        .put("title", draft.title.trim()).put("location", draft.location.trim()).put("description", draft.description.trim())
        .put("starts_at", draft.startsAt.trim()).put("ends_at", draft.endsAt.trim())
        .put("transport_mode", draft.transport ?: JSONObject.NULL)
        .apply { if (draft.latitude != null && draft.longitude != null) put("latitude", draft.latitude).put("longitude", draft.longitude) }

    /** Equal split between the chosen travellers; the server allocates leftover centavos fairly. */
    fun expenseBody(draft: ExpenseDraft) = JSONObject()
        .put("title", draft.title.trim()).put("merchant", draft.merchant.trim())
        .put("category_id", draft.categoryId).put("incurred_at", draft.incurredAt.trim())
        .put("total_minor", draft.totalMinor).put("split_method", "equal")
        .put("payments", JSONArray().apply { draft.paidByMemberId?.let { put(JSONObject().put("trip_member_id", it).put("amount_minor", draft.totalMinor)) } })
        .put("allocations", JSONArray().apply { draft.memberIds.forEach { put(JSONObject().put("trip_member_id", it).put("weight", 1)) } })
        .put("items", JSONArray())
}

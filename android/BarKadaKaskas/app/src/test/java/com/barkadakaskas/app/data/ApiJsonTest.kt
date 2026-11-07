package com.barkadakaskas.app.data

import com.barkadakaskas.app.domain.ExpenseDraft
import com.barkadakaskas.app.domain.StopDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses response shapes produced by the Laravel API (see tests/Feature/MobileApiTest.php). */
class ApiJsonTest {
    private val detailJson = """
        {"trip":{"id":4,"name":"Siquijor Barkada Trip","destination":"Siquijor, Philippines","starts_on":"2026-11-11","ends_on":"2026-11-17",
          "timezone":"Asia/Manila","currency_code":"PHP",
          "members":[{"id":7,"user_id":1,"status":"active","user":{"id":1,"name":"John"}},{"id":8,"user_id":2,"status":"active","user":{"id":2,"name":"Stephanie"}}],
          "itinerary_items":[{"id":11,"title":"Lunch","location":"San Juan","description":null,"starts_at":"2026-11-12T04:00:00.000000Z","ends_at":"2026-11-12T05:00:00.000000Z","transport_mode":null}],
          "expenses":[{"id":21,"title":"Dinner","merchant":null,"category_id":3,"category":{"id":3,"name":"Food"},"total_minor":10001,"status":"completed","version":3,"incurred_at":"2026-11-11","split_method":"equal",
            "payments":[{"trip_member_id":7,"amount_minor":10001}],
            "allocations":[{"trip_member_id":7,"amount_minor":5001},{"trip_member_id":8,"amount_minor":5000}]}],
          "settlements":[{"id":31,"expense_id":null,"from_member_id":8,"to_member_id":7,"amount_minor":5000,"payment_method":"gcash","status":"pending"}]},
         "categories":[{"id":3,"name":"Food"}],
         "balances":[{"member_id":7,"name":"John","net_minor":5000},{"member_id":8,"name":"Stephanie","net_minor":-5000}],
         "expense_shares":{"21":[{"member_id":7,"share_minor":5001,"covered_minor":10001,"remaining_minor":0},{"member_id":8,"share_minor":5000,"covered_minor":0,"remaining_minor":5000}]},
         "current_member_id":7,"is_organizer":true}
    """.trimIndent()

    @Test fun tripDetailMapsEverySectionAndHandlesNulls() {
        val detail = ApiJson.tripDetail(JSONObject(detailJson))
        assertEquals("Siquijor Barkada Trip", detail.trip.name)
        assertEquals(2, detail.trip.memberCount)
        assertEquals(listOf("John", "Stephanie"), detail.members.map { it.name })
        assertEquals("", detail.stops.single().description) // JSON null must not become the text "null"
        assertNull(detail.stops.single().transport)
        val expense = detail.expenses.single()
        assertEquals("", expense.merchant)
        assertEquals("Food", expense.category)
        assertEquals(3, expense.version)
        assertEquals(listOf(5000L), expense.shares.filter { it.memberId == 8L }.map { it.remainingMinor })
        assertNull(detail.settlements.single().expenseId)
        assertEquals(-5000L, detail.balances.first { it.memberId == 8L }.netMinor)
        assertEquals(7L, detail.currentMemberId)
        assertTrue(detail.isOrganizer)
    }

    @Test fun sharesFallBackToAllocationsWhenServerRowsAreMissing() {
        val json = JSONObject(detailJson).apply { put("expense_shares", JSONObject()) }
        val shares = ApiJson.tripDetail(json).expenses.single().shares
        assertEquals(mapOf(7L to 5001L, 8L to 5000L), shares.associate { it.memberId to it.amountMinor })
    }

    @Test fun tripListUsesMembersCount() {
        val trips = ApiJson.trips(JSONArray("""[{"id":1,"name":"Bohol","destination":"Bohol","starts_on":"2026-12-01","ends_on":"2026-12-05","timezone":"Asia/Manila","currency_code":"PHP","members_count":3}]"""))
        assertEquals(3, trips.single().memberCount)
    }

    @Test fun validationErrorsBecomeReadableMessages() {
        assertEquals("The provided credentials are incorrect.", ApiJson.errorMessage("""{"message":"The provided credentials are incorrect.","errors":{"email":["The provided credentials are incorrect."]}}"""))
        assertEquals("Unauthenticated.", ApiJson.errorMessage("""{"message":"Unauthenticated."}"""))
        assertNull(ApiJson.errorMessage("<html>Server error</html>"))
        assertNull(ApiJson.errorMessage(null))
    }

    @Test fun expenseBodySplitsEquallyAndOmitsPaymentWhenUnpaid() {
        val unpaid = ApiJson.expenseBody(ExpenseDraft("Dinner", "Cafe", 3, 10001, "2026-11-11", listOf(7, 8), null))
        assertEquals("equal", unpaid.getString("split_method"))
        assertEquals(0, unpaid.getJSONArray("payments").length())
        assertEquals(2, unpaid.getJSONArray("allocations").length())
        val paid = ApiJson.expenseBody(ExpenseDraft("Dinner", "Cafe", 3, 10001, "2026-11-11", listOf(7, 8), 7))
        assertEquals(10001L, paid.getJSONArray("payments").getJSONObject(0).getLong("amount_minor"))
    }

    @Test fun stopBodySendsNullTransportForStartingPoint() {
        val body = ApiJson.stopBody(StopDraft("Arrive", "Airport", "", "2026-11-11T08:00", "2026-11-11T09:00", null))
        assertTrue(body.isNull("transport_mode"))
        assertFalse(body.has("latitude"))
    }

    @Test fun stopBodyKeepsMapPin() {
        val body = ApiJson.stopBody(StopDraft("Falls", "Cambugahay Falls", "", "2026-11-11T08:00", "2026-11-11T09:00", "tricycle", 9.14, 123.62))
        assertEquals(9.14, body.getDouble("latitude"), 0.0)
        assertEquals(123.62, body.getDouble("longitude"), 0.0)
    }

    @Test fun tripReadsCancellationAndHiddenState() {
        val active = ApiJson.trip(JSONObject("""{"id":1,"name":"A","destination":"B","starts_on":"2026-11-11","ends_on":"2026-11-12","status":"planning","cancellation_reason":null,"hidden_at":null}"""))
        assertFalse(active.cancelled); assertFalse(active.hidden); assertNull(active.cancellationReason)
        val hidden = ApiJson.trip(JSONObject("""{"id":2,"name":"A","destination":"B","starts_on":"2026-11-11","ends_on":"2026-11-12","status":"cancelled","cancellation_reason":"Typhoon","hidden_at":"2026-11-08T10:00:00.000000Z"}"""))
        assertTrue(hidden.cancelled); assertTrue(hidden.hidden); assertEquals("Typhoon", hidden.cancellationReason)
        val body = ApiJson.cancelBody("  ", true)
        assertTrue(body.isNull("reason")); assertTrue(body.getBoolean("hide"))
    }

    @Test fun tripReadsCoverAndSendsDescription() {
        val trip = ApiJson.trip(JSONObject("""{"id":1,"name":"A","destination":"B","starts_on":"2026-11-08","ends_on":"2026-11-08","description":null,"cover_url":"https://example.test/trip-covers/abc.jpg"}"""))
        assertEquals("https://example.test/trip-covers/abc.jpg", trip.coverUrl); assertEquals("", trip.description)
        assertNull(ApiJson.trip(JSONObject("""{"id":2,"name":"A","destination":"B","starts_on":"2026-11-08","ends_on":"2026-11-08","cover_url":null}""")).coverUrl)
        val body = ApiJson.tripBody(com.barkadakaskas.app.domain.TripDraft("A", "B", "2026-11-08", "2026-11-09", description = "  Carpool  "))
        assertEquals("Carpool", body.getString("description"))
        assertTrue(ApiJson.tripBody(com.barkadakaskas.app.domain.TripDraft("A", "B", "2026-11-08", "2026-11-09")).isNull("description"))
    }

    @Test fun itemizedExpenseAndReceiptDraft() {
        val body = ApiJson.expenseBody(ExpenseDraft("Dinner", "Cafe", 3, 1000, "2026-11-11", listOf(7, 8), null, listOf(com.barkadakaskas.app.domain.ItemDraft("Sisig", 600, listOf(7)), com.barkadakaskas.app.domain.ItemDraft("Rice", 400, listOf(7, 8))), receiptScanId = 42))
        assertEquals("itemized", body.getString("split_method"))
        assertEquals(0, body.getJSONArray("allocations").length())
        assertEquals(2, body.getJSONArray("items").getJSONObject(1).getJSONArray("member_ids").length())
        assertEquals(42L, body.getLong("receipt_scan_id"))
        assertTrue(ApiJson.expenseBody(ExpenseDraft("A", "", 1, 100, "2026-11-11", listOf(7), null)).isNull("receipt_scan_id"))

        val draft = ApiJson.receiptDraft(JSONObject("""{"scan_id":5,"title":"Jollibee","merchant":"Jollibee","incurred_at":null,"category_id":2,"total_minor":50000,"lines":[{"description":"Chickenjoy","amount_minor":50000}],"warnings":["Check the total"]}"""))
        assertEquals(5L, draft.scanId); assertNull(draft.incurredAt); assertEquals(2L, draft.categoryId); assertEquals(1, draft.lines.size); assertEquals(listOf("Check the total"), draft.warnings)
    }
}

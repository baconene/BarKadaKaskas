package com.barkadakaskas.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceBreakdownTest {
    private fun expense(id: Long, title: String, total: Long, payments: List<Payment>, shares: List<Share>, status: String = "open") =
        Expense(id, title, "", "", 1, total, status, 1, "2026-11-11", "equal", shares, payments)

    // John (1) paid Cebu Pacific; the Airbnb has only Stephanie's (2) down payment; a cancelled bill is ignored.
    private val detail = TripDetail(
        trip = Trip(1, "Siquijor", "Siquijor", "2026-11-11", "2026-11-17"), members = emptyList(), categories = emptyList(), stops = emptyList(),
        expenses = listOf(
            expense(10, "Cebu Pacific", 713484, listOf(Payment(1, 713484)), listOf(Share(1, 237828, 713484, 0), Share(2, 237828, 0, 237828), Share(3, 237828, 0, 237828))),
            expense(11, "Airbnb", 1250000, listOf(Payment(2, 100000)), listOf(Share(1, 250000, 0, 250000), Share(2, 250000, 100000, 150000))),
            expense(12, "Cancelled", 5000, emptyList(), listOf(Share(1, 5000, 0, 5000)), status = "cancelled"),
        ),
        balances = emptyList(), settlements = emptyList(), currentMemberId = 1, isOrganizer = true,
    )

    @Test fun splitsPayAndReceiveByBill() {
        val result = BalanceBreakdown.of(detail)
        assertEquals(listOf("Airbnb"), result.pay.map { it.title })
        assertEquals(250000L, result.pay[0].amountMinor)
        assertTrue(result.pay[0].toMemberIds.isEmpty()); assertTrue(result.pay[0].merchantUnpaid)
        assertEquals(listOf("Cebu Pacific"), result.receive.map { it.title })
        assertEquals(475656L, result.receive[0].amountMinor)
        assertEquals(listOf(BalanceBreakdown.Debtor(2, 237828), BalanceBreakdown.Debtor(3, 237828)), result.receive[0].from)
    }

    @Test fun downPaymentReducesWhatThatTravellerStillPays() {
        val result = BalanceBreakdown.of(detail, memberId = 2)
        assertEquals(listOf(150000L, 237828L), result.pay.sortedBy { it.amountMinor }.map { it.amountMinor })
        assertEquals(listOf(1L), result.pay.first { it.title == "Cebu Pacific" }.toMemberIds)
        assertTrue(result.receive.isEmpty())
    }
}

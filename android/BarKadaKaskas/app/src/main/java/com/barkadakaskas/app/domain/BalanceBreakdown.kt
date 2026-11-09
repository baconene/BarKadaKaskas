package com.barkadakaskas.app.domain

/**
 * Splits a traveller's net balance into what they will pay (their unpaid shares) and what they will receive
 * (others' unpaid shares on bills they covered), bill by bill. Same rules as the web pop-up; the totals match
 * the server's dues.
 */
object BalanceBreakdown {
    data class PayLine(val expenseId: Long, val title: String, val amountMinor: Long, val toMemberIds: List<Long>, val merchantUnpaid: Boolean)
    data class Debtor(val memberId: Long, val amountMinor: Long)
    /** [from] is empty when the bill had several payers, so who owes whom is not certain. */
    data class ReceiveLine(val expenseId: Long, val title: String, val amountMinor: Long, val from: List<Debtor>)
    data class Result(val pay: List<PayLine>, val receive: List<ReceiveLine>)

    private val counted = setOf("open", "completed", "closed")

    fun of(detail: TripDetail, memberId: Long = detail.currentMemberId): Result {
        val pay = mutableListOf<PayLine>(); val receive = mutableListOf<ReceiveLine>()
        detail.expenses.filter { it.status in counted }.forEach { expense ->
            val me = expense.shares.find { it.memberId == memberId } ?: return@forEach
            val creditors = expense.shares.filter { it.coveredMinor > it.amountMinor && it.memberId != memberId }
            if (me.remainingMinor > 0) {
                val merchantUnpaid = expense.totalMinor - expense.payments.sumOf { it.amountMinor } > 0
                pay += PayLine(expense.id, expense.title, me.remainingMinor, creditors.map { it.memberId }, merchantUnpaid)
            }
            if (me.coveredMinor > me.amountMinor) {
                val from = if (creditors.isEmpty()) expense.shares.filter { it.memberId != memberId && it.remainingMinor > 0 }.map { Debtor(it.memberId, it.remainingMinor) } else emptyList()
                receive += ReceiveLine(expense.id, expense.title, me.coveredMinor - me.amountMinor, from)
            }
        }
        return Result(pay, receive)
    }
}

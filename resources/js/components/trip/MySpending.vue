<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { Receipt, X } from 'lucide-vue-next';

/**
 * "Your lines": every bill you paid the merchant for (with your own share of it), and every bill where part of your
 * share is still to pay (and to whom). Same rules as the balances: cancelled and draft bills don't count.
 */
type Expense = { id: number; title: string; merchant: string | null; incurred_at: string; status: string; total_minor: number; payments: { trip_member_id: number; amount_minor: number; paid_at?: string | null }[] };
type ShareRow = { member_id: number; share_minor: number; covered_minor: number; remaining_minor: number };
const props = defineProps<{ open: boolean; currency: string; currentMemberId: number; expenses: Expense[]; expenseShares: Record<number, ShareRow[]>; members: { id: number; user: { name: string } }[]; settlements: { expense_id: number | null; from_member_id: number; to_member_id: number; amount_minor: number; status: string }[] }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
watch(() => props.open, async open => { await nextTick(); if (open && !dialog.value?.open) dialog.value?.showModal(); else if (!open && dialog.value?.open) dialog.value.close(); }, { immediate: true });

const money = (minor: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: props.currency }).format(minor / 100);
const day = (value: string) => new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${value.slice(0, 10)}T00:00:00Z`));
const name = (id: number) => props.members.find(m => m.id === id)?.user.name.split(' ')[0] ?? 'Traveler';
const counted = computed(() => props.expenses.filter(e => ['open', 'completed', 'closed'].includes(e.status)).slice().sort((a, b) => b.incurred_at.localeCompare(a.incurred_at)));
const mine = (e: Expense) => (props.expenseShares[e.id] ?? []).find(r => r.member_id === props.currentMemberId);

const paid = computed(() => counted.value.map(e => ({
    e, amount: e.payments.filter(p => p.trip_member_id === props.currentMemberId && p.paid_at !== null).reduce((t, p) => t + p.amount_minor, 0), share: mine(e)?.share_minor ?? 0,
})).filter(r => r.amount > 0));
const toPay = computed(() => counted.value.map(e => {
    const row = mine(e); const payers = [...new Set(e.payments.filter(p => p.paid_at !== null && p.trip_member_id !== props.currentMemberId).map(p => name(p.trip_member_id)))];
    const merchantPaid = e.payments.filter(p => p.paid_at !== null).reduce((t, p) => t + p.amount_minor, 0);
    return { e, remaining: row?.remaining_minor ?? 0, share: row?.share_minor ?? 0, to: payers.length ? `to ${payers.join(', ')}` : merchantPaid < e.total_minor ? `to ${e.merchant || 'the merchant'} (not paid yet)` : '' };
}).filter(r => r.remaining > 0));
const totalPaid = computed(() => paid.value.reduce((t, r) => t + r.amount, 0));
const totalToPay = computed(() => toPay.value.reduce((t, r) => t + r.remaining, 0));
const tab = ref<'paid' | 'topay'>('paid');
</script>

<template>
    <dialog ref="dialog" class="my-lines" aria-labelledby="my-lines-title" @cancel.prevent="emit('close')" @click="$event.target === dialog && emit('close')">
        <div v-if="open" class="sheet">
            <header><h2 id="my-lines-title"><Receipt :size="18" aria-hidden="true" /> Your lines</h2><button type="button" class="close" aria-label="Close" @click="emit('close')"><X :size="18" /></button></header>
            <div class="tabs" role="tablist">
                <button type="button" role="tab" :aria-selected="tab === 'paid'" @click="tab = 'paid'">Paid by you<strong>{{ money(totalPaid) }}</strong></button>
                <button type="button" role="tab" :aria-selected="tab === 'topay'" @click="tab = 'topay'">Still to pay<strong>{{ money(totalToPay) }}</strong></button>
            </div>
            <template v-if="tab === 'paid'">
                <p class="muted">Bills you paid the merchant for. Your own share is part of it; the rest is what others owe you for that bill.</p>
                <ul v-if="paid.length" class="lines">
                    <li v-for="r in paid" :key="r.e.id"><span><strong>{{ r.e.title }}</strong><small>{{ day(r.e.incurred_at) }}<template v-if="r.e.merchant"> · {{ r.e.merchant }}</template> · your share {{ money(r.share) }}<template v-if="r.amount > r.share"> · others {{ money(r.amount - r.share) }}</template></small></span><b>{{ money(r.amount) }}</b></li>
                </ul>
                <p v-else class="muted">You haven't paid a merchant yet.</p>
            </template>
            <template v-else>
                <p class="muted">Your shares not covered yet: pay back whoever paid the merchant, or the merchant if nobody has.</p>
                <ul v-if="toPay.length" class="lines">
                    <li v-for="r in toPay" :key="r.e.id"><span><strong>{{ r.e.title }}</strong><small>{{ day(r.e.incurred_at) }} · your share {{ money(r.share) }}<template v-if="r.to"> · {{ r.to }}</template></small></span><b class="owe">{{ money(r.remaining) }}</b></li>
                </ul>
                <p v-else class="muted">Nothing left to pay.</p>
            </template>
        </div>
    </dialog>
</template>

<style scoped>
.my-lines{border:0;padding:0;margin:auto auto 0;width:min(560px,100%);max-height:88dvh;border-radius:22px 22px 0 0;background:#fffefb;color:#172f2a}
.my-lines::backdrop{background:#10221a66}
@media(min-width:640px){.my-lines{margin:auto;border-radius:22px}}
.sheet{display:grid;gap:12px;padding:18px 18px calc(18px + env(safe-area-inset-bottom));max-height:88dvh;overflow:auto}
header{display:flex;justify-content:space-between;align-items:center}
h2{display:flex;align-items:center;gap:6px;font-size:19px;font-weight:800}
.close{display:grid;place-items:center;width:40px;height:40px;border-radius:12px;border:1px solid #dfe6da;background:#fff;cursor:pointer}
.tabs{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.tabs button{display:grid;gap:2px;text-align:left;padding:10px 12px;border-radius:14px;border:1px solid #e0e7dc;background:#fff;font:inherit;font-size:12px;color:#5f6f66;cursor:pointer}
.tabs button strong{font-size:17px;color:#172f2a}
.tabs button[aria-selected="true"]{border-color:#214e3e;background:#eef5e6}
.muted{font-size:12px;color:#6c7c72}
.lines{list-style:none;margin:0;padding:0;display:grid}
.lines li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid #eef2ec}
.lines li span{display:grid;min-width:0}
.lines small{font-size:12px;color:#6c7c72}
.lines b{white-space:nowrap}
.owe{color:#a6573e}
</style>

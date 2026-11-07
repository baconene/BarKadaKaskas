<script setup lang="ts">
import { computed } from 'vue';
import { Banknote, CheckCircle2, Clock3 } from 'lucide-vue-next';

const props = defineProps<{
    sender: string;
    recipient: string;
    amount: string;
    method: string;
    status: string;
    paidAt: string | null;
    confirmedAt: string | null;
    timezone: string;
    expense?: { title: string; category: { name: string }; merchant: string | null; status: string };
    canConfirm: boolean;
}>();
defineEmits<{ confirm: [] }>();
const methods: Record<string, string> = { gcash: 'GCash money transfer', bank_transfer: 'bank transfer', cash: 'cash', other: 'another / unspecified payment method' };
const methodLabel = computed(() => methods[props.method] ?? 'an unspecified payment method');
const excluded = computed(() => props.expense?.status === 'cancelled');
const formatDate = (value: string) => new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeStyle: 'short', timeZone: props.timezone }).format(new Date(value));
</script>

<template>
    <article class="card section-gap payment-history">
        <div class="payment-icon" aria-hidden="true"><Banknote :size="21" /></div>
        <div class="payment-body">
            <p class="payment-sentence">
                <strong>{{ sender }}</strong>
                {{ status === 'pending' ? 'reported paying' : 'paid' }}
                <strong>{{ recipient }}</strong>
                <strong class="payment-amount">{{ amount }}</strong>
                using {{ methodLabel }}.
            </p>
            <p class="payment-purpose" v-if="expense">
                For <strong>{{ expense.category.name }} [{{ expense.title }}]</strong><template v-if="expense.merchant"> on {{ expense.merchant }}</template>
                <span v-if="excluded" class="voided">Expense cancelled — excluded from trip balances</span>
            </p>
            <p v-else class="payment-purpose">General trip reimbursement · not linked to a specific expense</p>
            <div class="payment-meta">
                <span v-if="paidAt">Paid {{ formatDate(paidAt) }}</span>
                <span v-if="status === 'confirmed'" class="confirmation"><CheckCircle2 :size="14" /> Confirmed<template v-if="confirmedAt"> {{ formatDate(confirmedAt) }}</template></span>
                <span v-else-if="status === 'pending'" class="awaiting"><Clock3 :size="14" /> Awaiting {{ recipient }}’s confirmation</span>
                <span v-else>{{ status }}</span>
            </div>
        </div>
        <button v-if="canConfirm" class="btn" @click="$emit('confirm')">Confirm I received {{ amount }}</button>
    </article>
</template>

<style scoped>
.payment-history{display:flex;gap:15px;align-items:flex-start}.payment-icon{display:grid;place-items:center;background:#eff4e8;color:#53754a;border-radius:12px;min-width:42px;height:42px}.payment-body{flex:1;min-width:0}.payment-sentence{font-size:14px;line-height:1.9;color:#40573c;overflow-wrap:anywhere}.payment-sentence strong{font-weight:700;color:#233f30}.payment-amount{white-space:nowrap}.payment-purpose{font-size:13px;color:#65785c;line-height:1.7;margin-top:5px}.payment-meta{display:flex;flex-wrap:wrap;gap:6px 18px;font-size:11px;color:#839079;margin-top:12px;line-height:1.7}.confirmation,.awaiting{display:inline-flex;align-items:center;gap:5px}.confirmation{color:#49734a}.awaiting{color:#976e27}.voided{display:block;color:#a4573d;font-size:12px}.payment-history>.btn{flex-shrink:0;font-size:12px}@media(max-width:700px){.payment-history{flex-wrap:wrap;gap:12px}.payment-history>.btn{margin-left:54px}.payment-sentence{font-size:13px}}
</style>

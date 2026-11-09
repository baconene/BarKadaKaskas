<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { ArrowDownLeft, ArrowUpRight, X } from 'lucide-vue-next';

/**
 * Pop-up behind the "Your balance" card: splits the net into what you will pay (your unpaid shares) and what
 * you will receive (others' unpaid shares on bills you covered), bill by bill. Totals match the server's dues.
 */
type ShareRow = { member_id: number; share_minor: number; covered_minor: number; remaining_minor: number };
type Expense = { id: number; title: string; status: string; total_minor: number; payments: { amount_minor: number; paid_at?: string | null }[] };
const props = defineProps<{
    open: boolean; currency: string; currentMemberId: number; net: number;
    owes: number; owed: number;
    expenses: Expense[]; expenseShares: Record<number, ShareRow[]>; members: { id: number; user: { name: string } }[];
}>();
const emit = defineEmits<{ close: [] }>();

const dialog = ref<HTMLDialogElement>();
watch(() => props.open, async open => {
    await nextTick();
    if (open && !dialog.value?.open) dialog.value?.showModal();
    if (!open && dialog.value?.open) dialog.value.close();
});
const money = (minor: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: props.currency }).format(minor / 100);
const name = (id: number) => props.members.find(m => m.id === id)?.user.name ?? 'Traveler';
const paidToMerchant = (e: Expense) => e.payments.filter(p => p.paid_at !== null).reduce((sum, p) => sum + p.amount_minor, 0);

const lines = computed(() => {
    const pay: { id: number; title: string; amount: number; to: string }[] = [];
    const receive: { id: number; title: string; amount: number; from: { name: string; amount: number }[] }[] = [];
    for (const e of props.expenses) {
        if (!['open', 'completed', 'closed'].includes(e.status)) continue;
        const rows = props.expenseShares[e.id] ?? [];
        const me = rows.find(r => r.member_id === props.currentMemberId);
        if (!me) continue;
        const creditors = rows.filter(r => r.covered_minor > r.share_minor && r.member_id !== me.member_id);
        if (me.remaining_minor > 0) {
            const unpaid = e.total_minor - paidToMerchant(e) > 0;
            const to = creditors.map(c => name(c.member_id));
            pay.push({ id: e.id, title: e.title, amount: me.remaining_minor, to: to.length ? `${to.join(', ')}${unpaid ? ' and the merchant' : ''}` : 'the merchant (not paid yet)' });
        }
        if (me.covered_minor > me.share_minor) {
            // Who owes you is only certain when you are the bill's only creditor.
            const from = creditors.length ? [] : rows.filter(r => r.member_id !== me.member_id && r.remaining_minor > 0).map(r => ({ name: name(r.member_id), amount: r.remaining_minor }));
            receive.push({ id: e.id, title: e.title, amount: me.covered_minor - me.share_minor, from });
        }
    }
    return { pay, receive };
});
</script>

<template>
    <dialog ref="dialog" class="breakdown" aria-labelledby="breakdown-title" @cancel.prevent="emit('close')" @click="$event.target === dialog && emit('close')">
        <div v-if="open" class="sheet">
            <header>
                <div>
                    <p class="eyebrow">Your balance</p>
                    <h2 id="breakdown-title" :class="{ owe: net < 0, receive: net > 0 }">{{ money(net) }}</h2>
                    <p class="muted">{{ net > 0 ? 'Overall, you will receive more than you pay.' : net < 0 ? 'Overall, you will pay more than you receive.' : 'You are even overall.' }}</p>
                </div>
                <button type="button" class="close" aria-label="Close" @click="emit('close')"><X :size="18" /></button>
            </header>

            <section>
                <h3><ArrowUpRight :size="16" aria-hidden="true" class="owe" /> You will pay <strong class="owe">{{ money(owes) }}</strong></h3>
                <p v-if="!lines.pay.length" class="muted">Nothing left to pay.</p>
                <ul v-else>
                    <li v-for="l in lines.pay" :key="l.id"><span><b>{{ l.title }}</b><small>Your share · to {{ l.to }}</small></span><strong>{{ money(l.amount) }}</strong></li>
                </ul>
            </section>

            <section>
                <h3><ArrowDownLeft :size="16" aria-hidden="true" class="receive" /> You will receive <strong class="receive">{{ money(owed) }}</strong></h3>
                <p v-if="!lines.receive.length" class="muted">Nobody owes you right now.</p>
                <ul v-else>
                    <li v-for="l in lines.receive" :key="l.id">
                        <span><b>{{ l.title }}</b><small v-if="l.from.length">{{ l.from.map(f => `${f.name} ${money(f.amount)}`).join(' · ') }}</small><small v-else>Others’ shares of a bill you covered</small></span>
                        <strong>{{ money(l.amount) }}</strong>
                    </li>
                </ul>
            </section>

            <p class="muted foot">Balance = what you will receive − what you will pay. Amounts update when payments are marked as paid.</p>
        </div>
    </dialog>
</template>

<style scoped>
.breakdown{border:0;padding:0;margin:auto auto 0;width:min(560px,100%);max-height:88vh;border-radius:22px 22px 0 0;background:#fff;color:#1d2b24}
.breakdown::backdrop{background:rgba(20,32,26,.45)}
@media(min-width:640px){.breakdown{margin:auto;border-radius:22px}}
.sheet{padding:20px 18px 24px;display:grid;gap:18px;overflow:auto}
header{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}
.eyebrow{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#76846e}
h2{font-size:28px;font-weight:800;letter-spacing:-.6px}
.close{display:inline-flex;align-items:center;justify-content:center;width:36px;height:36px;border-radius:999px;background:#f2f5ed;border:1px solid #e2e8da}
section{border:1px solid #e4ebdf;border-radius:14px;padding:14px}
h3{display:flex;align-items:center;gap:6px;font-size:14px;font-weight:700}
h3 strong{margin-left:auto;font-size:16px}
ul{list-style:none;margin:10px 0 0;padding:0}
li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid #eef2ea;font-size:14px}
li span{display:grid;gap:2px;min-width:0}
li small{font-size:12px;color:#6c7c72}
li strong{white-space:nowrap}
.muted{color:#6c7c72;font-size:13px}
.foot{font-size:12px}
.owe{color:#a6573e}.receive{color:#3d7650}
</style>

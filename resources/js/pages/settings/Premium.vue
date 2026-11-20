<script setup lang="ts">
import { Head } from '@inertiajs/vue3';
import { computed, ref } from 'vue';
import { CalendarCheck, Check, CreditCard, Sparkles } from 'lucide-vue-next';
import AccountLayout from '@/layouts/AccountLayout.vue';
import { appFetch } from '@/lib/uploads';

/**
 * Premium: 30 days of AI features per payment (PayMongo: card, GCash, Maya, GrabPay). AI works on trips you
 * organize; paying again while Premium adds 30 days to the end.
 */
type Status = { active: boolean; owner: boolean; until: string | null; price_label: string; days: number; checkout_available: boolean };
type Payment = { id: number; amount_minor: number; currency_code: string; paid_at: string; period_end: string };
const props = defineProps<{ premium: Status; payments: Payment[]; flash?: 'paid' | 'cancelled' | 'pending' | null }>();
const busy = ref(false);
const error = ref('');
const date = (iso: string | null) => (iso ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(iso)) : '');
const money = (minor: number, currency: string) => new Intl.NumberFormat('en-PH', { style: 'currency', currency }).format(minor / 100);
const daysLeft = computed(() => (props.premium.until ? Math.max(0, Math.ceil((Date.parse(props.premium.until) - Date.now()) / 86400000)) : 0));
const features = ['AI trip planner: whole itineraries from one request', 'Prep for the day: what to wear and bring', 'Tips for each place you visit', 'Receipt scanning for expenses', 'AI budget in the trip presentation', 'AI photo styles and highlight covers'];

async function goPremium() {
    busy.value = true; error.value = '';
    try {
        const { data } = await appFetch<{ data: { checkout_url: string } }>('/premium/checkout', { method: 'POST' });
        window.location.href = data.checkout_url;
    } catch (e) { error.value = e instanceof Error ? e.message : 'Couldn’t start the payment.'; busy.value = false; }
}
</script>

<template>
    <Head title="Premium" />
    <AccountLayout active="premium">
        <p v-if="flash === 'paid'" class="notice ok" role="status"><Check :size="16" aria-hidden="true" /> Payment received. Premium is on, so enjoy the AI features!</p>
        <p v-else-if="flash === 'cancelled'" class="notice" role="status">Payment cancelled. Nothing was charged.</p>
        <p v-else-if="flash === 'pending'" class="notice" role="status">We’re waiting for PayMongo to confirm your payment. Refresh in a minute.</p>

        <section class="card premium" aria-labelledby="premium-title">
            <div class="head">
                <span class="badge-ai"><Sparkles :size="16" aria-hidden="true" /></span>
                <div>
                    <h2 id="premium-title">BarkadaKasKas Premium</h2>
                    <p class="muted">AI features for the trips you organize. Travellers who join your trips don’t need Premium; AI is used by the organizer.</p>
                </div>
            </div>

            <div v-if="premium.active" class="status on">
                <CalendarCheck :size="18" aria-hidden="true" />
                <span v-if="premium.owner"><b>Premium is on</b>: app owner account, always included.</span>
                <span v-else><b>Premium is on</b> until {{ date(premium.until) }} ({{ daysLeft }} {{ daysLeft === 1 ? 'day' : 'days' }} left).</span>
            </div>
            <div v-else class="status">You’re on the free plan. Trips, expenses, photos and maps are all free; AI needs Premium.</div>

            <ul class="features"><li v-for="f in features" :key="f"><Check :size="15" aria-hidden="true" /> {{ f }}</li></ul>

            <div v-if="!premium.owner" class="buy">
                <div><b class="price">{{ premium.price_label }}</b> <span class="muted">for {{ premium.days }} days</span></div>
                <button type="button" class="btn" :disabled="busy || !premium.checkout_available" @click="goPremium"><CreditCard :size="16" aria-hidden="true" /> {{ busy ? 'Opening checkout…' : premium.active ? `Add ${premium.days} more days` : 'Go Premium' }}</button>
                <p class="muted fine">Pay with card, GCash, Maya or GrabPay through PayMongo. It doesn’t renew by itself: pay again when you need more days.</p>
                <p v-if="!premium.checkout_available" class="muted fine">Online payment isn’t set up yet.</p>
                <p v-if="error" class="error" role="alert">{{ error }}</p>
            </div>
        </section>

        <section v-if="payments.length" class="card" aria-labelledby="payments-title">
            <h2 id="payments-title">Payments</h2>
            <ul class="payments">
                <li v-for="p in payments" :key="p.id"><span>{{ date(p.paid_at) }}</span><span class="muted">Premium until {{ date(p.period_end) }}</span><b>{{ money(p.amount_minor, p.currency_code) }}</b></li>
            </ul>
        </section>
    </AccountLayout>
</template>

<style scoped>
.premium{display:grid;gap:16px}
.head{display:flex;gap:14px;align-items:flex-start}
.badge-ai{flex:none;display:grid;place-items:center;width:42px;height:42px;border-radius:12px;background:#fff1e8;color:#c2603a}
.status{padding:12px 14px;border-radius:12px;background:#f3f6ef;font-size:14px;color:#3f5a4b}
.status.on{display:flex;gap:8px;align-items:center;background:#e8f4e4;color:#214e3e}
.features{list-style:none;margin:0;padding:0;display:grid;gap:6px;font-size:14px}
.features li{display:flex;gap:8px;align-items:center}.features svg{color:#3d7650;flex:none}
.buy{display:grid;gap:8px;justify-items:start;padding-top:12px;border-top:1px solid #e8ede3}
.price{font-size:26px}
.fine{font-size:12px;margin:0}
.notice{display:flex;gap:8px;align-items:center;padding:12px 14px;border-radius:12px;background:#fff6e0;color:#7a5a12;font-size:14px}
.notice.ok{background:#e8f4e4;color:#214e3e}
.error{color:#a6573e;font-size:13px;margin:0}
.payments{list-style:none;margin:10px 0 0;padding:0;display:grid}
.payments li{display:grid;grid-template-columns:auto 1fr auto;gap:10px;padding:10px 0;border-bottom:1px solid #eef2ea;font-size:14px}
</style>

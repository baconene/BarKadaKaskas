<script setup lang="ts">
import { Head, router, usePage } from '@inertiajs/vue3';
import { computed, ref } from 'vue';
import { CalendarCheck, Check, CreditCard, Lock, Sparkles } from 'lucide-vue-next';
import AccountLayout from '@/layouts/AccountLayout.vue';
import { appFetch } from '@/lib/uploads';

/**
 * Premium. Two ways to pay:
 * - Subscription: save a card, get the free trial once, then the monthly price every 30 days until cancelled.
 *   The card goes straight from this page to PayMongo (public key); our server only sees the payment method id.
 * - 30 days at a time with GCash, Maya, GrabPay or card through PayMongo Checkout; no automatic renewal.
 */
type Allowance = { label: string; limit: number | null; used: number; remaining: number | null };
type Status = {
    active: boolean; owner: boolean; until: string | null; price_label: string; days: number; checkout_available: boolean;
    subscription_available: boolean; trial: boolean; trial_available: boolean; trial_days: number; hold_label: string; billing_status: string; auto_renew: boolean;
    card: string | null; plan: 'free' | 'trial' | 'premium'; usage: Record<string, Allowance>;
};
type Payment = { id: number; amount_minor: number; currency_code: string; paid_at: string; period_end: string };
const props = defineProps<{ premium: Status; payments: Payment[]; flash?: string | null; flashMessage?: string | null }>();
const user = computed(() => (usePage().props.auth as { user?: { name: string; email: string } } | undefined)?.user);
const busy = ref(false);
const error = ref('');
const date = (iso: string | null) => (iso ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(iso)) : '');
const dateTime = (iso: string | null) => (iso ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso)) : '');
const money = (minor: number, currency: string) => new Intl.NumberFormat('en-PH', { style: 'currency', currency }).format(minor / 100);
const daysLeft = computed(() => (props.premium.until ? Math.max(0, Math.ceil((Date.parse(props.premium.until) - Date.now()) / 86400000)) : 0));
const features = ['AI trip planner: whole itineraries from one request', 'Prep for the day: what to wear and bring', 'Tips for each place you visit', 'Receipt scanning for expenses', 'AI budget in the trip presentation', 'AI photo styles and highlight covers', 'More in-app navigation and place photos'];
const subscribed = computed(() => props.premium.auto_renew && props.premium.active);
const trialLabel = computed(() => (props.premium.trial_days === 1 ? '1-day' : `${props.premium.trial_days}-day`));
const meters = computed(() => Object.entries(props.premium.usage ?? {}).filter(([, a]) => a.limit !== 0));

// ---- Card form (subscription / trial) -----------------------------------------------------------------------
const showCard = ref(false);
const card = ref({ name: user.value?.name ?? '', number: '', month: '', year: '', cvc: '' });
const accepted = ref(false);

async function subscribe() {
    error.value = '';
    if (!accepted.value) { error.value = 'Please accept the Terms of Service and Subscription Terms.'; return; }
    const number = card.value.number.replace(/\D/g, '');
    const month = Number(card.value.month); const year = Number(card.value.year.length === 2 ? `20${card.value.year}` : card.value.year);
    if (number.length < 13 || !month || month > 12 || !year || card.value.cvc.replace(/\D/g, '').length < 3) { error.value = 'Check the card number, expiry and security code.'; return; }
    busy.value = true;
    try {
        const form = new FormData(); form.append('accept_terms', '1');
        const { data: setup } = await appFetch<{ data: { payment_id: number; public_key: string } }>('/premium/subscribe', { method: 'POST', body: form });
        // The card goes to PayMongo directly; only the resulting payment method id comes back to us.
        const created = await fetch('https://api.paymongo.com/v1/payment_methods', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: `Basic ${btoa(`${setup.public_key}:`)}` },
            body: JSON.stringify({ data: { attributes: { type: 'card', details: { card_number: number, exp_month: month, exp_year: year, cvc: card.value.cvc.replace(/\D/g, '') }, billing: { name: card.value.name || user.value?.name, email: user.value?.email } } } }),
        });
        const method = await created.json().catch(() => ({}));
        if (!created.ok || !method?.data?.id) throw new Error(method?.errors?.[0]?.detail ?? 'PayMongo couldn’t read that card.');
        const attach = new FormData(); attach.append('payment_method_id', method.data.id);
        const { data } = await appFetch<{ data: { redirect?: string; ok?: boolean; message?: string } }>(`/premium/subscribe/${setup.payment_id}/card`, { method: 'POST', body: attach });
        // 3-D Secure: the bank's page, which comes back to /premium/subscribe/{id}/return.
        if (data.redirect) { window.location.href = data.redirect; return; }
        card.value = { ...card.value, number: '', cvc: '' };
        router.visit('/settings/premium', { preserveScroll: true });
    } catch (e) {
        error.value = e instanceof Error ? e.message : 'Couldn’t save the card.';
    } finally { busy.value = false; }
}

async function cancel() {
    if (!window.confirm(props.premium.trial ? 'Cancel the free trial? Premium stays on until the trial ends and your card won’t be charged.' : 'Cancel your subscription? Premium stays on until the end of the month you paid for, and you won’t be charged again.')) return;
    router.post('/premium/cancel', {}, { preserveScroll: true });
}

async function payOnce() {
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
        <p v-else-if="flash && flashMessage" class="notice" :class="{ ok: ['trial', 'subscribed', 'canceled'].includes(flash) }" role="status">{{ flashMessage }}</p>

        <section class="card premium" aria-labelledby="premium-title">
            <div class="head">
                <span class="badge-ai"><Sparkles :size="16" aria-hidden="true" /></span>
                <div>
                    <h2 id="premium-title">BarkadaKasKas Premium</h2>
                    <p class="muted">AI features for the trips you organize, plus more in-app navigation and place photos. Travellers who join your trips don’t need Premium.</p>
                </div>
            </div>

            <div v-if="premium.active" class="status on">
                <CalendarCheck :size="18" aria-hidden="true" />
                <span v-if="premium.owner"><b>Premium is on</b>: app owner account, always included.</span>
                <span v-else-if="premium.trial && premium.auto_renew"><b>Free trial</b> until {{ dateTime(premium.until) }}. Then {{ premium.price_label }} every 30 days on {{ premium.card ?? 'your card' }} until you cancel.</span>
                <span v-else-if="premium.trial"><b>Free trial</b> until {{ dateTime(premium.until) }}. Cancelled: you won’t be charged.</span>
                <span v-else-if="subscribed"><b>Subscribed.</b> Renews {{ date(premium.until) }} for {{ premium.price_label }} on {{ premium.card ?? 'your card' }}.</span>
                <span v-else><b>Premium is on</b> until {{ date(premium.until) }} ({{ daysLeft }} {{ daysLeft === 1 ? 'day' : 'days' }} left). It won’t renew by itself.</span>
            </div>
            <div v-else-if="premium.billing_status === 'past_due'" class="status warn">Your last renewal didn’t go through. We’ll retry your card; you can also pay for 30 days below.</div>
            <div v-else class="status">You’re on the free plan. Trips, expenses, photos and maps are free; AI needs Premium.</div>

            <ul class="features"><li v-for="f in features" :key="f"><Check :size="15" aria-hidden="true" /> {{ f }}</li></ul>

            <div v-if="!premium.owner" class="buy">
                <div><b class="price">{{ premium.price_label }}</b> <span class="muted">every 30 days</span></div>

                <template v-if="premium.subscription_available && !subscribed">
                    <button v-if="!showCard" type="button" class="btn" @click="showCard = true"><CreditCard :size="16" aria-hidden="true" /> {{ premium.trial_available ? `Start ${trialLabel} free trial` : 'Subscribe with a card' }}</button>
                    <form v-else class="card-form" @submit.prevent="subscribe">
                        <p class="disclose">
                            <template v-if="premium.trial_available"><b>Free for {{ premium.trial_days === 1 ? '1 day' : `${premium.trial_days} days` }}, then {{ premium.price_label }} every 30 days</b> until you cancel. Cancel before the trial ends and you pay nothing.</template>
                            <template v-else><b>{{ premium.price_label }} now, then every 30 days</b> until you cancel.</template>
                            We check your card with a temporary {{ premium.hold_label }} hold that is released right away; you aren’t charged for it.
                        </p>
                        <label>Name on card<input v-model="card.name" autocomplete="cc-name" required maxlength="80" /></label>
                        <label>Card number<input v-model="card.number" inputmode="numeric" autocomplete="cc-number" required maxlength="23" placeholder="4343 4343 4343 4345" /></label>
                        <div class="row">
                            <label>Month<input v-model="card.month" inputmode="numeric" autocomplete="cc-exp-month" required maxlength="2" placeholder="MM" /></label>
                            <label>Year<input v-model="card.year" inputmode="numeric" autocomplete="cc-exp-year" required maxlength="4" placeholder="YY" /></label>
                            <label>CVC<input v-model="card.cvc" inputmode="numeric" autocomplete="cc-csc" required maxlength="4" /></label>
                        </div>
                        <label class="check"><input v-model="accepted" type="checkbox" /> <span>I agree to the <a href="/terms" target="_blank" rel="noopener">Terms of Service</a>, the <a href="/subscription-terms" target="_blank" rel="noopener">Subscription Terms</a> (automatic renewal) and the <a href="/privacy" target="_blank" rel="noopener">Privacy Policy</a>.</span></label>
                        <button type="submit" class="btn" :disabled="busy"><Lock :size="15" aria-hidden="true" /> {{ busy ? 'Checking your card…' : premium.trial_available ? 'Start free trial' : `Subscribe for ${premium.price_label}` }}</button>
                        <p class="muted fine">Card details are sent securely to PayMongo, our payment processor; BarkadaKasKas never sees or stores your card number.</p>
                    </form>
                </template>
                <button v-if="subscribed" type="button" class="btn secondary" @click="cancel">{{ premium.trial ? 'Cancel free trial' : 'Cancel subscription' }}</button>

                <div class="once">
                    <button type="button" class="btn secondary" :disabled="busy || !premium.checkout_available" @click="payOnce">{{ premium.active ? `Add ${premium.days} days (one-time)` : `Pay for ${premium.days} days (one-time)` }}</button>
                    <p class="muted fine">One-time payment with GCash, Maya, GrabPay or card through PayMongo. Doesn’t renew by itself.</p>
                </div>
                <p v-if="!premium.checkout_available" class="muted fine">Online payment isn’t set up yet.</p>
                <p v-if="error" class="error" role="alert">{{ error }}</p>
            </div>
        </section>

        <section class="card" aria-labelledby="usage-title">
            <h2 id="usage-title">This month’s allowance <span class="muted small">({{ premium.plan === 'trial' ? 'free trial' : premium.plan === 'premium' ? 'Premium' : 'free plan' }})</span></h2>
            <ul class="meters">
                <li v-for="[key, a] in meters" :key="key">
                    <span>{{ a.label }}</span>
                    <span class="muted">{{ a.limit === null ? 'Unlimited' : `${a.used} of ${a.limit} used` }}</span>
                    <span class="bar" aria-hidden="true"><span :style="{ width: a.limit ? `${Math.min(100, (a.used / a.limit) * 100)}%` : '0%' }" /></span>
                </li>
            </ul>
            <p class="muted fine">Allowances reset on the 1st of each month (Manila time). When in-app navigation runs out, routes open in Google Maps instead.</p>
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
.status.warn{background:#fff6e0;color:#7a5a12}
.features{list-style:none;margin:0;padding:0;display:grid;gap:6px;font-size:14px}
.features li{display:flex;gap:8px;align-items:center}.features svg{color:#3d7650;flex:none}
.buy{display:grid;gap:10px;justify-items:start;padding-top:12px;border-top:1px solid #e8ede3}
.price{font-size:26px}
.fine{font-size:12px;margin:0}
.small{font-size:13px;font-weight:500}
.card-form{display:grid;gap:10px;width:100%;max-width:420px}
.card-form label{display:grid;gap:4px;font-size:13px;font-weight:600;color:#3f5a4b}
.card-form input:not([type=checkbox]){min-height:42px;padding:8px 12px;border:1px solid #d8e0d2;border-radius:10px;font:inherit;font-weight:400}
.card-form .row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}
.card-form .check{display:flex;gap:8px;align-items:flex-start;font-weight:400}
.card-form .check input{margin-top:3px}
.card-form a{color:#214e3e;text-decoration:underline}
.disclose{margin:0;padding:10px 12px;border-radius:10px;background:#f3f6ef;font-size:13px;line-height:1.45}
.once{display:grid;gap:4px;padding-top:10px;border-top:1px dashed #e0e7da;width:100%}
.notice{display:flex;gap:8px;align-items:center;padding:12px 14px;border-radius:12px;background:#fff6e0;color:#7a5a12;font-size:14px}
.notice.ok{background:#e8f4e4;color:#214e3e}
.error{color:#a6573e;font-size:13px;margin:0}
.meters{list-style:none;margin:10px 0;padding:0;display:grid;gap:10px}
.meters li{display:grid;grid-template-columns:1fr auto;gap:4px 10px;font-size:14px}
.meters .bar{grid-column:1/-1;height:6px;border-radius:99px;background:#eef2ea;overflow:hidden}
.meters .bar span{display:block;height:100%;background:#3d7650}
.payments{list-style:none;margin:10px 0 0;padding:0;display:grid}
.payments li{display:grid;grid-template-columns:auto 1fr auto;gap:10px;padding:10px 0;border-bottom:1px solid #eef2ea;font-size:14px}
</style>

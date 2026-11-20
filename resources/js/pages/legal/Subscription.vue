<script setup lang="ts">
import { computed } from 'vue';
import LegalPage from '@/components/legal/LegalPage.vue';

type Allowances = Record<'ai_plan' | 'receipt_scan' | 'ai_image' | 'ai_insight' | 'navigation' | 'place_photos', number>;
const props = defineProps<{ plans: { price_label: string; hold_label: string; days: number; trial_days: number; free: Allowances; premium: Allowances; trial: Allowances }; legal: { operator: string; contact_email: string | null; effective: string } }>();
const trial = computed(() => (props.plans.trial_days === 1 ? '1 day (24 hours)' : `${props.plans.trial_days} days`));
const rows = computed(() => [
    ['AI trip planner requests', 'ai_plan'], ['AI receipt scans', 'receipt_scan'], ['AI place tips, day prep and budget estimates', 'ai_insight'],
    ['AI photo styles and highlight covers', 'ai_image'], ['In-app turn-by-turn navigations', 'navigation'], ['Place photo lookups', 'place_photos'],
] as const);
</script>

<template>
    <LegalPage title="Subscription Terms" :effective="legal.effective" current="subscription">
        <p>These Subscription Terms apply when you buy BarkadaKasKas Premium. They are part of the <a href="/terms">Terms of Service</a>. Please read them before starting a free trial or subscription.</p>

        <div class="box">
            <p><b>In short:</b> Premium costs <b>{{ plans.price_label }} every {{ plans.days }} days</b>. The free trial lasts <b>{{ trial }}</b> and needs a card. Unless you cancel before the trial ends, your card is charged {{ plans.price_label }} when it ends and again every {{ plans.days }} days until you cancel. Cancel any time in <b>Settings → Premium</b>.</p>
        </div>

        <h2>1. Plans and prices</h2>
        <ul>
            <li><b>Free plan</b>: no charge. Includes trips, itineraries, shared expenses, maps and photo posts, with the monthly limits below.</li>
            <li><b>Premium subscription</b>: {{ plans.price_label }} for each {{ plans.days }}-day period, charged to your saved card automatically at the start of each period.</li>
            <li><b>One-time Premium</b>: {{ plans.price_label }} for {{ plans.days }} days, paid by GCash, Maya, GrabPay or card through PayMongo Checkout. It does not renew; buy again when you need more time.</li>
        </ul>
        <p>Prices are in Philippine pesos and include any taxes we are required to charge. We may change the price for future periods; we will tell you by e-mail at least 7 days before a new price applies to your subscription, and you can cancel before then.</p>

        <h2>2. Free trial</h2>
        <ul>
            <li>The free trial lasts {{ trial }} from when it starts and is available <b>once per person</b>. We may refuse or end a trial if we see it being used more than once (for example with several accounts).</li>
            <li>To start it you must save a valid credit or debit card. We check the card with a temporary authorization hold of {{ plans.hold_label }}, which we release immediately; you are not charged for it. Your bank may take a few days to remove the hold from your statement.</li>
            <li>Trial allowances are smaller than the full Premium allowances (see section 5).</li>
            <li><b>If you do not cancel before the trial ends, your subscription starts automatically and your card is charged {{ plans.price_label }}.</b> We e-mail you when the trial starts, with the exact time it ends.</li>
        </ul>

        <h2>3. Automatic renewal</h2>
        <p>Your subscription renews automatically every {{ plans.days }} days, and we charge your saved card {{ plans.price_label }} (or the price we have told you about under section 1) at each renewal, until you cancel. We e-mail a confirmation after each charge.</p>
        <p>If a charge fails, your Premium features stop at the end of the paid period, we e-mail you, and we try the card again about once a day. After 3 failed attempts we cancel the subscription and your account moves to the free plan. If your bank needs you to confirm a charge, we send you a link to do so.</p>

        <h2>4. Cancelling</h2>
        <ul>
            <li>Cancel any time in <b>Settings → Premium</b> on the website. Cancelling takes effect immediately for future charges; we also remove the saved card from your subscription.</li>
            <li>After cancelling, Premium stays on until the end of the trial or period you already started, then your account moves to the free plan. Your trips and data are not deleted.</li>
            <li>Deleting your account also cancels your subscription.</li>
        </ul>

        <h2>5. Monthly allowances</h2>
        <p>Some features use paid Google services (AI and Maps) each time they are used, so each plan includes a monthly allowance. Allowances reset on the 1st of each month (Philippine time) and unused amounts do not carry over. When an allowance runs out, that feature pauses until the next month (in-app navigation opens Google Maps instead).</p>
        <table>
            <thead><tr><th>Feature (per month)</th><th>Free</th><th>Trial</th><th>Premium</th></tr></thead>
            <tbody><tr v-for="[label, key] in rows" :key="key"><td>{{ label }}</td><td>{{ plans.free[key] || '—' }}</td><td>{{ plans.trial[key] }}</td><td>{{ plans.premium[key] }}</td></tr></tbody>
        </table>
        <p>We may change allowances for future months; if we reduce a Premium allowance we will tell you at least 7 days before.</p>

        <h2>6. Refunds</h2>
        <p>Except where the law requires otherwise, payments are not refundable and we do not give refunds or credits for partly used periods or unused allowances. If you were charged by mistake (for example, charged after cancelling, or charged twice), contact us within 30 days and we will refund the charge. Nothing in these terms limits your rights under the Consumer Act of the Philippines (Republic Act No. 7394) or other applicable law.</p>

        <h2>7. Payments and your card</h2>
        <p>Payments are processed by <b>PayMongo Philippines, Inc.</b>, a payment processor regulated by the Bangko Sentral ng Pilipinas. Your card details go directly from your browser to PayMongo; BarkadaKasKas never receives or stores your full card number or security code. We keep only a reference to the saved card, its brand and last four digits. PayMongo’s own terms and privacy policy also apply to your payment.</p>
        <p>By starting a trial or subscription you authorize us, through PayMongo, to charge your saved card as described in these terms until you cancel.</p>

        <h2>8. Contact</h2>
        <p>Questions about billing: {{ legal.contact_email ? '' : 'use the contact details in the app.' }}<a v-if="legal.contact_email" :href="`mailto:${legal.contact_email}`">{{ legal.contact_email }}</a>. Service provided by {{ legal.operator }}.</p>
    </LegalPage>
</template>

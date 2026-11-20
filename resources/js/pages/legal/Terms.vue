<script setup lang="ts">
import LegalPage from '@/components/legal/LegalPage.vue';

defineProps<{ plans: { price_label: string; days: number; trial_days: number }; legal: { operator: string; contact_email: string | null; effective: string } }>();
</script>

<template>
    <LegalPage title="Terms of Service" :effective="legal.effective" current="terms">
        <p>These Terms of Service (“Terms”) govern your use of BarkadaKasKas: the website, the Android and iPhone apps and related services (the “Service”), provided by {{ legal.operator }} (“we”, “us”). By creating an account or using the Service you agree to these Terms, the <a href="/subscription-terms">Subscription Terms</a> (if you buy Premium) and our <a href="/privacy">Privacy Policy</a>. If you do not agree, do not use the Service.</p>

        <h2>1. Your account</h2>
        <ul>
            <li>You must be at least 18 years old, or have a parent’s or guardian’s permission, to create an account.</li>
            <li>Give accurate information and keep your password secret. You are responsible for what happens under your account.</li>
            <li>Tell us promptly if you think someone else is using your account.</li>
        </ul>

        <h2>2. What the Service does</h2>
        <p>BarkadaKasKas helps groups plan trips: itineraries, maps, shared expenses and settling up, photo posts and travel documents. Expense splits and balances are a record kept by the group; <b>we do not hold, send or collect money between travellers</b>, and we are not a party to any arrangement between trip members.</p>

        <h2>3. Plans and payment</h2>
        <p>The free plan is free. Premium costs {{ plans.price_label }} every {{ plans.days }} days and can start with a {{ plans.trial_days === 1 ? '1-day' : `${plans.trial_days}-day` }} free trial that renews into a paid subscription unless you cancel. Free and paid plans include monthly allowances for features that use paid third-party services. The full rules on trials, automatic renewal, cancellation, allowances and refunds are in the <a href="/subscription-terms">Subscription Terms</a>.</p>

        <h2>4. AI features and third-party services</h2>
        <ul>
            <li>AI features (trip plans, tips, day prep, budget estimates, receipt reading and photo edits) are produced by Google’s AI models. <b>AI output can be wrong or out of date.</b> Check opening hours, prices, safety, travel rules and receipt amounts yourself before relying on them.</li>
            <li>Maps, routes, navigation, place search and place photos come from Google Maps Platform and Wikimedia Commons. Your use of them is also subject to the <a href="https://maps.google.com/help/terms_maps/" target="_blank" rel="noopener">Google Maps Terms of Service</a>.</li>
            <li>In-app turn-by-turn navigation is a guide only. Obey traffic laws and road signs and pay attention to your surroundings; do not interact with the app while driving.</li>
        </ul>

        <h2>5. Your content</h2>
        <p>You keep ownership of what you add (trips, photos, captions, receipts, documents). You give us permission to store, process and show it to the people you share it with, and to send it to the service providers that power the features you use (for example, a receipt photo to Google’s AI when you scan it), only as needed to run the Service. You are responsible for having the right to share what you upload, including photos of other people.</p>

        <h2>6. Acceptable use</h2>
        <p>Do not use the Service to break the law; harass, defraud or impersonate others; upload malware or content you have no right to share; scrape, overload or try to break into the Service; get around allowances or the one-trial-per-person rule; or resell the Service. We may suspend or close accounts that do.</p>

        <h2>7. Availability and changes</h2>
        <p>We work to keep the Service running but do not promise it will always be available or error-free. We may change or stop features. If we make a material change to these Terms we will post the new version here and, for significant changes, tell you in the app or by e-mail before it applies. Continuing to use the Service after that means you accept the changes.</p>

        <h2>8. Ending your use</h2>
        <p>You can stop using the Service and delete your account at any time in Settings → Profile; this also cancels any subscription. We may suspend or end your access if you seriously or repeatedly break these Terms, with notice where reasonable.</p>

        <h2>9. Disclaimers and liability</h2>
        <p>The Service is provided “as is”. To the extent the law allows, we are not liable for indirect or consequential losses, for losses caused by relying on AI output, maps or other travellers’ entries, or for disputes between trip members. Our total liability to you for any claim is limited to the amount you paid us in the 3 months before the claim. Nothing in these Terms excludes liability that cannot be excluded under Philippine law, including your rights as a consumer.</p>

        <h2>10. Governing law</h2>
        <p>These Terms are governed by the laws of the Republic of the Philippines. Before going to court, please contact us so we can try to resolve the issue. Any court case will be brought in the proper courts of the Philippines.</p>

        <h2>11. Contact</h2>
        <p>{{ legal.operator }}<template v-if="legal.contact_email"> · <a :href="`mailto:${legal.contact_email}`">{{ legal.contact_email }}</a></template></p>
    </LegalPage>
</template>

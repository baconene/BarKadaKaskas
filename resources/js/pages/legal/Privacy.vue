<script setup lang="ts">
import LegalPage from '@/components/legal/LegalPage.vue';

defineProps<{ legal: { operator: string; contact_email: string | null; effective: string } }>();
</script>

<template>
    <LegalPage title="Privacy Policy" :effective="legal.effective" current="privacy">
        <p>This policy explains what personal information BarkadaKasKas ({{ legal.operator }}, “we”) collects, why, who we share it with and your rights. We process personal information in line with the <b>Data Privacy Act of 2012 (Republic Act No. 10173)</b> and its rules.</p>

        <h2>1. What we collect</h2>
        <ul>
            <li><b>Account</b>: name, e-mail, password (stored hashed), and optional phone, other contact, emergency contact and profile photo.</li>
            <li><b>Trip content</b>: trips, itinerary stops and locations, expenses and receipts, settlements, photo and video posts (which may include where they were taken), comments and travel documents you upload.</li>
            <li><b>Location</b>: your device’s location, only while you use the map or in-app navigation and only if you allow it. We do not keep a history of your location.</li>
            <li><b>Billing</b>: your PayMongo customer and saved-card reference, card brand and last four digits, payment history and subscription status. We never receive your full card number or security code.</li>
            <li><b>Usage</b>: how many times this month you used features with allowances (for example AI plans or navigations), plus basic technical logs (IP address, device and errors) to keep the Service secure and working.</li>
        </ul>

        <h2>2. Why we use it</h2>
        <ul>
            <li>To provide the Service you asked for: share trips with your group, split expenses, show maps and run the features you choose (contract).</li>
            <li>To take payments, run your subscription and send required notices about trials, charges and cancellation (contract and legal obligation).</li>
            <li>To enforce allowances and the one-trial-per-person rule, prevent fraud and keep the Service secure (legitimate interest).</li>
            <li>To meet legal, tax and accounting duties (legal obligation).</li>
        </ul>
        <p>We do not sell your personal information and we do not use it for advertising.</p>

        <h2>3. Who we share it with</h2>
        <ul>
            <li><b>Your trip members</b> see what you add to a shared trip (and documents you choose to share).</li>
            <li><b>Google Cloud (Vertex AI, Google Maps Platform)</b>: when you use AI features, the relevant trip details, receipt or photo are sent to Google to produce the answer; maps, routes, navigation and place lookups send locations and place names to Google.</li>
            <li><b>PayMongo Philippines, Inc.</b> processes payments and stores your card.</li>
            <li><b>Wikimedia Commons</b> receives place names when we look up free place photos.</li>
            <li><b>E-mail and hosting providers</b> that send our e-mails and run our servers.</li>
            <li>Authorities, when the law requires it.</li>
        </ul>
        <p>Some of these providers process data outside the Philippines; we use providers that protect it with appropriate safeguards.</p>

        <h2>4. How long we keep it</h2>
        <p>We keep your account and trip content while your account is open. When you delete your account we delete or anonymize your personal information within 30 days, except payment records, which we keep as long as tax and accounting rules require (generally up to 10 years). Receipt photos that were scanned but never saved as an expense are deleted automatically.</p>

        <h2>5. Your rights</h2>
        <p>Under the Data Privacy Act you have the right to be informed, to access, to correct, to object, to erasure or blocking, to data portability, to damages and to file a complaint with the <b>National Privacy Commission</b> (privacy.gov.ph). You can correct most information and delete your account yourself in Settings; for anything else, contact us.</p>

        <h2>6. Security</h2>
        <p>We protect your information with encryption in transit, hashed passwords, access controls and an encrypted login token in the mobile apps. No system is perfectly secure; if a breach affects you we will notify you and the National Privacy Commission as the law requires.</p>

        <h2>7. Children</h2>
        <p>The Service is not meant for children under 18 without a parent’s or guardian’s permission.</p>

        <h2>8. Changes and contact</h2>
        <p>We will post changes to this policy here and tell you about significant ones before they apply. Questions or requests about your data: {{ legal.operator }}<template v-if="legal.contact_email"> · <a :href="`mailto:${legal.contact_email}`">{{ legal.contact_email }}</a></template>.</p>
    </LegalPage>
</template>

<script setup lang="ts">
import { Link } from '@inertiajs/vue3';
import { Compass, Map, ReceiptText, Wallet } from 'lucide-vue-next';

defineProps<{
    title?: string;
    description?: string;
    eyebrow?: string;
}>();

const steps = [
    { icon: Map, title: 'One trip. Everyone in.', text: 'Keep the itinerary and your friends in the same place.' },
    { icon: ReceiptText, title: 'Your order. Your share.', text: 'Split the whole bill or assign each item to its people.' },
    { icon: Wallet, title: 'Clear balances. Happy friends.', text: 'Track payments, close expenses, and get back to the good part.' },
];
</script>

<template>
    <div class="travel-app auth-page">
        <aside class="auth-story" aria-hidden="true">
            <Link href="/" class="brand" tabindex="-1"><Compass :size="27" /> BarKada<span>Kaskas</span></Link>
            <div>
                <p class="eyebrow">For the trips that become stories</p>
                <h2>More memories.<br />Less “who owes who?”</h2>
                <ul class="auth-steps">
                    <li v-for="step in steps" :key="step.title">
                        <span class="step-icon"><component :is="step.icon" :size="19" /></span>
                        <div>
                            <strong>{{ step.title }}</strong>
                            <p>{{ step.text }}</p>
                        </div>
                    </li>
                </ul>
            </div>
            <footer>Good trips. Great company. Fair shares.</footer>
        </aside>

        <main class="auth-main">
            <Link href="/" class="brand auth-mobile-brand"><Compass :size="25" /> BarKada<span>Kaskas</span></Link>
            <section class="card auth-card">
                <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
                <h1>{{ title }}</h1>
                <p v-if="description" class="muted">{{ description }}</p>
                <slot />
            </section>
            <p class="auth-legal">Made for going places, together.</p>
        </main>
    </div>
</template>

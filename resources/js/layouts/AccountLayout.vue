<script setup lang="ts">
import { Link } from '@inertiajs/vue3';
import { KeyRound, UserRound } from 'lucide-vue-next';
import TravelLayout from '@/layouts/TravelLayout.vue';

/** Account settings inside the app's own layout, with tabs for profile and password. */
defineProps<{ active: 'profile' | 'password' }>();
const tabs = [
    { key: 'profile', label: 'Profile', href: '/settings/profile', icon: UserRound },
    { key: 'password', label: 'Password', href: '/settings/password', icon: KeyRound },
] as const;
</script>

<template>
    <TravelLayout>
        <div class="account">
            <div class="account-head">
                <p class="eyebrow">Account</p>
                <h1>Your settings</h1>
            </div>
            <nav class="account-tabs" aria-label="Account sections">
                <Link v-for="tab in tabs" :key="tab.key" :href="tab.href" class="account-tab" :aria-current="active === tab.key ? 'page' : undefined"><component :is="tab.icon" :size="16" aria-hidden="true" /> {{ tab.label }}</Link>
            </nav>
            <slot />
        </div>
    </TravelLayout>
</template>

<style scoped>
.account{display:grid;gap:18px;max-width:680px;margin:0 auto}
.account-head h1{font-size:clamp(28px,4vw,38px)}
.account-tabs{display:flex;gap:6px;padding:5px;border-radius:14px;background:#edf2e6;width:max-content;max-width:100%}
.account-tab{display:inline-flex;align-items:center;gap:7px;padding:9px 16px;border-radius:10px;font-size:14px;font-weight:650;color:#507044}
.account-tab[aria-current="page"]{background:#fff;color:#214e3e;box-shadow:0 1px 4px #233d2614}
</style>

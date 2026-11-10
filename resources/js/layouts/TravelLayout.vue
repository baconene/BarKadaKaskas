<script setup lang="ts">
import AlertsBell from '@/components/AlertsBell.vue';
import { Link, usePage } from '@inertiajs/vue3';
import { Compass, LogOut } from 'lucide-vue-next';
import { computed } from 'vue';
// appNav: the page renders its own bottom navigation on small screens.
defineProps<{ appNav?: boolean }>();
const page = usePage();
const me = computed(() => (page.props as { auth?: { user?: { name: string; avatar_url?: string | null } } }).auth?.user);
const initials = computed(() => (me.value?.name ?? '').split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]!.toUpperCase()).join(''));
</script>
<template>
  <div class="travel-app" :class="{ 'has-app-nav': appNav }">
    <header class="travel-header"><Link href="/dashboard" class="brand"><Compass :size="27" /> BarKada<span>Kaskas</span></Link><nav><Link href="/dashboard">My trips</Link><AlertsBell v-if="me" /><Link href="/settings/profile" class="account-link" aria-label="Account settings"><img v-if="me?.avatar_url" :src="me.avatar_url" alt="" class="me" /><span v-else-if="me" class="me initials" aria-hidden="true">{{ initials }}</span><span class="account-label">Account</span></Link><Link href="/logout" method="post" as="button" aria-label="Log out"><LogOut :size="18" /></Link></nav></header>
    <main class="travel-main"><div v-if="Object.keys(page.props.errors || {}).length" role="alert" class="error-box"><p v-for="(error, key) in page.props.errors" :key="key">{{ error }}</p></div><slot /></main>
    <footer class="travel-footer">Good trips. Great company. Fair shares.</footer>
  </div>
</template>

<style scoped>
.account-link{display:inline-flex;align-items:center;gap:8px}
.me{width:30px;height:30px;border-radius:50%;object-fit:cover;flex-shrink:0}
.me.initials{display:grid;place-items:center;background:#e3edd5;color:#214e3e;font-size:11px;font-weight:800}
@media(max-width:640px){.account-label{display:none}}
</style>

<script setup lang="ts">
import { Link, usePage } from '@inertiajs/vue3';
import { Compass, LogOut } from 'lucide-vue-next';
// appNav: the page renders its own bottom navigation on small screens.
defineProps<{ appNav?: boolean }>();
const page = usePage();
</script>
<template>
  <div class="travel-app" :class="{ 'has-app-nav': appNav }">
    <header class="travel-header"><Link href="/dashboard" class="brand"><Compass :size="27" /> BarKada<span>Kaskas</span></Link><nav><Link href="/dashboard">My trips</Link><Link href="/settings/profile">Account</Link><Link href="/logout" method="post" as="button" aria-label="Log out"><LogOut :size="18" /></Link></nav></header>
    <main class="travel-main"><div v-if="Object.keys(page.props.errors || {}).length" role="alert" class="error-box"><p v-for="(error, key) in page.props.errors" :key="key">{{ error }}</p></div><slot /></main>
    <footer class="travel-footer">Good trips. Great company. Fair shares.</footer>
  </div>
</template>

<script setup lang="ts">
import { Head } from '@inertiajs/vue3';
import { Compass } from 'lucide-vue-next';

/** Shared frame for the legal pages: header, title, effective date, the text, and links between the pages. */
defineProps<{ title: string; effective: string; current: 'terms' | 'subscription' | 'privacy' }>();
const pages = [
    { key: 'terms', href: '/terms', label: 'Terms of Service' },
    { key: 'subscription', href: '/subscription-terms', label: 'Subscription Terms' },
    { key: 'privacy', href: '/privacy', label: 'Privacy Policy' },
] as const;
</script>

<template>
    <Head :title="title" />
    <div class="legal">
        <header class="legal-header">
            <a href="/" class="brand"><Compass :size="22" aria-hidden="true" /><span>BarKadaKaskas</span></a>
            <nav aria-label="Legal pages"><a v-for="p in pages" :key="p.key" :href="p.href" :aria-current="p.key === current ? 'page' : undefined">{{ p.label }}</a></nav>
        </header>
        <main class="legal-body">
            <h1>{{ title }}</h1>
            <p class="effective">Effective {{ effective }}</p>
            <slot />
        </main>
    </div>
</template>

<style scoped>
.legal{min-height:100vh;background:#f7f6ef;color:#203d32;font-family:'Instrument Sans',ui-sans-serif,system-ui,sans-serif}
.legal-header{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;width:min(880px,calc(100% - 32px));margin:0 auto;padding:22px 0;border-bottom:1px solid #dce0d5}
.brand{display:inline-flex;align-items:center;gap:8px;font-weight:750;letter-spacing:-.5px}
.legal-header nav{display:flex;gap:16px;flex-wrap:wrap;font-size:13px}
.legal-header nav a{color:#617167}.legal-header nav a[aria-current]{color:#214c3d;font-weight:700;text-decoration:underline;text-underline-offset:4px}
.legal-body{width:min(880px,calc(100% - 32px));margin:0 auto;padding:36px 0 80px;font-size:15px;line-height:1.75}
.legal-body h1{font-size:clamp(30px,5vw,42px);letter-spacing:-1.2px;line-height:1.15;margin:0}
.effective{color:#617167;font-size:13px;margin:8px 0 28px}
.legal-body :deep(h2){font-size:20px;margin:32px 0 8px;letter-spacing:-.3px}
.legal-body :deep(p),.legal-body :deep(li){color:#2d473c}
.legal-body :deep(ul),.legal-body :deep(ol){padding-left:22px}
.legal-body :deep(a){color:#214c3d;text-decoration:underline;text-underline-offset:3px}
.legal-body :deep(.box){padding:14px 16px;border-radius:10px;background:#eef2e8;border:1px solid #dce0d5}
.legal-body :deep(table){width:100%;border-collapse:collapse;font-size:14px;margin:10px 0}
.legal-body :deep(th),.legal-body :deep(td){text-align:left;padding:8px 10px;border-bottom:1px solid #dce0d5;vertical-align:top}
</style>

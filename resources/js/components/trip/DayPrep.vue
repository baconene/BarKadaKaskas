<script setup lang="ts">
import { ref, watch } from 'vue';
import { Backpack, Shirt, Sparkles } from 'lucide-vue-next';
import { appFetch } from '@/lib/uploads';

/**
 * "Prep for the day": a short summary of the day's plan, what to wear and what to bring, written by AI from the
 * itinerary (the server caches it until that day's plan changes). Collapsible; hidden when the AI isn't available.
 */
type Prep = { date: string; stops: number; summary: string | null; outfit: string[]; bring: string[]; tips: string[]; available: boolean };
const props = defineProps<{ tripId: number; date: string; signature: string }>();
const prep = ref<Prep | null>(null);
const loading = ref(false);
const cache = new Map<string, Prep>();

watch(() => `${props.date}|${props.signature}`, async key => {
    if (cache.has(key)) { prep.value = cache.get(key)!; return; }
    prep.value = null; loading.value = true;
    try {
        const { data } = await appFetch<{ data: Prep }>(`/trips/${props.tripId}/itinerary-day?date=${props.date}`);
        cache.set(key, data);
        if (`${props.date}|${props.signature}` === key) prep.value = data;
    } catch { /* optional extra */ }
    finally { loading.value = false; }
}, { immediate: true });
</script>

<template>
    <details v-if="loading || (prep && (prep.summary || prep.outfit.length || prep.bring.length))" class="day-prep" open>
        <summary><Sparkles :size="15" aria-hidden="true" /> Prep for the day</summary>
        <p v-if="loading" class="muted">Reading the day’s plan…</p>
        <template v-else-if="prep">
            <p v-if="prep.summary" class="summary">{{ prep.summary }}</p>
            <div class="cols">
                <section v-if="prep.outfit.length"><h4><Shirt :size="14" aria-hidden="true" /> What to wear</h4><ul><li v-for="o in prep.outfit" :key="o">{{ o }}</li></ul></section>
                <section v-if="prep.bring.length"><h4><Backpack :size="14" aria-hidden="true" /> What to bring</h4><ul class="checks"><li v-for="b in prep.bring" :key="b">{{ b }}</li></ul></section>
            </div>
            <ul v-if="prep.tips.length" class="tips"><li v-for="t in prep.tips" :key="t">{{ t }}</li></ul>
            <p class="fine">Suggested by AI from today’s plan. Check the weather before you head out.</p>
        </template>
    </details>
</template>

<style scoped>
.day-prep{border:1px solid #e3eadc;background:#f5f8f1;border-radius:16px;padding:12px 14px}
.day-prep summary{display:flex;align-items:center;gap:6px;font-weight:750;color:#214e3e;cursor:pointer;list-style:none;min-height:32px}
.day-prep summary::-webkit-details-marker{display:none}
.day-prep[open] summary{margin-bottom:6px}
.summary{font-size:14px;line-height:1.55;color:#33463d}
.cols{display:grid;gap:12px;margin-top:10px}
@media(min-width:560px){.cols{grid-template-columns:1fr 1fr}}
h4{display:flex;align-items:center;gap:5px;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#76846e;margin-bottom:4px}
ul{margin:0;padding-left:18px;display:grid;gap:3px;font-size:13px;color:#33463d}
ul.checks{list-style:none;padding-left:0}
ul.checks li::before{content:'☐';margin-right:6px;color:#72883a}
.tips{margin-top:10px;font-size:13px;color:#4f6156}
.fine{font-size:11px;color:#8a978f;margin-top:8px}
.muted{font-size:13px;color:#8a978f}
</style>

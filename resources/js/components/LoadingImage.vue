<script setup lang="ts">
import { ImageOff } from 'lucide-vue-next';
import { ref, watch } from 'vue';

/**
 * An image that shows a shimmer while it downloads, fades in when ready, and shows a quiet fallback if it fails,
 * so cards never sit blank on slow connections. Fills its container (object-fit: cover by default).
 */
const props = withDefaults(defineProps<{ src: string | null | undefined; alt?: string; fit?: 'cover' | 'contain'; eager?: boolean }>(), { alt: '', fit: 'cover', eager: false });
const state = ref<'loading' | 'ready' | 'failed'>('loading');
watch(() => props.src, () => { state.value = 'loading'; });
</script>

<template>
    <span class="loading-image" :class="state">
        <span v-if="state === 'loading'" class="skeleton fill" aria-hidden="true" />
        <span v-else-if="state === 'failed'" class="fallback" aria-hidden="true"><ImageOff :size="20" /></span>
        <img v-if="src" :src="src" :alt="alt" :loading="eager ? 'eager' : 'lazy'" decoding="async" :style="{ objectFit: fit }" @load="state = 'ready'" @error="state = 'failed'" />
    </span>
</template>

<style scoped>
.loading-image{position:absolute;inset:0;display:block;overflow:hidden}
.fill{position:absolute;inset:0;border-radius:0}
img{position:absolute;inset:0;width:100%;height:100%;opacity:0;transition:opacity .35s ease}
.ready img{opacity:1}
.failed img{display:none}
.fallback{position:absolute;inset:0;display:grid;place-items:center;background:#eef2e9;color:#9aa89a}
@media(prefers-reduced-motion:reduce){img{transition:none}}
</style>

<script setup lang="ts">
import { CalendarDays, Images, Map as MapIcon, ReceiptText, Sun, UsersRound } from 'lucide-vue-next';

export type TripScreen = 'today' | 'itinerary' | 'map' | 'expenses' | 'photos' | 'group';
const screen = defineModel<TripScreen>({ required: true });
defineProps<{ badges?: Partial<Record<TripScreen, number>> }>();
const items: { key: TripScreen; label: string; icon: unknown }[] = [
    { key: 'today', label: 'Today', icon: Sun },
    { key: 'itinerary', label: 'Itinerary', icon: CalendarDays },
    { key: 'map', label: 'Map', icon: MapIcon },
    { key: 'expenses', label: 'Expenses', icon: ReceiptText },
    { key: 'photos', label: 'Photos', icon: Images },
    { key: 'group', label: 'Group', icon: UsersRound },
];
</script>

<template>
    <nav class="trip-nav" aria-label="Trip sections">
        <button v-for="item in items" :key="item.key" type="button" class="trip-nav-item" :aria-current="screen === item.key ? 'page' : undefined" @click="screen = item.key">
            <span class="icon-wrap"><component :is="item.icon" :size="21" aria-hidden="true" /><span v-if="badges?.[item.key]" class="badge-dot">{{ badges[item.key] }}</span></span>
            <span class="label">{{ item.label }}</span>
        </button>
    </nav>
</template>

<style scoped>
.trip-nav{position:fixed;z-index:40;left:0;right:0;bottom:0;display:grid;grid-template-columns:repeat(6,1fr);background:#fffefbf2;backdrop-filter:blur(10px);border-top:1px solid #e1e8dc;padding:4px max(6px,env(safe-area-inset-left)) calc(4px + env(safe-area-inset-bottom)) max(6px,env(safe-area-inset-right))}
.trip-nav-item{display:grid;justify-items:center;align-content:center;gap:2px;min-height:56px;border:0;background:transparent;color:#6c7c72;font:inherit;cursor:pointer;border-radius:12px;-webkit-tap-highlight-color:transparent}
.icon-wrap{position:relative;display:grid;place-items:center;width:46px;height:30px;border-radius:999px;transition:background .15s}
.label{font-size:11px;font-weight:650;letter-spacing:.1px}
.trip-nav-item[aria-current="page"]{color:#214e3e}
.trip-nav-item[aria-current="page"] .icon-wrap{background:#e3edd5}
.trip-nav-item[aria-current="page"] .label{font-weight:800}
.trip-nav-item:focus-visible{outline:3px solid #9cb865;outline-offset:-2px}
.badge-dot{position:absolute;top:-2px;right:6px;min-width:16px;height:16px;padding:0 4px;border-radius:999px;background:#c2683f;color:#fff;font-size:10px;font-weight:800;line-height:16px;text-align:center}
@media(min-width:1024px){
    .trip-nav{position:sticky;top:0;bottom:auto;display:flex;gap:4px;margin:0 0 20px;padding:6px;border:1px solid #e1e8dc;border-radius:16px;background:#fffefbf2}
    .trip-nav-item{display:flex;align-items:center;gap:8px;min-height:44px;padding:0 16px 0 6px}
    .icon-wrap{width:34px;height:30px}
    .label{font-size:13px}
}
@media(prefers-reduced-motion:reduce){.icon-wrap{transition:none}}
</style>

<script setup lang="ts">
import { Link } from '@inertiajs/vue3';
import { ArrowLeft, CalendarDays, Ellipsis, MapPin, UserPlus, Users } from 'lucide-vue-next';
import { googleMapsUrl } from '@/lib/maps';

/** Compact trip header with the money summary strip. Metrics open the related screen. */
const props = defineProps<{ name: string; destination: string; startsOn: string; endsOn: string; travelers: number; isOrganizer: boolean; spent: string; balanceMinor: number; balance: string; expenseCount: number; openExpenses: number; hideSummary?: boolean }>();
const emit = defineEmits<{ open: [screen: 'expenses' | 'group'] }>();
const range = (() => {
    const fmt = (value: string, options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-PH', { ...options, timeZone: 'UTC' }).format(new Date(`${value}T00:00:00Z`));
    const sameYear = props.startsOn.slice(0, 4) === props.endsOn.slice(0, 4);
    return `${fmt(props.startsOn, { month: 'short', day: 'numeric', ...(sameYear ? {} : { year: 'numeric' }) })} – ${fmt(props.endsOn, { month: 'short', day: 'numeric', year: 'numeric' })}`;
})();
</script>

<template>
    <header class="trip-header">
        <div class="trip-header-top">
            <Link href="/dashboard" class="back" aria-label="All trips"><ArrowLeft :size="18" /></Link>
            <div class="trip-id">
                <a class="destination" :href="googleMapsUrl(destination)" target="_blank" rel="noopener noreferrer"><MapPin :size="12" aria-hidden="true" /> {{ destination }}<span class="sr-only"> (opens Google Maps)</span></a>
                <h1>{{ name }}</h1>
                <p class="meta"><span><CalendarDays :size="13" aria-hidden="true" /> {{ range }}</span><span><Users :size="13" aria-hidden="true" /> {{ travelers }} {{ travelers === 1 ? 'traveler' : 'travelers' }}</span></p>
            </div>
            <div class="trip-header-actions">
                <button v-if="isOrganizer" type="button" class="round" aria-label="Invite friends" title="Invite friends" @click="emit('open', 'group')"><UserPlus :size="18" /></button>
                <details class="more">
                    <summary class="round" aria-label="More trip actions"><Ellipsis :size="18" /></summary>
                    <div class="more-menu" role="menu">
                        <a role="menuitem" :href="googleMapsUrl(destination)" target="_blank" rel="noopener noreferrer">Open destination in Google Maps</a>
                        <button role="menuitem" type="button" @click="emit('open', 'group')">Travelers &amp; balances</button>
                        <Link role="menuitem" href="/dashboard">All trips</Link>
                    </div>
                </details>
            </div>
        </div>
        <!-- Hidden on Expenses/Group, which show their own fuller money summary. -->
        <div v-if="!hideSummary" class="summary-strip">
            <button type="button" class="metric featured" @click="emit('open', 'expenses')"><span>Trip spending</span><strong>{{ spent }}</strong></button>
            <button type="button" class="metric" @click="emit('open', 'group')"><span>Your balance</span><strong :class="{ owe: balanceMinor < 0, receive: balanceMinor > 0 }">{{ balance }}</strong></button>
            <button type="button" class="metric" @click="emit('open', 'expenses')"><span>Expenses</span><strong>{{ expenseCount }}<small v-if="openExpenses"> · {{ openExpenses }} open</small></strong></button>
        </div>
    </header>
</template>

<style scoped>
.trip-header{display:grid;gap:14px;margin-bottom:16px}
.trip-header-top{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:start;gap:10px}
.back,.round{display:grid;place-items:center;width:44px;height:44px;border-radius:12px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer;list-style:none}
.round::-webkit-details-marker{display:none}
.trip-id{min-width:0}
.destination{display:inline-flex;align-items:center;gap:4px;font-size:11px;font-weight:800;letter-spacing:1.2px;text-transform:uppercase;color:#698448}
.travel-app .trip-header h1{font-size:clamp(20px,5.4vw,34px);letter-spacing:-.8px;line-height:1.15;margin-top:2px;overflow-wrap:anywhere}
.meta{display:flex;flex-wrap:wrap;gap:4px 14px;margin-top:4px;font-size:12px;color:#6c7c72}
.meta span{display:inline-flex;align-items:center;gap:5px}
.trip-header-actions{display:flex;gap:8px}
.more{position:relative}
.more-menu{position:absolute;right:0;top:50px;z-index:45;display:grid;min-width:240px;padding:6px;background:#fff;border:1px solid #e0e7dc;border-radius:14px;box-shadow:0 12px 32px #233d2626}
.more-menu a,.more-menu button{display:block;text-align:left;padding:12px;border-radius:10px;border:0;background:transparent;font:inherit;font-size:14px;color:#172f2a;cursor:pointer}
.more-menu a:hover,.more-menu button:hover{background:#f2f6ec}
.summary-strip{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}
.metric{display:grid;gap:2px;text-align:left;min-height:60px;padding:10px 12px;border-radius:14px;border:1px solid #e0e7dc;background:#fff;color:#172f2a;font:inherit;cursor:pointer;min-width:0}
.metric span{font-size:11px;color:#6c7c72;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.metric strong{font-size:16px;font-weight:800;letter-spacing:-.4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.metric strong small{font-size:11px;font-weight:600;color:#926815}
.metric.featured{background:#214e3e;border-color:#214e3e;color:#fff}
.metric.featured span{color:#cfe0a5}
.metric .owe{color:#a6573e}
.metric .receive{color:#3d7650}
.metric:hover{border-color:#bcd0a4}
.metric:focus-visible,.round:focus-visible,.back:focus-visible{outline:3px solid #9cb865;outline-offset:2px}
@media(max-width:360px){.metric{padding:9px 10px}.metric strong{font-size:14px}}
@media(min-width:1024px){.trip-header{grid-template-columns:minmax(0,1fr) minmax(420px,46%);align-items:center}.metric{min-height:68px}.metric strong{font-size:19px}}
</style>

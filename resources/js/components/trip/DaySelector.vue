<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';

export type DayOption = { key: string; number: number; weekday: string; date: string; count: number };
const props = defineProps<{ days: DayOption[]; allowAll?: boolean; allCount?: number; label?: string }>();
const selected = defineModel<string | null>({ required: true });
const track = ref<HTMLElement>();

// Keep the selected day visible when it changes (e.g. after a swipe on the timeline).
watch(selected, () => nextTick(() => {
    const active = track.value?.querySelector<HTMLElement>('[aria-pressed="true"]');
    active?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
}), { immediate: true });
</script>

<template>
    <div ref="track" class="day-selector" role="group" :aria-label="label ?? 'Choose a day'">
        <button v-if="allowAll" type="button" class="day-option all" :aria-pressed="selected === null" @click="selected = null">
            <span class="weekday">All</span><span class="date">Days</span><span class="count">{{ props.allCount }}</span>
        </button>
        <button v-for="day in days" :key="day.key" type="button" class="day-option" :aria-pressed="selected === day.key" :aria-label="`Day ${day.number}, ${day.weekday} ${day.date}, ${day.count} activities`" @click="selected = day.key">
            <span class="weekday">{{ day.weekday }}</span><span class="date">{{ day.date }}</span><span class="count">{{ day.count }}</span>
        </button>
    </div>
</template>

<style scoped>
.day-selector{display:flex;gap:8px;overflow-x:auto;scroll-snap-type:x proximity;padding:2px 2px 6px;scrollbar-width:none;-webkit-overflow-scrolling:touch}
.day-selector::-webkit-scrollbar{display:none}
.day-option{flex:0 0 auto;scroll-snap-align:center;display:grid;justify-items:center;gap:1px;min-width:62px;min-height:62px;padding:8px 10px;border-radius:14px;border:1px solid #d9e2d6;background:#fff;color:#214e3e;cursor:pointer;transition:background .15s,border-color .15s,transform .15s}
.day-option:hover{border-color:#a9bd8e}
.day-option:active{transform:scale(.97)}
.weekday{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;color:#6f8070}
.date{font-size:15px;font-weight:800;letter-spacing:-.3px}
.count{font-size:10px;font-weight:700;color:#526936;background:#edf2e6;border-radius:999px;padding:0 7px;margin-top:2px}
.day-option[aria-pressed="true"]{background:#214e3e;border-color:#214e3e;color:#fff}
.day-option[aria-pressed="true"] .weekday{color:#cfe0a5}
.day-option[aria-pressed="true"] .count{background:#ffffff26;color:#fff}
.day-option:focus-visible{outline:3px solid #9cb865;outline-offset:2px}
@media(prefers-reduced-motion:reduce){.day-option{transition:none}.day-option:active{transform:none}}
</style>

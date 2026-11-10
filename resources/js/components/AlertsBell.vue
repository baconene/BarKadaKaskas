<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { router } from '@inertiajs/vue3';
import { Bell, CalendarClock, Camera, CheckCheck, LoaderCircle, MessageCircle, Receipt } from 'lucide-vue-next';
import { appFetch } from '@/lib/uploads';

/**
 * The bell in the top bar: alerts for what others did on your trips (comments, photos, expenses, itinerary changes).
 * Checks the unread count every minute while the page is visible; opening the list shows the latest 50.
 */
type Alert = { id: number; type: 'comment' | 'post' | 'expense' | 'itinerary'; title: string; body: string | null; trip_id: number; trip_name: string | null; read: boolean; created_at: string };
const unread = ref(0);
const open = ref(false);
const alerts = ref<Alert[]>([]);
const loading = ref(false);
const root = ref<HTMLElement | null>(null);
let timer: number | undefined;

async function refreshCount() {
    if (document.visibilityState !== 'visible') return;
    try { unread.value = (await appFetch<{ unread_count: number }>('/alerts/unread-count')).unread_count; } catch { /* offline: try again next minute */ }
}
async function toggle() {
    open.value = !open.value;
    if (!open.value) return;
    loading.value = true;
    try { const result = await appFetch<{ data: Alert[]; unread_count: number }>('/alerts'); alerts.value = result.data; unread.value = result.unread_count; }
    catch { /* keep the old list */ }
    finally { loading.value = false; }
}
async function markRead(ids?: number[]) {
    const body = new FormData(); (ids ?? []).forEach(id => body.append('ids[]', String(id)));
    try { unread.value = (await appFetch<{ unread_count: number }>('/alerts/read', { method: 'POST', body })).unread_count; } catch { /* ignore */ }
    alerts.value = alerts.value.map(a => (!ids || ids.includes(a.id) ? { ...a, read: true } : a));
}
const tab: Record<Alert['type'], string> = { comment: 'photos', post: 'photos', expense: 'expenses', itinerary: 'itinerary' };
async function go(alert: Alert) {
    if (!alert.read) await markRead([alert.id]);
    open.value = false;
    router.visit(`/trips/${alert.trip_id}#${tab[alert.type]}`);
}
const icon = { comment: MessageCircle, post: Camera, expense: Receipt, itinerary: CalendarClock };
const when = (iso: string) => {
    const minutes = (Date.now() - Date.parse(iso)) / 60000;
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${Math.floor(minutes)}m ago`;
    if (minutes < 1440) return `${Math.floor(minutes / 60)}h ago`;
    return new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric' }).format(new Date(iso));
};
function outside(event: MouseEvent) { if (open.value && root.value && !root.value.contains(event.target as Node)) open.value = false; }
onMounted(() => { refreshCount(); timer = window.setInterval(refreshCount, 60000); document.addEventListener('visibilitychange', refreshCount); document.addEventListener('click', outside); });
onBeforeUnmount(() => { window.clearInterval(timer); document.removeEventListener('visibilitychange', refreshCount); document.removeEventListener('click', outside); });
</script>

<template>
    <div ref="root" class="bell-wrap">
        <button type="button" class="bell" :aria-label="unread ? `Alerts, ${unread} unread` : 'Alerts'" :aria-expanded="open" @click="toggle">
            <Bell :size="20" aria-hidden="true" />
            <span v-if="unread" class="count">{{ unread > 99 ? '99+' : unread }}</span>
        </button>
        <div v-if="open" class="panel" role="dialog" aria-label="Alerts">
            <header><strong>Alerts</strong><button v-if="unread" type="button" class="link" @click="markRead()"><CheckCheck :size="14" aria-hidden="true" /> Mark all read</button></header>
            <p v-if="loading && !alerts.length" class="empty"><LoaderCircle :size="16" class="spin" aria-hidden="true" /> Loading…</p>
            <p v-else-if="!alerts.length" class="empty">No alerts yet. You'll hear here when your barkada comments, posts photos, adds expenses or changes the plan.</p>
            <ul v-else>
                <li v-for="a in alerts" :key="a.id">
                    <button type="button" class="item" :class="{ unread: !a.read }" @click="go(a)">
                        <span class="icon" :class="a.type"><component :is="icon[a.type]" :size="16" aria-hidden="true" /></span>
                        <span class="text"><strong>{{ a.title }}</strong><span v-if="a.body" class="body">{{ a.body }}</span><small>{{ a.trip_name }} · {{ when(a.created_at) }}</small></span>
                        <span v-if="!a.read" class="dot" aria-label="Unread" />
                    </button>
                </li>
            </ul>
        </div>
    </div>
</template>

<style scoped>
.bell-wrap{position:relative}
.bell{position:relative;display:grid;place-items:center;width:40px;height:40px;border-radius:12px;border:0;background:transparent;color:#214e3e;cursor:pointer}
.bell:hover{background:#f1f5ec}
.count{position:absolute;top:3px;right:2px;min-width:18px;height:18px;padding:0 5px;border-radius:999px;background:#c2410c;color:#fff;font-size:10px;font-weight:800;display:grid;place-items:center;border:2px solid #fff}
.panel{position:absolute;right:-8px;top:46px;z-index:50;width:min(380px,calc(100vw - 24px));max-height:min(70vh,520px);overflow-y:auto;background:#fff;border:1px solid #e0e7dc;border-radius:18px;box-shadow:0 18px 50px #172f2a33}
.panel header{position:sticky;top:0;display:flex;justify-content:space-between;align-items:center;padding:14px 16px;background:#fff;border-bottom:1px solid #eef2ea;font-size:15px}
.link{display:inline-flex;align-items:center;gap:4px;border:0;background:none;color:#3d7650;font:inherit;font-size:12px;font-weight:700;cursor:pointer}
.empty{padding:20px 16px;font-size:13px;color:#738178;line-height:1.6}
ul{list-style:none;margin:0;padding:6px}
.item{display:flex;gap:10px;align-items:flex-start;width:100%;padding:10px;border:0;border-radius:12px;background:transparent;text-align:left;font:inherit;color:inherit;cursor:pointer}
.item:hover{background:#f6f9f1}
.item.unread{background:#f3f8ec}
.icon{flex-shrink:0;display:grid;place-items:center;width:32px;height:32px;border-radius:10px;background:#edf2e6;color:#214e3e}
.icon.expense{background:#fff3d6;color:#926815}.icon.itinerary{background:#e6eef8;color:#2f5a8a}
.text{display:grid;gap:2px;min-width:0;flex:1}
.text strong{font-size:13px;line-height:1.35}
.body{font-size:12px;color:#4d5f55;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.text small{font-size:11px;color:#7b8a80}
.dot{flex-shrink:0;width:8px;height:8px;border-radius:50%;background:#c2410c;margin-top:6px}
.spin{animation:spin 1s linear infinite;vertical-align:-3px}
@keyframes spin{to{transform:rotate(360deg)}}
</style>

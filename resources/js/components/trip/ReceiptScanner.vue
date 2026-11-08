<script setup lang="ts">
import { Camera, LoaderCircle, ScanLine, TriangleAlert } from 'lucide-vue-next';
import { ref } from 'vue';

/** What the server read from a receipt photo; the expense form is filled from it and stays editable. */
export type ReceiptDraft = {
    scan_id: number; merchant: string | null; title: string; incurred_at: string | null; currency: string | null;
    category_id: number | null; total_minor: number | null; lines: { description: string; amount_minor: number }[]; warnings: string[];
};

const props = defineProps<{ tripId: number }>();
const emit = defineEmits<{ scanned: [draft: ReceiptDraft] }>();

const input = ref<HTMLInputElement | null>(null);
const busy = ref(false);
const error = ref('');
const warnings = ref<string[]>([]);
const done = ref(false);

/** Shrinks big photos (longest side 2000px, JPEG) so uploads are quick but text stays legible. */
async function prepare(file: File): Promise<Blob> {
    const bitmap = await createImageBitmap(file).catch(() => null);
    if (!bitmap) return file;
    const scale = Math.min(1, 2000 / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size < 2_500_000 && file.type === 'image/jpeg') return file;
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale); canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return await new Promise<Blob>(resolve => canvas.toBlob(blob => resolve(blob ?? file), 'image/jpeg', 0.9));
}

/** A readable reason, including the HTTP status for errors that come from the web server rather than the app. */
function failure(status: number, message?: string): string {
    if (status === 419) return 'Your session expired. Refresh the page and try again.';
    if (status === 413) return 'That photo is too large for the server. Try a smaller photo. (HTTP 413)';
    if (status === 502 || status === 504) return message || `The server stopped waiting for the receipt reader. Try again in a moment; if it keeps happening, raise the server's PHP time limit or use a faster model. (HTTP ${status})`;
    if (message && message !== 'Server Error') return message;
    return `The receipt could not be read (HTTP ${status}). Please try again; if it keeps happening, check the server log.`;
}

function xsrfToken(): string {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
}

async function scan(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    busy.value = true; error.value = ''; warnings.value = []; done.value = false;
    try {
        const body = new FormData();
        body.append('receipt', await prepare(file), 'receipt.jpg');
        const response = await fetch(`/trips/${props.tripId}/receipt-scans`, { method: 'POST', body, credentials: 'same-origin', headers: { Accept: 'application/json', 'X-XSRF-TOKEN': xsrfToken(), 'X-Requested-With': 'XMLHttpRequest' } });
        const json = await response.json().catch(() => ({}));
        if (!response.ok) {
            error.value = failure(response.status, json.message);
            return;
        }
        warnings.value = json.data.warnings ?? [];
        done.value = true;
        emit('scanned', json.data as ReceiptDraft);
    } catch {
        error.value = 'Could not reach the server. Check your connection and try again.';
    } finally {
        busy.value = false;
        if (input.value) input.value.value = '';
    }
}
</script>

<template>
    <div class="scanner full">
        <label class="scan-button" :class="{ busy }">
            <LoaderCircle v-if="busy" :size="20" class="spin" aria-hidden="true" />
            <ScanLine v-else :size="20" aria-hidden="true" />
            <span><strong>{{ busy ? 'Reading your receipt…' : done ? 'Scan another receipt' : 'Scan a receipt' }}</strong><small>{{ busy ? 'This takes a few seconds.' : 'Take a photo and we’ll fill in the items for you.' }}</small></span>
            <Camera v-if="!busy" :size="18" class="trail" aria-hidden="true" />
            <input ref="input" type="file" accept="image/*" capture="environment" class="sr-only" :disabled="busy" @change="scan" />
        </label>
        <p v-if="error" class="message error" role="alert"><TriangleAlert :size="15" aria-hidden="true" /> {{ error }}</p>
        <p v-else-if="done" class="message ok" role="status">Filled in from your receipt. Check the amounts, then choose who shares each item.</p>
        <ul v-if="warnings.length" class="warnings" role="status"><li v-for="w in warnings" :key="w"><TriangleAlert :size="14" aria-hidden="true" /> {{ w }}</li></ul>
    </div>
</template>

<style scoped>
.scanner{display:grid;gap:8px}
.scan-button{display:flex;align-items:center;gap:12px;padding:14px 16px;border-radius:14px;border:1.5px dashed #9cb865;background:#f5f9ef;color:#214e3e;cursor:pointer;transition:background .2s}
.scan-button:hover{background:#edf4e3}
.scan-button.busy{cursor:progress;opacity:.85}
.scan-button span{display:grid;gap:2px;flex:1}
.scan-button strong{font-size:14px}
.scan-button small{font-size:12px;color:#6c7c72;font-weight:400}
.trail{color:#72883a}
.spin{animation:spin 1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
.message{display:flex;gap:6px;align-items:flex-start;font-size:13px;line-height:1.5}
.message.error{color:#9c3e2d}
.message.ok{color:#276c47}
.warnings{display:grid;gap:4px;padding:10px 12px;border-radius:10px;background:#fff7e6;color:#7a5512;font-size:12px;line-height:1.5}
.warnings li{display:flex;gap:6px;align-items:flex-start}
.warnings svg{flex-shrink:0;margin-top:2px}
@media(prefers-reduced-motion:reduce){.spin{animation:none}}
</style>

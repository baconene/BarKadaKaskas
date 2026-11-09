<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Eye, EyeOff, FileText, LoaderCircle, Lock, Paperclip, Plus, Trash2, Users } from 'lucide-vue-next';
import { appFetch, shrinkImage } from '@/lib/uploads';

/**
 * A traveller's documents for this trip: boarding passes, IDs, bank QR codes. Everyone on the trip sees shared ones;
 * private ones only their owner and the organizer (the server decides what is listed). You manage your own here.
 */
type Doc = { id: number; member_id: number; type: string; label: string | null; visibility: 'trip' | 'private'; mime_type: string; size: number; is_image: boolean; mine: boolean; file_path: string };
const props = defineProps<{ tripId: number; memberId: number; isMe: boolean }>();

const TYPES: Record<string, string> = { boarding_pass: 'Boarding pass', id: 'ID', bank_qr: 'Bank / e-wallet QR', booking: 'Booking', other: 'Other' };
const docs = ref<Doc[]>([]);
const loading = ref(true);
const error = ref('');
const mineHere = computed(() => docs.value.filter(d => d.member_id === props.memberId));

async function load() {
    loading.value = true;
    try { docs.value = (await appFetch<{ data: Doc[] }>(`/trips/${props.tripId}/documents`)).data; }
    catch (e) { error.value = e instanceof Error ? e.message : 'Could not load documents.'; }
    finally { loading.value = false; }
}
onMounted(load);

// Adding a document (your own card only).
const adding = ref(false);
const type = ref('boarding_pass');
const label = ref('');
const visibility = ref<'trip' | 'private'>('private');
const file = ref<File | null>(null);
const saving = ref(false);
function pickType(value: string) { type.value = value; visibility.value = value === 'bank_qr' ? 'trip' : 'private'; }
function pickFile(event: Event) { const input = event.target as HTMLInputElement; file.value = input.files?.[0] ?? null; }
async function upload() {
    if (!file.value || saving.value) return;
    saving.value = true; error.value = '';
    const body = new FormData();
    body.append('type', type.value); body.append('visibility', visibility.value);
    if (label.value.trim()) body.append('label', label.value.trim());
    // Photos are downsized (QR codes stay sharp at 2400px); PDFs go as they are.
    const upload = file.value.type.startsWith('image/') ? await shrinkImage(file.value, 2400, 0.92) : file.value;
    body.append('file', upload, file.value.type.startsWith('image/') ? 'document.jpg' : file.value.name);
    try {
        const { data } = await appFetch<{ data: Doc }>(`/trips/${props.tripId}/documents`, { method: 'POST', body });
        docs.value = [data, ...docs.value];
        adding.value = false; label.value = ''; file.value = null;
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not upload the document.'; }
    finally { saving.value = false; }
}
async function toggleVisibility(doc: Doc) {
    const body = new FormData(); body.append('_method', 'PATCH'); body.append('visibility', doc.visibility === 'trip' ? 'private' : 'trip');
    try { const { data } = await appFetch<{ data: Doc }>(`/trips/${props.tripId}/documents/${doc.id}`, { method: 'POST', body }); Object.assign(doc, data); }
    catch (e) { error.value = e instanceof Error ? e.message : 'Could not change who can see it.'; }
}
async function remove(doc: Doc) {
    if (!confirm(`Remove “${doc.label || TYPES[doc.type]}”?`)) return;
    try { await appFetch(`/trips/${props.tripId}/documents/${doc.id}`, { method: 'DELETE' }); docs.value = docs.value.filter(d => d.id !== doc.id); }
    catch (e) { error.value = e instanceof Error ? e.message : 'Could not remove the document.'; }
}
</script>

<template>
    <section class="block docs">
        <h3><Paperclip :size="15" aria-hidden="true" /> Documents</h3>
        <p v-if="loading" class="muted small"><LoaderCircle :size="14" class="spin" aria-hidden="true" /> Loading…</p>
        <template v-else>
            <ul v-if="mineHere.length" class="doc-list">
                <li v-for="doc in mineHere" :key="doc.id" class="doc">
                    <a :href="doc.file_path" target="_blank" rel="noopener" class="thumb" :aria-label="`Open ${doc.label || TYPES[doc.type]}`">
                        <img v-if="doc.is_image" :src="doc.file_path" alt="" loading="lazy" />
                        <FileText v-else :size="22" aria-hidden="true" />
                    </a>
                    <span class="info">
                        <strong>{{ doc.label || TYPES[doc.type] }}</strong>
                        <small>{{ TYPES[doc.type] }} · <template v-if="doc.visibility === 'trip'"><Users :size="11" aria-hidden="true" /> Everyone on the trip</template><template v-else><Lock :size="11" aria-hidden="true" /> {{ isMe ? 'You and the organizer' : 'Private' }}</template></small>
                    </span>
                    <span v-if="doc.mine" class="doc-actions">
                        <button type="button" class="icon" :aria-label="doc.visibility === 'trip' ? 'Make private' : 'Share with everyone on the trip'" :title="doc.visibility === 'trip' ? 'Make private' : 'Share with the trip'" @click="toggleVisibility(doc)"><EyeOff v-if="doc.visibility === 'trip'" :size="16" /><Eye v-else :size="16" /></button>
                        <button type="button" class="icon danger" :aria-label="`Remove ${doc.label || TYPES[doc.type]}`" @click="remove(doc)"><Trash2 :size="16" /></button>
                    </span>
                </li>
            </ul>
            <p v-else class="muted small">{{ isMe ? 'Keep your boarding pass, ID or bank QR here for the trip.' : 'No documents shared with you.' }}</p>

            <template v-if="isMe">
                <button v-if="!adding" type="button" class="btn secondary wide" @click="adding = true"><Plus :size="16" aria-hidden="true" /> Add a document</button>
                <form v-else class="add" @submit.prevent="upload">
                    <label class="field">Type<select :value="type" @change="pickType(($event.target as HTMLSelectElement).value)"><option v-for="(name, key) in TYPES" :key="key" :value="key">{{ name }}</option></select></label>
                    <label class="field">Name (optional)<input v-model="label" maxlength="120" placeholder="e.g. Cebu Pacific MNL–DGT, GCash" /></label>
                    <label class="field">File<input type="file" accept="image/*,application/pdf" required @change="pickFile" /></label>
                    <fieldset class="vis">
                        <legend>Who can see it</legend>
                        <label><input v-model="visibility" type="radio" value="private" /> Only me and the organizer</label>
                        <label><input v-model="visibility" type="radio" value="trip" /> Everyone on the trip</label>
                    </fieldset>
                    <div class="actions"><button type="button" class="btn secondary" :disabled="saving" @click="adding = false">Cancel</button><button class="btn" :disabled="saving || !file">{{ saving ? 'Uploading…' : 'Upload' }}</button></div>
                </form>
            </template>
        </template>
        <p v-if="error" class="error small" role="alert">{{ error }}</p>
    </section>
</template>

<style scoped>
.block{display:grid;gap:8px;padding:14px;border-radius:16px;background:#f7f8f4;border:1px solid #e3e8da}
.block h3{display:flex;align-items:center;gap:6px;font-size:13px;font-weight:750;color:#507044;text-transform:uppercase;letter-spacing:.6px}
.doc-list{list-style:none;margin:0;padding:0;display:grid;gap:8px}
.doc{display:flex;align-items:center;gap:10px;padding:8px;border-radius:12px;background:#fff;border:1px solid #e3e8da}
.thumb{flex-shrink:0;display:grid;place-items:center;width:52px;height:52px;border-radius:10px;overflow:hidden;background:#eef3e7;color:#214e3e}
.thumb img{width:100%;height:100%;object-fit:cover}
.info{display:grid;min-width:0;flex:1}
.info strong{font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.info small{display:flex;align-items:center;gap:3px;font-size:12px;color:#738178;flex-wrap:wrap}
.doc-actions{display:flex;gap:4px}
.icon{display:grid;place-items:center;width:36px;height:36px;border-radius:10px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.icon.danger{color:#9c3e2d}
.add{display:grid;gap:10px}
.vis{border:0;padding:0;margin:0;display:grid;gap:6px;font-size:13px}
.vis legend{font-size:12px;font-weight:650;margin-bottom:4px}
.vis label{display:flex;align-items:center;gap:8px}
.actions{display:flex;justify-content:flex-end;gap:10px}
.wide{width:100%}
.small{font-size:12px}
.muted{color:#738178}
.error{color:#9c3e2d}
.spin{animation:spin 1s linear infinite;vertical-align:-2px}
@keyframes spin{to{transform:rotate(360deg)}}
</style>

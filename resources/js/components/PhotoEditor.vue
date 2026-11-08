<script setup lang="ts">
import { FlipHorizontal2, LoaderCircle, RotateCcw, RotateCw, Sparkles, Undo2, X, ZoomIn } from 'lucide-vue-next';
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { appFetch } from '@/lib/uploads';

/**
 * Built-in photo editor used for profile pictures, trip covers and trip photos: crop (drag + zoom), rotate, flip,
 * light and colour adjustments, filter presets, and AI restyling with Gemini on Vertex AI. Emits the finished JPEG;
 * the caller decides where to save it.
 */
const props = withDefaults(defineProps<{
    source: string;
    /** Width ÷ height of the crop, or "original" to keep the photo's own shape. */
    aspect?: number | 'original';
    /** Round crop guide (profile pictures). */
    round?: boolean;
    /** Longest side of the exported JPEG. */
    outputSize?: number;
    /** "portrait" keeps a person recognisable in AI styles; "photo" restyles a scene. */
    subject?: 'portrait' | 'photo';
    title?: string;
    confirmLabel?: string;
}>(), { aspect: 1, round: false, outputSize: 1600, subject: 'photo', title: 'Edit photo', confirmLabel: 'Use this photo' });
const emit = defineEmits<{ close: []; done: [photo: Blob] }>();

const dialog = ref<HTMLDialogElement | null>(null);
const canvas = ref<HTMLCanvasElement | null>(null);
const image = ref<HTMLImageElement | null>(null);
const history = ref<string[]>([]); // sources before each AI restyle, for Undo
const tab = ref<'crop' | 'adjust' | 'ai'>('crop');
const view = reactive({ zoom: 1, x: 0, y: 0, rotation: 0, flip: false });
const tune = reactive({ brightness: 100, contrast: 100, saturation: 100 });
const preset = ref('original');
const saving = ref(false);
const restyling = ref(false);
const error = ref('');

const presets: { key: string; label: string; filter: string }[] = [
    { key: 'original', label: 'Original', filter: '' },
    { key: 'warm', label: 'Warm', filter: 'sepia(0.28) saturate(1.25) brightness(1.04)' },
    { key: 'cool', label: 'Cool', filter: 'saturate(0.9) hue-rotate(-12deg) brightness(1.05)' },
    { key: 'vivid', label: 'Vivid', filter: 'saturate(1.5) contrast(1.1)' },
    { key: 'bw', label: 'B&W', filter: 'grayscale(1) contrast(1.12)' },
    { key: 'vintage', label: 'Vintage', filter: 'sepia(0.5) contrast(0.92) brightness(1.06) saturate(0.85)' },
    { key: 'fade', label: 'Fade', filter: 'contrast(0.85) brightness(1.1) saturate(0.8)' },
];
const allStyles: Record<string, { label: string; emoji: string }> = {
    cartoon: { label: 'Cartoon', emoji: '🎨' }, anime: { label: 'Anime', emoji: '✨' }, watercolor: { label: 'Watercolor', emoji: '🖌️' },
    '3d': { label: '3D animated', emoji: '🧸' }, 'pop-art': { label: 'Pop art', emoji: '💥' }, sketch: { label: 'Sketch', emoji: '✏️' },
    headshot: { label: 'Studio headshot', emoji: '📸' }, beach: { label: 'Beach trip', emoji: '🏝️' },
    'golden-hour': { label: 'Golden hour', emoji: '🌅' }, postcard: { label: 'Vintage postcard', emoji: '📮' },
};
const styleKeys = computed(() => props.subject === 'portrait'
    ? ['cartoon', 'anime', 'watercolor', '3d', 'pop-art', 'sketch', 'headshot', 'beach']
    : ['cartoon', 'anime', 'watercolor', '3d', 'pop-art', 'sketch', 'golden-hour', 'postcard']);
const styles = computed(() => styleKeys.value.map(key => ({ key, ...allStyles[key] })));
const customPrompt = ref('');
const filter = computed(() => [presets.find(p => p.key === preset.value)?.filter ?? '', `brightness(${tune.brightness}%) contrast(${tune.contrast}%) saturate(${tune.saturation}%)`].filter(Boolean).join(' '));

/** On-screen crop frame: fits the dialog and follows the crop shape. */
const ratio = computed(() => {
    if (props.aspect !== 'original') return props.aspect;
    const img = image.value;
    if (!img) return 1;
    const turned = view.rotation % 180 !== 0;
    return Math.min(2, Math.max(0.5, (turned ? img.naturalHeight : img.naturalWidth) / (turned ? img.naturalWidth : img.naturalHeight)));
});
const frameMax = typeof window === 'undefined' ? 320 : Math.min(380, window.innerWidth - 56);
const W = computed(() => (ratio.value >= 1 ? frameMax : Math.round(frameMax * ratio.value)));
const H = computed(() => (ratio.value >= 1 ? Math.round(frameMax / ratio.value) : frameMax));

function load(src: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => { image.value = img; resolve(); };
        img.onerror = () => reject(new Error('That photo could not be opened.'));
        img.src = src;
    });
}

/** Draws the photo covering a width×height frame with the current crop, rotation and filters. */
function draw(target: HTMLCanvasElement, width: number, height: number, scaleFactor = 1) {
    const ctx = target.getContext('2d');
    const img = image.value;
    if (!ctx || !img) return;
    ctx.save();
    ctx.clearRect(0, 0, target.width, target.height);
    ctx.fillStyle = '#e3edd5'; ctx.fillRect(0, 0, target.width, target.height);
    const turned = view.rotation % 180 !== 0;
    const w = turned ? img.naturalHeight : img.naturalWidth;
    const h = turned ? img.naturalWidth : img.naturalHeight;
    const cover = Math.max(width / w, height / h) * view.zoom;
    ctx.filter = filter.value || 'none';
    ctx.translate(width / 2 + view.x * scaleFactor, height / 2 + view.y * scaleFactor);
    ctx.rotate((view.rotation * Math.PI) / 180);
    ctx.scale(view.flip ? -cover : cover, cover);
    ctx.drawImage(img, -img.naturalWidth / 2, -img.naturalHeight / 2);
    ctx.restore();
}
function render() {
    const c = canvas.value;
    if (!c) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = W.value * dpr; c.height = H.value * dpr;
    c.getContext('2d')?.setTransform(1, 0, 0, 1, 0, 0);
    draw(c, W.value * dpr, H.value * dpr, dpr);
}
watch([view, tune, preset, image, W, H], () => nextTick(render), { deep: true });

/** Keeps the photo covering the frame so no empty corners show. */
function clampOffsets() {
    const img = image.value;
    if (!img) return;
    const turned = view.rotation % 180 !== 0;
    const w = turned ? img.naturalHeight : img.naturalWidth;
    const h = turned ? img.naturalWidth : img.naturalHeight;
    const cover = Math.max(W.value / w, H.value / h) * view.zoom;
    const maxX = Math.max(0, (w * cover - W.value) / 2), maxY = Math.max(0, (h * cover - H.value) / 2);
    view.x = Math.min(maxX, Math.max(-maxX, view.x));
    view.y = Math.min(maxY, Math.max(-maxY, view.y));
}
watch(() => [view.zoom, view.rotation], clampOffsets);

// Drag to reposition
let drag: { id: number; x: number; y: number } | null = null;
function down(e: PointerEvent) { drag = { id: e.pointerId, x: e.clientX, y: e.clientY }; (e.target as HTMLElement).setPointerCapture(e.pointerId); }
function move(e: PointerEvent) {
    if (!drag || drag.id !== e.pointerId) return;
    view.x += e.clientX - drag.x; view.y += e.clientY - drag.y;
    drag.x = e.clientX; drag.y = e.clientY;
    clampOffsets();
}
function up() { drag = null; }
function wheel(e: WheelEvent) { e.preventDefault(); view.zoom = Math.min(4, Math.max(1, view.zoom - e.deltaY * 0.0015)); }
function rotate(step: number) { view.rotation = (view.rotation + step + 360) % 360; }
function resetAll() { Object.assign(view, { zoom: 1, x: 0, y: 0, rotation: 0, flip: false }); Object.assign(tune, { brightness: 100, contrast: 100, saturation: 100 }); preset.value = 'original'; }

/** Exports the crop with its longest side at [size] pixels. */
function exportBlob(size: number, quality = 0.9): Promise<Blob> {
    const out = document.createElement('canvas');
    const scale = size / Math.max(W.value, H.value);
    out.width = Math.round(W.value * scale); out.height = Math.round(H.value * scale);
    draw(out, out.width, out.height, scale);
    return new Promise((resolve, reject) => out.toBlob(b => (b ? resolve(b) : reject(new Error('Could not prepare the photo.'))), 'image/jpeg', quality));
}

async function restyle(style: string | null) {
    if (restyling.value) return;
    error.value = ''; restyling.value = true;
    try {
        const body = new FormData();
        body.append('photo', await exportBlob(1024, 0.92), 'photo.jpg');
        if (style) body.append('style', style); else body.append('prompt', customPrompt.value.trim());
        body.append('subject', props.subject);
        const { data } = await appFetch<{ data: { image: string } }>('/photos/restyle', { method: 'POST', body });
        history.value.push(image.value?.src ?? props.source);
        await load(data.image);
        resetAll();
        tab.value = 'crop';
    } catch (e) { error.value = e instanceof Error ? e.message : 'The AI could not restyle the photo.'; }
    finally { restyling.value = false; }
}
async function undoAi() {
    const previous = history.value.pop();
    if (previous) { await load(previous); resetAll(); }
}

async function finish() {
    saving.value = true; error.value = '';
    try { emit('done', await exportBlob(props.outputSize, 0.9)); dialog.value?.close(); }
    catch (e) { error.value = e instanceof Error ? e.message : 'Could not prepare the photo.'; }
    finally { saving.value = false; }
}

onMounted(async () => {
    dialog.value?.showModal();
    try { await load(props.source); await nextTick(); render(); } catch (e) { error.value = e instanceof Error ? e.message : 'That photo could not be opened.'; }
});
</script>

<template>
    <dialog ref="dialog" class="editor" aria-labelledby="editor-title" @close="emit('close')">
        <div class="body">
            <header><h2 id="editor-title">{{ title }}</h2><button type="button" class="icon-btn" aria-label="Close" @click="dialog?.close()"><X :size="18" /></button></header>

            <div class="stage" :class="{ busy: restyling }" :style="{ width: W + 'px', height: H + 'px' }">
                <canvas ref="canvas" :style="{ width: W + 'px', height: H + 'px' }" aria-label="Photo preview. Drag to reposition." @pointerdown="down" @pointermove="move" @pointerup="up" @pointercancel="up" @wheel="wheel" />
                <div class="ring" :class="{ round }" aria-hidden="true" />
                <div v-if="restyling" class="painting"><LoaderCircle :size="26" class="spin" aria-hidden="true" /><span>Restyling with AI…</span><small>This can take up to a minute.</small></div>
            </div>
            <p class="hint muted">Drag to move · use the slider (or mouse wheel) to zoom</p>

            <nav class="tabs" aria-label="Editing tools">
                <button type="button" :aria-pressed="tab === 'crop'" @click="tab = 'crop'">Crop</button>
                <button type="button" :aria-pressed="tab === 'adjust'" @click="tab = 'adjust'">Adjust</button>
                <button type="button" :aria-pressed="tab === 'ai'" @click="tab = 'ai'"><Sparkles :size="14" aria-hidden="true" /> AI styles</button>
            </nav>

            <div v-if="tab === 'crop'" class="panel">
                <label class="slider"><ZoomIn :size="16" aria-hidden="true" /><span class="sr-only">Zoom</span><input v-model.number="view.zoom" type="range" min="1" max="4" step="0.01" /></label>
                <div class="row tools">
                    <button type="button" class="btn secondary" aria-label="Rotate left" @click="rotate(-90)"><RotateCcw :size="16" /></button>
                    <button type="button" class="btn secondary" aria-label="Rotate right" @click="rotate(90)"><RotateCw :size="16" /></button>
                    <button type="button" class="btn secondary" :aria-pressed="view.flip" aria-label="Flip horizontally" @click="view.flip = !view.flip"><FlipHorizontal2 :size="16" /></button>
                    <button type="button" class="btn secondary" @click="resetAll">Reset</button>
                    <button v-if="history.length" type="button" class="btn secondary" @click="undoAi"><Undo2 :size="15" aria-hidden="true" /> Undo AI</button>
                </div>
            </div>

            <div v-else-if="tab === 'adjust'" class="panel">
                <div class="presets" role="group" aria-label="Filters">
                    <button v-for="p in presets" :key="p.key" type="button" :aria-pressed="preset === p.key" @click="preset = p.key"><span class="swatch" :style="{ filter: p.filter || 'none' }" />{{ p.label }}</button>
                </div>
                <label class="slider labeled">Brightness<input v-model.number="tune.brightness" type="range" min="50" max="150" /></label>
                <label class="slider labeled">Contrast<input v-model.number="tune.contrast" type="range" min="50" max="150" /></label>
                <label class="slider labeled">Saturation<input v-model.number="tune.saturation" type="range" min="0" max="200" /></label>
            </div>

            <div v-else class="panel">
                <p class="muted small">Gemini redraws the photo in a new style. You can keep editing the result and only save if you like it.</p>
                <div class="styles" role="group" aria-label="AI styles">
                    <button v-for="s in styles" :key="s.key" type="button" :disabled="restyling" @click="restyle(s.key)"><span aria-hidden="true">{{ s.emoji }}</span>{{ s.label }}</button>
                </div>
                <form class="custom" @submit.prevent="customPrompt.trim() && restyle(null)">
                    <input v-model="customPrompt" maxlength="200" placeholder="Or describe a look, e.g. hiking in the mountains" :disabled="restyling" />
                    <button class="btn" :disabled="restyling || !customPrompt.trim()"><Sparkles :size="15" aria-hidden="true" /> Go</button>
                </form>
                <button v-if="history.length" type="button" class="btn secondary" @click="undoAi"><Undo2 :size="15" aria-hidden="true" /> Undo last AI style</button>
            </div>

            <p v-if="error" class="error" role="alert">{{ error }}</p>
            <div class="actions"><button type="button" class="btn secondary" :disabled="saving" @click="dialog?.close()">Cancel</button><button type="button" class="btn" :disabled="saving || restyling || !image" @click="finish">{{ saving ? 'Preparing…' : confirmLabel }}</button></div>
        </div>
    </dialog>
</template>

<style scoped>
.editor{border:0;border-radius:22px;padding:0;width:min(440px,calc(100vw - 16px));max-height:calc(100dvh - 16px);box-shadow:0 24px 60px #172f2a40;color:#172f2a}
.editor::backdrop{background:#172f2a99}
.body{display:grid;gap:12px;padding:18px}
header{display:flex;justify-content:space-between;align-items:center}
header h2{font-size:19px;font-weight:750}
.icon-btn{display:grid;place-items:center;width:38px;height:38px;border-radius:12px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.stage{position:relative;justify-self:center;border-radius:16px;overflow:hidden;touch-action:none;background:#e3edd5}
.stage canvas{display:block;cursor:grab}
.stage canvas:active{cursor:grabbing}
.ring{position:absolute;inset:0;pointer-events:none;box-shadow:inset 0 0 0 2px #ffffffcc;border-radius:16px}
.ring.round{border-radius:0;box-shadow:none;background:radial-gradient(circle closest-side at center,transparent calc(100% - 1px),#172f2a8c 100%)}
.painting{position:absolute;inset:0;display:grid;place-content:center;justify-items:center;gap:4px;background:#172f2aa6;color:#fff;font-weight:700}
.painting small{font-weight:400;opacity:.85}
.hint{text-align:center;font-size:12px;margin-top:-4px}
.tabs{display:flex;gap:4px;padding:4px;border-radius:12px;background:#edf2e6}
.tabs button{flex:1;display:inline-flex;align-items:center;justify-content:center;gap:5px;padding:8px;border:0;border-radius:9px;background:transparent;color:#507044;font:inherit;font-size:13px;font-weight:700;cursor:pointer}
.tabs button[aria-pressed="true"]{background:#fff;color:#214e3e;box-shadow:0 1px 4px #233d2614}
.panel{display:grid;gap:12px;min-height:150px;align-content:start}
.slider{display:flex;align-items:center;gap:10px;color:#507044}
.slider input{flex:1;accent-color:#214e3e}
.slider.labeled{display:grid;grid-template-columns:90px 1fr;font-size:13px;font-weight:650;color:#172f2a}
.tools{justify-content:center;gap:8px}
.tools .btn{min-height:42px;padding:8px 12px}
.tools .btn[aria-pressed="true"]{background:#e3edd5}
.presets{display:flex;gap:8px;overflow-x:auto;padding-bottom:4px;scrollbar-width:thin}
.presets button{display:grid;justify-items:center;gap:4px;padding:6px;border:2px solid transparent;border-radius:12px;background:none;font:inherit;font-size:12px;font-weight:650;color:#172f2a;cursor:pointer;flex-shrink:0}
.presets button[aria-pressed="true"]{border-color:#214e3e}
.swatch{width:48px;height:48px;border-radius:10px;background:linear-gradient(135deg,#f2b880,#6aa6a0 55%,#3a6b4f)}
.styles{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:8px}
.styles button{display:flex;align-items:center;gap:8px;padding:10px 12px;border:1px solid #dfe6da;border-radius:12px;background:#f7f8f4;font:inherit;font-size:13px;font-weight:650;color:#214e3e;cursor:pointer;text-align:left}
.styles button:hover:not(:disabled){background:#edf4e3}
.styles button:disabled{opacity:.5;cursor:progress}
.custom{display:flex;gap:8px}
.custom input{flex:1;min-width:0;border:1px solid #d6dfd5;border-radius:10px;padding:10px 12px;font:inherit;font-size:14px}
.small{font-size:12px}
.error{color:#9c3e2d;font-size:13px}
.actions{display:flex;justify-content:flex-end;gap:10px}
.actions .btn{min-height:46px}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(prefers-reduced-motion:reduce){.spin{animation:none}}
</style>

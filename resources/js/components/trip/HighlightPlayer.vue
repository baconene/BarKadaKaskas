<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { Download, LoaderCircle, Pause, Play, RotateCcw, Sparkles, X } from 'lucide-vue-next';
import { appFetch } from '@/lib/uploads';

/**
 * A day's highlight, story style: cover, then each itinerary stop with the photos posted around then, slow zoom and
 * pan, crossfades, captions and music; about 30 seconds. Everything is drawn on one canvas, so the same drawing
 * plays it here and records it as a video file ("Save video").
 */
type Slide = { time: string; title: string; location: string | null; photo_url: string; caption: string | null; duration: number };
type Highlight = { date: string; day_number: number; title: string; subtitle: string; cover_url: string | null; base_photo_url: string | null; music_url: string; cover_duration: number; outro_duration: number; slides: Slide[]; days: string[] };
const props = defineProps<{ tripId: number }>();
const emit = defineEmits<{ close: [] }>();

const W = 720, H = 1280, FADE = 0.6;
const data = ref<Highlight | null>(null);
const loading = ref(true);
const error = ref('');
const playing = ref(false);
const recording = ref(false);
const progress = ref(0);
const makingCover = ref(false);
const canvas = ref<HTMLCanvasElement | null>(null);
const audio = new Audio();
audio.crossOrigin = 'anonymous';
const images = new Map<string, HTMLImageElement>();
let frame = 0, startedAt = 0, pausedAt = 0;

type Part = { kind: 'cover' | 'photo' | 'outro'; start: number; end: number; slide?: Slide; index: number };
const timeline = computed<Part[]>(() => {
    const h = data.value; if (!h) return [];
    const parts: Part[] = []; let t = 0;
    parts.push({ kind: 'cover', start: 0, end: (t = h.cover_duration), index: 0 });
    h.slides.forEach((slide, i) => { parts.push({ kind: 'photo', start: t, end: t + slide.duration, slide, index: i + 1 }); t += slide.duration; });
    parts.push({ kind: 'outro', start: t, end: t + h.outro_duration, index: h.slides.length + 1 });
    return parts;
});
const total = computed(() => timeline.value.at(-1)?.end ?? 0);

async function load(date?: string) {
    loading.value = true; error.value = ''; stop();
    try {
        data.value = (await appFetch<{ data: Highlight }>(`/trips/${props.tripId}/highlights${date ? `?date=${date}` : ''}`)).data;
        audio.src = data.value.music_url;
        await Promise.all([data.value.cover_url, data.value.base_photo_url, ...data.value.slides.map(s => s.photo_url)].filter(Boolean).map(url => image(url!)));
        await nextTick(); draw(0);
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not load the highlight.'; }
    finally { loading.value = false; }
}
function image(url: string): Promise<HTMLImageElement> {
    const cached = images.get(url); if (cached?.complete) return Promise.resolve(cached);
    return new Promise(resolve => { const img = new Image(); img.crossOrigin = 'anonymous'; img.onload = () => resolve(img); img.onerror = () => resolve(img); img.src = url; images.set(url, img); });
}

// ---- Drawing ----------------------------------------------------------------------------------------------
function cover(ctx: CanvasRenderingContext2D, img: HTMLImageElement | undefined, t: number, seed: number) {
    if (!img || !img.naturalWidth) { ctx.fillStyle = '#214e3e'; ctx.fillRect(0, 0, W, H); return; }
    // Slow zoom and pan (Ken Burns), alternating direction per slide.
    const zoom = 1.05 + 0.12 * t, dir = seed % 2 ? 1 : -1;
    const scale = Math.max(W / img.naturalWidth, H / img.naturalHeight) * zoom;
    const w = img.naturalWidth * scale, h = img.naturalHeight * scale;
    ctx.drawImage(img, (W - w) / 2 + dir * 40 * (t - 0.5), (H - h) / 2 + 20 * (t - 0.5), w, h);
}
function shade(ctx: CanvasRenderingContext2D, from: number, alpha: number) {
    const g = ctx.createLinearGradient(0, from, 0, H); g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, `rgba(0,0,0,${alpha})`);
    ctx.fillStyle = g; ctx.fillRect(0, from, W, H - from);
}
function text(ctx: CanvasRenderingContext2D, value: string, x: number, y: number, size: number, weight = 700, color = '#fff', maxWidth = W - 112) {
    ctx.font = `${weight} ${size}px Inter, system-ui, sans-serif`; ctx.fillStyle = color; ctx.shadowColor = 'rgba(0,0,0,.35)'; ctx.shadowBlur = 12;
    const words = value.split(' '); let line = ''; let yy = y;
    for (const word of words) { const next = line ? `${line} ${word}` : word; if (ctx.measureText(next).width > maxWidth && line) { ctx.fillText(line, x, yy); line = word; yy += size * 1.15; } else line = next; }
    ctx.fillText(line, x, yy); ctx.shadowBlur = 0; return yy;
}
function part(ctx: CanvasRenderingContext2D, p: Part, local: number) {
    const h = data.value!; const t = Math.min(1, Math.max(0, local / (p.end - p.start)));
    if (p.kind === 'photo' && p.slide) {
        cover(ctx, images.get(p.slide.photo_url), t, p.index); shade(ctx, H * 0.5, 0.75);
        // Time pill and stop title in the lower third.
        ctx.font = '700 30px Inter, system-ui, sans-serif'; const pill = ctx.measureText(p.slide.time).width + 40;
        ctx.fillStyle = 'rgba(33,78,62,.92)'; ctx.beginPath(); ctx.roundRect(56, H - 330, pill, 54, 27); ctx.fill();
        ctx.fillStyle = '#e8f2d8'; ctx.fillText(p.slide.time, 76, H - 292);
        const y = text(ctx, p.slide.title, 56, H - 220, 56, 800);
        if (p.slide.location) text(ctx, p.slide.location.split(',').slice(0, 2).join(','), 56, y + 52, 28, 500, 'rgba(255,255,255,.85)');
        return;
    }
    const bg = images.get((h.cover_url ?? h.base_photo_url) || '');
    cover(ctx, bg, t, p.kind === 'cover' ? 0 : 1);
    if (p.kind === 'cover') {
        shade(ctx, H * 0.35, 0.8);
        ctx.font = '800 26px Inter, system-ui, sans-serif'; ctx.fillStyle = '#cfe0a5'; ctx.fillText('HIGHLIGHTS', 56, H - 380);
        const y = text(ctx, h.title, 56, H - 300, 72, 800);
        text(ctx, h.subtitle, 56, y + 64, 34, 600, 'rgba(255,255,255,.9)');
    } else {
        ctx.fillStyle = 'rgba(23,47,42,.55)'; ctx.fillRect(0, 0, W, H);
        ctx.textAlign = 'center';
        text(ctx, `That was Day ${h.day_number}`, W / 2, H / 2 - 20, 60, 800, '#fff', W - 120);
        text(ctx, 'BarkadaKasKas', W / 2, H / 2 + 50, 30, 600, '#cfe0a5', W - 120);
        ctx.textAlign = 'left';
    }
}
function bars(ctx: CanvasRenderingContext2D, now: number) {
    const parts = timeline.value; const gap = 8, top = 40, width = (W - 64 - gap * (parts.length - 1)) / parts.length;
    parts.forEach((p, i) => {
        const x = 32 + i * (width + gap); const fill = Math.min(1, Math.max(0, (now - p.start) / (p.end - p.start)));
        ctx.fillStyle = 'rgba(255,255,255,.35)'; ctx.fillRect(x, top, width, 6); ctx.fillStyle = '#fff'; ctx.fillRect(x, top, width * fill, 6);
    });
}
function draw(now: number) {
    const ctx = canvas.value?.getContext('2d'); if (!ctx || !data.value) return;
    const parts = timeline.value; const i = Math.max(0, parts.findIndex(p => now < p.end)); const current = parts[i === -1 ? parts.length - 1 : i];
    ctx.globalAlpha = 1; part(ctx, current, now - current.start);
    // Crossfade into the next part during its first FADE seconds.
    const next = parts[i + 1]; const into = next ? now - (current.end - FADE) : -1;
    if (next && into > 0) { ctx.globalAlpha = Math.min(1, into / FADE); part(ctx, next, 0); ctx.globalAlpha = 1; }
    bars(ctx, now); progress.value = total.value ? now / total.value : 0;
}

// ---- Playback ---------------------------------------------------------------------------------------------
function tick() {
    const now = (performance.now() - startedAt) / 1000;
    if (now >= total.value) { draw(total.value); finish(); return; }
    draw(now); frame = requestAnimationFrame(tick);
}
function play() {
    if (!data.value?.slides.length) return;
    startedAt = performance.now() - pausedAt * 1000; audio.currentTime = pausedAt; audio.volume = 1;
    audio.play().catch(() => { /* muted autoplay policy: the video still plays */ }); playing.value = true; frame = requestAnimationFrame(tick);
}
function pause() { cancelAnimationFrame(frame); pausedAt = (performance.now() - startedAt) / 1000; audio.pause(); playing.value = false; }
function stop() { cancelAnimationFrame(frame); audio.pause(); pausedAt = 0; playing.value = false; }
function restart() { stop(); play(); }
let onFinished: (() => void) | null = null;
function finish() { cancelAnimationFrame(frame); audio.pause(); playing.value = false; pausedAt = 0; onFinished?.(); onFinished = null; }

/** Records the canvas and the music while the highlight plays from the start, then downloads the video. */
let audioContext: AudioContext | null = null, audioDestination: MediaStreamAudioDestinationNode | null = null;
async function saveVideo() {
    if (!canvas.value || recording.value) return;
    const type = ['video/mp4;codecs=avc1', 'video/webm;codecs=vp9,opus', 'video/webm'].find(t => MediaRecorder.isTypeSupported(t));
    if (!type) { error.value = 'This browser can’t record video. Try Chrome or Edge.'; return; }
    if (!audioContext) { audioContext = new AudioContext(); const source = audioContext.createMediaElementSource(audio); audioDestination = audioContext.createMediaStreamDestination(); source.connect(audioDestination); source.connect(audioContext.destination); }
    await audioContext.resume();
    const stream = new MediaStream([...canvas.value.captureStream(30).getVideoTracks(), ...(audioDestination?.stream.getAudioTracks() ?? [])]);
    const recorder = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 6_000_000 });
    const chunks: Blob[] = []; recorder.ondataavailable = e => e.data.size && chunks.push(e.data);
    recorder.onstop = () => {
        recording.value = false;
        const url = URL.createObjectURL(new Blob(chunks, { type: type.split(';')[0] }));
        const a = document.createElement('a'); a.href = url; a.download = `highlight-day-${data.value?.day_number ?? ''}.${type.includes('mp4') ? 'mp4' : 'webm'}`; a.click();
        setTimeout(() => URL.revokeObjectURL(url), 10_000);
    };
    recording.value = true; stop(); onFinished = () => setTimeout(() => recorder.stop(), 300);
    recorder.start(); play();
}

async function makeCover() {
    if (!data.value || makingCover.value) return;
    makingCover.value = true; error.value = '';
    try {
        const { data: result } = await appFetch<{ data: { cover_url: string } }>(`/trips/${props.tripId}/highlights/cover?date=${data.value.date}`, { method: 'POST' });
        await image(result.cover_url); data.value.cover_url = result.cover_url; if (!playing.value) draw(pausedAt);
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not make the cover.'; }
    finally { makingCover.value = false; }
}
const dayLabel = (date: string) => new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`));
function onKey(e: KeyboardEvent) { if (e.key === 'Escape') emit('close'); if (e.key === ' ') { e.preventDefault(); if (playing.value) pause(); else play(); } }
onMounted(() => { load(); document.addEventListener('keydown', onKey); });
onBeforeUnmount(() => { stop(); document.removeEventListener('keydown', onKey); audioContext?.close(); });
</script>

<template>
    <div class="highlight" role="dialog" aria-modal="true" aria-label="Day highlight">
        <button type="button" class="close" aria-label="Close highlight" @click="emit('close')"><X :size="22" /></button>
        <div class="stage">
            <canvas ref="canvas" :width="W" :height="H" class="screen" @click="playing ? pause() : play()" />
            <div v-if="loading" class="overlay"><LoaderCircle :size="30" class="spin" /> Putting the day together…</div>
            <div v-else-if="data && !data.slides.length" class="overlay empty">
                <strong>No photos for this day yet</strong>
                <span>Post photos during the day and they'll appear here with the itinerary.</span>
            </div>
            <button v-else-if="data && !playing && !recording" type="button" class="big-play" aria-label="Play highlight" @click="play"><Play :size="40" fill="currentColor" /></button>
            <div v-if="recording" class="rec">● Recording video… {{ Math.round(progress * 100) }}%</div>
        </div>
        <div class="controls">
            <div v-if="data && data.days.length > 1" class="days" role="group" aria-label="Choose a day">
                <button v-for="d in data.days" :key="d" type="button" :aria-pressed="d === data.date" :disabled="recording" @click="load(d)">{{ dayLabel(d) }}</button>
            </div>
            <div class="row">
                <button type="button" class="ctl" :disabled="!data?.slides.length || recording" @click="playing ? pause() : play()"><Pause v-if="playing" :size="18" /><Play v-else :size="18" /> {{ playing ? 'Pause' : 'Play' }}</button>
                <button type="button" class="ctl" :disabled="!data?.slides.length || recording" @click="restart"><RotateCcw :size="18" /> Replay</button>
                <button type="button" class="ctl" :disabled="!data?.slides.length || makingCover || recording" @click="makeCover"><LoaderCircle v-if="makingCover" :size="18" class="spin" /><Sparkles v-else :size="18" /> {{ data?.cover_url ? 'Remake AI cover' : 'AI cover' }}</button>
                <button type="button" class="ctl primary" :disabled="!data?.slides.length || recording" @click="saveVideo"><Download :size="18" /> {{ recording ? 'Recording…' : 'Save video' }}</button>
            </div>
            <p class="hint">Save video records the highlight as it plays (about {{ Math.round(total) }} seconds). The AI cover uses one of your AI edits.</p>
            <p v-if="error" class="error" role="alert">{{ error }}</p>
        </div>
    </div>
</template>

<style scoped>
.highlight{position:fixed;inset:0;z-index:80;background:#0d1512;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;padding:16px}
.close{position:absolute;top:14px;right:14px;z-index:2;display:grid;place-items:center;width:44px;height:44px;border-radius:50%;border:0;background:rgba(255,255,255,.12);color:#fff;cursor:pointer}
.stage{position:relative;height:min(72vh,calc((100vw - 32px) * 16 / 9));aspect-ratio:9/16;border-radius:18px;overflow:hidden;background:#214e3e}
.screen{width:100%;height:100%;display:block;cursor:pointer}
.overlay{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;color:#fff;font-weight:600;text-align:center;padding:24px;background:rgba(13,21,18,.55)}
.overlay.empty span{font-weight:400;opacity:.85;font-size:14px;max-width:260px}
.big-play{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);display:grid;place-items:center;width:84px;height:84px;border-radius:50%;border:0;background:rgba(255,255,255,.9);color:#214e3e;cursor:pointer;padding-left:6px}
.rec{position:absolute;left:12px;top:56px;padding:6px 10px;border-radius:999px;background:rgba(194,65,12,.92);color:#fff;font-size:12px;font-weight:700}
.controls{display:grid;gap:10px;justify-items:center;width:min(560px,100%)}
.days{display:flex;gap:6px;flex-wrap:wrap;justify-content:center}
.days button{padding:6px 12px;border-radius:999px;border:1px solid rgba(255,255,255,.25);background:transparent;color:#fff;font:inherit;font-size:12px;cursor:pointer}
.days button[aria-pressed="true"]{background:#fff;color:#214e3e;font-weight:700}
.row{display:flex;gap:8px;flex-wrap:wrap;justify-content:center}
.ctl{display:inline-flex;align-items:center;gap:6px;min-height:44px;padding:8px 14px;border-radius:12px;border:1px solid rgba(255,255,255,.22);background:rgba(255,255,255,.08);color:#fff;font:inherit;font-size:14px;font-weight:600;cursor:pointer}
.ctl.primary{background:#e8f2d8;color:#214e3e;border-color:transparent}
.ctl:disabled{opacity:.45;cursor:default}
.hint{font-size:12px;color:rgba(255,255,255,.6);text-align:center}
.error{font-size:13px;color:#ffb4a2;text-align:center}
.spin{animation:spin 1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
</style>

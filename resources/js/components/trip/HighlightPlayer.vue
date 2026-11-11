<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { Download, LoaderCircle, Pause, Play, RotateCcw, Sparkles, X } from 'lucide-vue-next';
import { appFetch } from '@/lib/uploads';
import { Bike, BusFront, CarFront, CarTaxiFront, Footprints, MapPin, Plane, Route, Sailboat, Truck } from 'lucide';

/**
 * A day's highlight as a travel reel: cover; then for each stop, its transport icon travelling along the route on a
 * map to the place, then that place's photos in quick reel-style cuts (place sticker, who posted, caption); outro;
 * with music. Everything is drawn on one canvas, so the same drawing plays it here and records it as a video file.
 */
type Slide = { time: string; title: string; location: string | null; photo_url: string; caption: string | null; duration: number };
type CoverInfo = { eyebrow: string; title: string; date: string; destination: string | null; route: string | null; stats: string };
type Point = { x: number; y: number };
type Travel = { mode: string; label: string; from: string; map: { url: string; from: Point; to: Point } | null; duration: number };
type ScenePhoto = { url: string; caption: string | null; by: string | null; duration: number };
type Scene = { time: string; title: string; location: string | null; travel: Travel | null; photos: ScenePhoto[] };
type Highlight = { scenes?: Scene[]; date: string; day_number: number; title: string; subtitle: string; cover_url: string | null; base_photo_url: string | null; cover?: CoverInfo; music_url: string; cover_duration: number; outro_duration: number; slides: Slide[]; days: string[] };
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

type Part = { kind: 'cover' | 'travel' | 'photo' | 'outro'; start: number; end: number; index: number; scene?: Scene; sceneIndex: number; photo?: ScenePhoto; photoIndex?: number };
/** Scenes from the server; an older server only sends slides, which become scenes without travel. */
const scenes = computed<Scene[]>(() => {
    const h = data.value; if (!h) return [];
    if (h.scenes) return h.scenes;
    return h.slides.map(s => ({ time: s.time, title: s.title, location: s.location, travel: null, photos: [{ url: s.photo_url, caption: s.caption, by: null, duration: s.duration }] }));
});
const timeline = computed<Part[]>(() => {
    const h = data.value; if (!h) return [];
    const parts: Part[] = []; let t = 0;
    const push = (p: Omit<Part, 'start' | 'end' | 'index'>, length: number) => { parts.push({ ...p, start: t, end: t + length, index: parts.length }); t += length; };
    push({ kind: 'cover', sceneIndex: -1 }, h.cover_duration);
    scenes.value.forEach((scene, i) => {
        if (scene.travel) push({ kind: 'travel', scene, sceneIndex: i }, scene.travel.duration);
        scene.photos.forEach((photo, j) => push({ kind: 'photo', scene, sceneIndex: i, photo, photoIndex: j }, photo.duration));
    });
    push({ kind: 'outro', sceneIndex: scenes.value.length }, h.outro_duration);
    return parts;
});
const total = computed(() => timeline.value.at(-1)?.end ?? 0);

async function load(date?: string) {
    loading.value = true; error.value = ''; stop();
    try {
        data.value = (await appFetch<{ data: Highlight }>(`/trips/${props.tripId}/highlights${date ? `?date=${date}` : ''}`)).data;
        audio.src = data.value.music_url;
        const urls = [data.value.cover_url, data.value.base_photo_url, ...scenes.value.flatMap(s => [...s.photos.map(p => p.url), s.travel?.map?.url])];
        await Promise.all([...urls.filter(Boolean).map(url => image(url!)), ...Object.keys(ICONS).map(mode => icon(mode)), icon('pin')]);
        await nextTick(); draw(0);
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not load the highlight.'; }
    finally { loading.value = false; }
}
function image(url: string): Promise<HTMLImageElement> {
    const cached = images.get(url); if (cached?.complete) return Promise.resolve(cached);
    return new Promise(resolve => { const img = new Image(); img.crossOrigin = 'anonymous'; img.onload = () => resolve(img); img.onerror = () => resolve(img); img.src = url; images.set(url, img); });
}

// Transport icons (Lucide), drawn as images so they can sit on the canvas.
type IconNode = [string, Record<string, string | number>, IconNode[]?];
const ICONS: Record<string, unknown> = { car: CarFront, van: BusFront, taxi: CarTaxiFront, motorcycle: Bike, tricycle: Truck, walking: Footprints, bicycle: Bike, transit: BusFront, ferry: Sailboat, flight: Plane, other: Route };
const icons = new Map<string, HTMLImageElement>();
function svg(node: IconNode, color: string): string {
    const [tag, attrs, children] = node;
    const a = Object.entries({ ...attrs, ...(tag === 'svg' ? { stroke: color, width: 96, height: 96 } : {}) }).map(([k, v]) => `${k}="${v}"`).join(' ');
    return `<${tag} ${a}>${(children ?? []).map(c => svg(c, color)).join('')}</${tag}>`;
}
function icon(mode: string): Promise<HTMLImageElement> {
    const key = mode === 'pin' ? 'pin' : (ICONS[mode] ? mode : 'other');
    const node = (key === 'pin' ? MapPin : ICONS[key]) as IconNode;
    return image(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg(node, '#214e3e'))}`).then(img => { icons.set(key, img); return img; });
}
const iconFor = (mode: string) => icons.get(ICONS[mode] ? mode : 'other');
const ease = (t: number) => t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2;

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
function pill(ctx: CanvasRenderingContext2D, x: number, y: number, label: string, size: number, bg: string, fg: string, img?: HTMLImageElement) {
    ctx.font = `700 ${size}px Inter, system-ui, sans-serif`;
    const pad = size * 0.7, iconSize = img ? size * 1.3 : 0, w = ctx.measureText(label).width + pad * 2 + (img ? iconSize + size * 0.35 : 0), h = size * 2;
    ctx.fillStyle = bg; ctx.shadowColor = 'rgba(0,0,0,.25)'; ctx.shadowBlur = 16; ctx.beginPath(); ctx.roundRect(x, y, w, h, h / 2); ctx.fill(); ctx.shadowBlur = 0;
    if (img) ctx.drawImage(img, x + pad * 0.8, y + (h - iconSize) / 2, iconSize, iconSize);
    ctx.fillStyle = fg; ctx.fillText(label, x + pad + (img ? iconSize + size * 0.35 : 0), y + h * 0.66);
    return w;
}
/** Route: a gentle arc (higher for flights) from one stop to the next, in canvas pixels. */
function arc(a: Point, b: Point, mode: string) {
    const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2, dx = b.x - a.x, dy = b.y - a.y;
    const bend = mode === 'flight' ? 0.35 : 0.12;
    const c = { x: mx - dy * bend, y: my + dx * bend };
    return (t: number): Point => ({ x: (1 - t) ** 2 * a.x + 2 * (1 - t) * t * c.x + t * t * b.x, y: (1 - t) ** 2 * a.y + 2 * (1 - t) * t * c.y + t * t * b.y });
}
function travel(ctx: CanvasRenderingContext2D, p: Part, t: number) {
    const scene = p.scene!, tr = scene.travel!; const img = tr.map ? images.get(tr.map.url) : undefined;
    const move = ease(Math.min(1, Math.max(0, (t - 0.08) / 0.58)));   // arrive before the crossfade into the photos
    let a: Point, b: Point;
    if (img?.naturalWidth && tr.map) {
        a = { x: tr.map.from.x * W, y: tr.map.from.y * H }; b = { x: tr.map.to.x * W, y: tr.map.to.y * H };
        // The map drifts in towards the destination as the icon travels.
        const zoom = 1 + 0.12 * t, fx = a.x + (b.x - a.x) * move, fy = a.y + (b.y - a.y) * move;
        ctx.save(); ctx.translate(fx, fy); ctx.scale(zoom, zoom); ctx.translate(-fx, -fy);
        ctx.drawImage(img, 0, 0, W, H);
        ctx.fillStyle = 'rgba(13,21,18,.12)'; ctx.fillRect(0, 0, W, H);
        route(ctx, a, b, tr.mode, move, t, '#214e3e');
        ctx.restore();
    } else {
        // No map: the icon crosses a dotted line between the two place names.
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, '#2d6a55'); g.addColorStop(1, '#0d1512');
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
        a = { x: 110, y: H * 0.52 }; b = { x: W - 110, y: H * 0.46 };
        route(ctx, a, b, tr.mode, move, t, '#e8f2d8');
        ctx.textAlign = 'center';
        text(ctx, tr.from, a.x + 40, a.y + 110, 26, 600, 'rgba(255,255,255,.85)', 260);
        text(ctx, scene.title, b.x - 40, b.y - 80, 26, 700, '#fff', 260);
        ctx.textAlign = 'left';
    }
    // Header card: transport, from → to, arrival time.
    ctx.fillStyle = 'rgba(255,255,255,.94)'; ctx.shadowColor = 'rgba(0,0,0,.25)'; ctx.shadowBlur = 24;
    ctx.beginPath(); ctx.roundRect(40, 90, W - 80, 196, 28); ctx.fill(); ctx.shadowBlur = 0;
    pill(ctx, 68, 116, tr.label, 22, '#e8f2d8', '#214e3e', iconFor(tr.mode));
    ctx.font = '700 24px Inter, system-ui, sans-serif'; ctx.fillStyle = '#5b6b64'; ctx.textAlign = 'right'; ctx.fillText(scene.time, W - 68, 148); ctx.textAlign = 'left';
    text(ctx, `${tr.from}  →`, 68, 210, 26, 500, '#5b6b64', W - 136);
    ctx.shadowBlur = 0; ctx.font = '800 36px Inter, system-ui, sans-serif'; ctx.fillStyle = '#17302a';
    const title = scene.title.length > 32 ? `${scene.title.slice(0, 31)}…` : scene.title; ctx.fillText(title, 68, 258);
}
function route(ctx: CanvasRenderingContext2D, a: Point, b: Point, mode: string, move: number, t: number, color: string) {
    const at = arc(a, b, mode);
    // Faint full route, then the travelled part.
    ctx.lineCap = 'round'; ctx.setLineDash([2, 18]); ctx.lineWidth = 10; ctx.strokeStyle = color; ctx.globalAlpha *= 0.45;
    ctx.beginPath(); for (let i = 0; i <= 60; i++) { const q = at(i / 60); if (i) ctx.lineTo(q.x, q.y); else ctx.moveTo(q.x, q.y); }
    ctx.stroke(); ctx.globalAlpha /= 0.45; ctx.setLineDash([]); ctx.lineWidth = 12; ctx.strokeStyle = color;
    ctx.beginPath(); for (let i = 0; i <= 60; i++) { const q = at((i / 60) * move); if (i) ctx.lineTo(q.x, q.y); else ctx.moveTo(q.x, q.y); } ctx.stroke();
    // Start dot; destination pin pops in on arrival.
    ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(a.x, a.y, 16, 0, Math.PI * 2); ctx.fill(); ctx.fillStyle = '#214e3e'; ctx.beginPath(); ctx.arc(a.x, a.y, 9, 0, Math.PI * 2); ctx.fill();
    // The transport icon in a white disc, gently bobbing.
    const q = at(move), img = iconFor(mode), r = 46, bob = Math.sin(t * Math.PI * 6) * 4 * (move < 1 ? 1 : 0);
    ctx.fillStyle = '#fff'; ctx.shadowColor = 'rgba(0,0,0,.3)'; ctx.shadowBlur = 18; ctx.beginPath(); ctx.arc(q.x, q.y + bob, r, 0, Math.PI * 2); ctx.fill(); ctx.shadowBlur = 0;
    ctx.strokeStyle = '#214e3e'; ctx.lineWidth = 5; ctx.stroke();
    if (img) ctx.drawImage(img, q.x - 28, q.y + bob - 28, 56, 56);
    // On arrival a pin pops up above the icon.
    const pop = Math.min(1, Math.max(0, (t - 0.6) / 0.1)), pin = icons.get('pin');
    if (pin && pop > 0) {
        const size = 76 * (pop < 1 ? 0.5 + 0.7 * pop : 1), cx = b.x, cy = b.y - r - size * 0.55;
        ctx.fillStyle = '#fff'; ctx.shadowColor = 'rgba(0,0,0,.3)'; ctx.shadowBlur = 14; ctx.beginPath(); ctx.arc(cx, cy, size * 0.55, 0, Math.PI * 2); ctx.fill(); ctx.shadowBlur = 0;
        ctx.drawImage(pin, cx - size * 0.4, cy - size * 0.42, size * 0.8, size * 0.8);
    }
}
/** A reel frame: blurred fill behind the photo, a quick punch-in, place sticker, poster and caption. */
function reel(ctx: CanvasRenderingContext2D, p: Part, t: number, local: number) {
    const scene = p.scene!, photo = p.photo!, img = images.get(photo.url);
    ctx.fillStyle = '#0d1512'; ctx.fillRect(0, 0, W, H);
    if (img?.naturalWidth) {
        const ratio = img.naturalWidth / img.naturalHeight;
        // Background: the same photo, blurred and dark (portrait photos simply fill the screen).
        const fill = Math.max(W / img.naturalWidth, H / img.naturalHeight);
        if (ratio > 0.68) {
            ctx.save(); ctx.filter = 'blur(28px) brightness(.55)'; ctx.drawImage(img, (W - img.naturalWidth * fill * 1.1) / 2, (H - img.naturalHeight * fill * 1.1) / 2, img.naturalWidth * fill * 1.1, img.naturalHeight * fill * 1.1); ctx.restore();
        }
        const base = ratio > 0.68 ? W / img.naturalWidth : fill;
        const punch = local < 0.22 ? 1.1 - 0.1 * ease(local / 0.22) : 1 + 0.05 * (t - 0.1);
        const w = img.naturalWidth * base * punch, h = img.naturalHeight * base * punch, dir = (p.photoIndex ?? 0) % 2 ? 1 : -1;
        ctx.drawImage(img, (W - w) / 2 + dir * 18 * t, (H - h) / 2 - (ratio > 0.68 ? 40 : 0), w, h);
    }
    // Quick flash on each cut.
    if (local < 0.12) { ctx.fillStyle = `rgba(255,255,255,${0.35 * (1 - local / 0.12)})`; ctx.fillRect(0, 0, W, H); }
    const g = ctx.createLinearGradient(0, 0, 0, 300); g.addColorStop(0, 'rgba(0,0,0,.45)'); g.addColorStop(1, 'rgba(0,0,0,0)'); ctx.fillStyle = g; ctx.fillRect(0, 0, W, 300);
    shade(ctx, H * 0.62, 0.8);
    // Place sticker and time.
    const title = scene.title.length > 26 ? `${scene.title.slice(0, 25)}…` : scene.title;
    const sw = pill(ctx, 36, 84, title, 26, 'rgba(255,255,255,.95)', '#17302a', icons.get('pin'));
    pill(ctx, 36 + sw + 10, 84, scene.time, 22, 'rgba(33,78,62,.92)', '#e8f2d8');
    // Photo counter for this place.
    if (scene.photos.length > 1) {
        ctx.font = '700 22px Inter, system-ui, sans-serif'; ctx.fillStyle = 'rgba(255,255,255,.9)'; ctx.textAlign = 'right';
        ctx.fillText(`${(p.photoIndex ?? 0) + 1}/${scene.photos.length}`, W - 40, 122); ctx.textAlign = 'left';
    }
    // Poster and caption, bottom left like a reel.
    let y = H - 200;
    if (photo.by) {
        ctx.fillStyle = '#e8f2d8'; ctx.beginPath(); ctx.arc(66, y, 30, 0, Math.PI * 2); ctx.fill();
        ctx.font = '800 28px Inter, system-ui, sans-serif'; ctx.fillStyle = '#214e3e'; ctx.textAlign = 'center'; ctx.fillText(photo.by.charAt(0).toUpperCase(), 66, y + 10); ctx.textAlign = 'left';
        text(ctx, photo.by, 110, y + 10, 28, 700);
        y += 64;
    }
    if (photo.caption) text(ctx, photo.caption.length > 90 ? `${photo.caption.slice(0, 89)}…` : photo.caption, 40, y, 30, 500, '#fff', W - 80);
    else if (scene.location) text(ctx, scene.location.split(',').slice(0, 2).join(','), 40, y, 26, 500, 'rgba(255,255,255,.85)', W - 80);
}
function part(ctx: CanvasRenderingContext2D, p: Part, local: number) {
    const h = data.value!; const t = Math.min(1, Math.max(0, local / (p.end - p.start)));
    if (p.kind === 'travel') { travel(ctx, p, t); return; }
    if (p.kind === 'photo') { reel(ctx, p, t, local); return; }
    if (p.kind === 'cover') {
        cover(ctx, images.get((h.cover_url ?? h.base_photo_url) || ''), t, 0);
        shade(ctx, H * 0.3, 0.85);
        const c = h.cover;
        // Caption block, bottom up: stats, route, place; date and title above.
        ctx.font = '800 26px Inter, system-ui, sans-serif'; ctx.fillStyle = '#cfe0a5';
        ctx.fillText(`HIGHLIGHTS · ${(c?.eyebrow ?? `Day ${h.day_number}`).toUpperCase()}`, 56, H - 520);
        let y = text(ctx, c?.title ?? h.title, 56, H - 450, 68, 800);
        y = text(ctx, c?.date ?? h.subtitle, 56, y + 58, 34, 600, 'rgba(255,255,255,.95)');
        ctx.fillStyle = 'rgba(255,255,255,.45)'; ctx.fillRect(56, y + 30, 80, 4);
        if (c?.destination) y = text(ctx, c.destination, 56, y + 82, 30, 600, '#fff');
        if (c?.route) y = text(ctx, c.route, 56, y + 44, 28, 500, 'rgba(255,255,255,.85)');
        if (c?.stats) text(ctx, c.stats, 56, y + 44, 26, 500, 'rgba(232,242,216,.9)');
    } else {
        // Outro on a plain background (no photo repeated).
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, '#214e3e'); g.addColorStop(1, '#0d1512');
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
        ctx.textAlign = 'center';
        text(ctx, `That was Day ${h.day_number}`, W / 2, H / 2 - 40, 60, 800, '#fff', W - 120);
        if (h.cover?.title) text(ctx, h.cover.title, W / 2, H / 2 + 30, 32, 600, 'rgba(255,255,255,.85)', W - 120);
        text(ctx, 'BarkadaKasKas', W / 2, H / 2 + 90, 28, 600, '#cfe0a5', W - 120);
        ctx.textAlign = 'left';
    }
}
/** One progress bar per section: cover, each place (trip there + photos), outro. */
function bars(ctx: CanvasRenderingContext2D, now: number) {
    const sections: { start: number; end: number; scene: number }[] = [];
    for (const p of timeline.value) { const last = sections.at(-1); if (last && last.scene === p.sceneIndex) last.end = p.end; else sections.push({ start: p.start, end: p.end, scene: p.sceneIndex }); }
    const gap = 8, top = 40, width = (W - 64 - gap * (sections.length - 1)) / sections.length;
    sections.forEach((p, i) => {
        const x = 32 + i * (width + gap); const fill = Math.min(1, Math.max(0, (now - p.start) / (p.end - p.start)));
        ctx.fillStyle = 'rgba(255,255,255,.35)'; ctx.fillRect(x, top, width, 6); ctx.fillStyle = '#fff'; ctx.fillRect(x, top, width * fill, 6);
    });
}
function draw(now: number) {
    const ctx = canvas.value?.getContext('2d'); if (!ctx || !data.value) return;
    const parts = timeline.value; const i = Math.max(0, parts.findIndex(p => now < p.end)); const current = parts[i === -1 ? parts.length - 1 : i];
    ctx.globalAlpha = 1; part(ctx, current, now - current.start);
    // Crossfade into the next part during its first FADE seconds (reel photos at the same place just cut).
    const next = parts[i + 1]; const cut = next?.kind === 'photo' && current.kind === 'photo' && next.sceneIndex === current.sceneIndex;
    const into = next && !cut ? now - (current.end - FADE) : -1;
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

<script setup lang="ts">
import { Camera, ChevronLeft, ChevronRight, Clapperboard, Heart, ImagePlus, MapPin, MessageCircle, Pencil, Sparkles, Trash2, X } from 'lucide-vue-next';
import PostComments from '@/components/trip/PostComments.vue';
import HighlightPlayer from '@/components/trip/HighlightPlayer.vue';
import LoadingImage from '@/components/LoadingImage.vue';
import PhotoEditor from '@/components/PhotoEditor.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { appFetch, shrinkImage } from '@/lib/uploads';
import { currentPosition, readPhotoLocation } from '@/lib/photoLocation';

/** The trip's photo feed: everyone on the trip can post photos with a caption and leave hearts. */
type Post = { id: number; caption: string | null; created_at: string; author: { member_id: number; name: string; avatar_url: string | null }; photos: { url: string; width: number | null; height: number | null }[]; likes_count: number; liked: boolean; can_delete: boolean; comments_count?: number; place?: { name: string; itinerary_item_id: number | null } | null; can_edit_place?: boolean };
// The day highlight (30-second story of today's stops and photos).
const showHighlight = ref(false);
// Posts whose comments are open.
const openComments = ref(new Set<number>());
function toggleComments(id: number) { const next = new Set(openComments.value); if (next.has(id)) next.delete(id); else next.add(id); openComments.value = next; }
const props = defineProps<{ tripId: number; tripName: string; readOnly?: boolean; stops?: { id: number; title: string; starts_at: string }[]; timezone?: string }>();

// The place on a post: shown under the poster's name; the poster or organizer can pick a stop, type a place, or remove it.
const placing = ref<Post | null>(null);
const placeName = ref('');
const placeError = ref('');
const placeBusy = ref(false);
const stopDay = (iso: string) => new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: props.timezone || 'Asia/Manila' }).format(new Date(iso));
function editPlace(post: Post) { placing.value = post; placeName.value = post.place && !post.place.itinerary_item_id ? post.place.name : ''; placeError.value = ''; }
async function savePlace(choice: { itinerary_item_id?: number; name?: string; clear?: boolean }) {
    if (!placing.value || placeBusy.value) return;
    placeBusy.value = true; placeError.value = '';
    const body = new FormData(); body.append('_method', 'PATCH');
    if (choice.itinerary_item_id) body.append('itinerary_item_id', String(choice.itinerary_item_id));
    if (choice.name) body.append('name', choice.name);
    if (choice.clear) body.append('clear', '1');
    try {
        const { data } = await appFetch<{ data: Post }>(`/trips/${props.tripId}/posts/${placing.value.id}/place`, { method: 'POST', body });
        const i = posts.value.findIndex(p => p.id === data.id); if (i >= 0) posts.value[i] = { ...posts.value[i], place: data.place };
        placing.value = null;
    } catch (e) { placeError.value = e instanceof Error ? e.message : 'Could not change the place.'; }
    finally { placeBusy.value = false; }
}

const posts = ref<Post[]>([]);
const nextBefore = ref<number | null>(null);
const loading = ref(false);
const loaded = ref(false);
const error = ref('');

async function load(more = false) {
    if (loading.value) return;
    loading.value = true; error.value = '';
    try {
        const page = await appFetch<{ data: Post[]; next_before: number | null }>(`/trips/${props.tripId}/posts${more && nextBefore.value ? `?before=${nextBefore.value}` : ''}`);
        posts.value = more ? [...posts.value, ...page.data] : page.data;
        nextBefore.value = page.next_before;
        loaded.value = true;
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not load photos.'; }
    finally { loading.value = false; }
}
onMounted(() => load());

const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]!.toUpperCase()).join('');
const when = (iso: string) => {
    const seconds = (Date.now() - Date.parse(iso)) / 1000;
    if (seconds < 60) return 'just now';
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
    return new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
};

async function toggleLike(post: Post) {
    // Optimistic: flip now, reconcile with the server's count.
    post.liked = !post.liked; post.likes_count += post.liked ? 1 : -1;
    try {
        const { data } = await appFetch<{ data: { liked: boolean; likes_count: number } }>(`/trips/${props.tripId}/posts/${post.id}/like`, { method: 'POST' });
        post.liked = data.liked; post.likes_count = data.likes_count;
    } catch { post.liked = !post.liked; post.likes_count += post.liked ? 1 : -1; }
}
async function remove(post: Post) {
    if (!confirm('Delete this post and its photos for everyone?')) return;
    try { await appFetch(`/trips/${props.tripId}/posts/${post.id}`, { method: 'DELETE' }); posts.value = posts.value.filter(p => p.id !== post.id); }
    catch (e) { error.value = e instanceof Error ? e.message : 'Could not delete the post.'; }
}

// Composer
const composing = ref(false);
const dialog = ref<HTMLDialogElement | null>(null);
const picked = ref<{ file: File; url: string }[]>([]);
const caption = ref('');
const posting = ref(false);
const postError = ref('');
const fileInput = ref<HTMLInputElement | null>(null);
async function openComposer(pick = true) {
    composing.value = true; postError.value = '';
    await nextTick(); dialog.value?.showModal();
    if (pick && !picked.value.length) fileInput.value?.click();
}
function closeComposer() { dialog.value?.close(); }
function onClosed() { composing.value = false; if (!posting.value) { picked.value.forEach(p => URL.revokeObjectURL(p.url)); picked.value = []; caption.value = ''; } }
function addFiles(event: Event) {
    const files = Array.from((event.target as HTMLInputElement).files ?? []).filter(f => f.type.startsWith('image/'));
    const room = 10 - picked.value.length;
    if (files.length > room) postError.value = 'You can post up to 10 photos at a time.';
    picked.value = [...picked.value, ...files.slice(0, room).map(file => ({ file, url: URL.createObjectURL(file) }))];
    (event.target as HTMLInputElement).value = ''; // lets the same camera/file be picked again
}
function removePicked(index: number) { URL.revokeObjectURL(picked.value[index].url); picked.value.splice(index, 1); }
// Per-photo editing before posting: crop, filters and AI styles; the edited JPEG replaces the picked file.
const editingIndex = ref<number | null>(null);
function applyEdit(photo: Blob) {
    const index = editingIndex.value;
    if (index === null) return;
    URL.revokeObjectURL(picked.value[index].url);
    const file = new File([photo], `photo-${index + 1}.jpg`, { type: 'image/jpeg' });
    picked.value[index] = { file, url: URL.createObjectURL(file) };
}
async function publish() {
    if (!picked.value.length || posting.value) return;
    posting.value = true; postError.value = '';
    try {
        const body = new FormData();
        body.append('caption', caption.value.trim());
        // Where each photo was taken (from its own GPS data, read before resizing) and, as a fallback, where the
        // phone is now; the day highlight uses these to put photos at the right stop.
        const [here, ...places] = await Promise.all([currentPosition(), ...picked.value.map(p => readPhotoLocation(p.file))]);
        if (here) { body.append('latitude', String(here.latitude)); body.append('longitude', String(here.longitude)); }
        places.forEach((place, i) => (Object.keys(place) as (keyof typeof place)[]).forEach(key => { if (place[key] !== null) body.append(`photo_locations[${i}][${key}]`, String(place[key])); }));
        for (const [i, p] of picked.value.entries()) body.append('photos[]', await shrinkImage(p.file), `photo-${i + 1}.jpg`);
        const { data } = await appFetch<{ data: Post }>(`/trips/${props.tripId}/posts`, { method: 'POST', body });
        posts.value = [data, ...posts.value];
        picked.value.forEach(p => URL.revokeObjectURL(p.url)); picked.value = []; caption.value = '';
        posting.value = false;
        closeComposer();
    } catch (e) { postError.value = e instanceof Error ? e.message : 'Could not post your photos.'; posting.value = false; }
}
onBeforeUnmount(() => picked.value.forEach(p => URL.revokeObjectURL(p.url)));

// Full-size viewer
const viewing = ref<{ photos: string[]; index: number } | null>(null);
const strip = ref<HTMLElement | null>(null);
/** Opens the swipeable viewer on the tapped photo of a post. */
async function openViewer(post: Post, index: number) {
    viewing.value = { photos: post.photos.map(p => p.url), index };
    await nextTick();
    strip.value?.scrollTo({ left: index * strip.value.clientWidth, behavior: 'instant' as ScrollBehavior });
    strip.value?.focus();
}
function go(step: number) {
    if (!viewing.value || !strip.value) return;
    const index = Math.min(Math.max(viewing.value.index + step, 0), viewing.value.photos.length - 1);
    strip.value.scrollTo({ left: index * strip.value.clientWidth, behavior: 'smooth' });
}
function onStripScroll() {
    if (viewing.value && strip.value) viewing.value.index = Math.round(strip.value.scrollLeft / Math.max(strip.value.clientWidth, 1));
}
function onViewerKey(event: KeyboardEvent) {
    if (event.key === 'ArrowRight') { event.preventDefault(); go(1); }
    else if (event.key === 'ArrowLeft') { event.preventDefault(); go(-1); }
    else if (event.key === 'Escape') viewing.value = null;
}
const gridClass = (count: number) => (count === 1 ? 'one' : count === 2 ? 'two' : count === 3 ? 'three' : 'many');
const empty = computed(() => loaded.value && !posts.value.length);
</script>

<template>
    <section class="photos" aria-labelledby="photos-title">
        <div class="photos-head">
            <div><h2 id="photos-title">Photos</h2><p class="muted">Moments from {{ tripName }}, shared by the barkada.</p></div>
            <div class="head-actions">
                <button v-if="posts.length" type="button" class="btn secondary highlight-btn" @click="showHighlight = true"><Clapperboard :size="16" aria-hidden="true" /> Day highlight</button>
                <button v-if="!readOnly" type="button" class="btn post-desktop" @click="openComposer()"><ImagePlus :size="16" aria-hidden="true" /> Post photos</button>
            </div>
        </div>
        <p v-if="error" class="error-box" role="alert">{{ error }} <button type="button" class="link" @click="load()">Try again</button></p>

        <div v-if="empty" class="card empty-feed">
            <Camera :size="34" aria-hidden="true" />
            <h3>No photos yet</h3>
            <p class="muted">Be the first to share a moment from the trip.</p>
            <button v-if="!readOnly" type="button" class="btn" @click="openComposer()"><ImagePlus :size="16" aria-hidden="true" /> Post photos</button>
        </div>

        <article v-for="post in posts" :key="post.id" class="post card">
            <header class="post-head">
                <img v-if="post.author.avatar_url" :src="post.author.avatar_url" alt="" class="avatar" />
                <span v-else class="avatar initials" aria-hidden="true">{{ initials(post.author.name) }}</span>
                <span class="who"><strong>{{ post.author.name }}</strong><time :datetime="post.created_at">{{ when(post.created_at) }}</time>
                    <button v-if="post.place || post.can_edit_place" type="button" class="place" :disabled="!post.can_edit_place" :aria-label="post.place ? `Place: ${post.place.name}${post.can_edit_place ? ', change' : ''}` : 'Add a place'" @click="editPlace(post)"><MapPin :size="12" aria-hidden="true" /> {{ post.place?.name ?? 'Add a place' }}<Pencil v-if="post.can_edit_place" :size="11" aria-hidden="true" class="place-edit" /></button>
                </span>
                <button v-if="post.can_delete" type="button" class="icon-btn" :aria-label="'Delete post by ' + post.author.name" @click="remove(post)"><Trash2 :size="16" /></button>
            </header>
            <div class="post-photos" :class="gridClass(post.photos.length)">
                <button v-for="(photo, i) in post.photos.slice(0, post.photos.length > 4 ? 4 : post.photos.length)" :key="photo.url" type="button" class="photo" :style="post.photos.length === 1 && photo.width && photo.height ? { aspectRatio: `${photo.width} / ${photo.height}` } : undefined" @click="openViewer(post, i)">
                    <LoadingImage :src="photo.url" :alt="post.caption ? `Photo ${i + 1}: ${post.caption}` : `Photo ${i + 1} by ${post.author.name}`" />
                    <span v-if="i === 3 && post.photos.length > 4" class="more">+{{ post.photos.length - 4 }}</span>
                </button>
            </div>
            <p v-if="post.caption" class="caption">{{ post.caption }}</p>
            <footer class="post-foot">
                <button type="button" class="heart" :class="{ on: post.liked }" :aria-pressed="post.liked" :aria-label="post.liked ? 'Remove heart' : 'Add heart'" @click="toggleLike(post)"><Heart :size="20" :fill="post.liked ? 'currentColor' : 'none'" /></button>
                <span class="muted">{{ post.likes_count }} {{ post.likes_count === 1 ? 'heart' : 'hearts' }}</span>
                <button type="button" class="comment-btn" :aria-expanded="openComments.has(post.id)" @click="toggleComments(post.id)"><MessageCircle :size="19" aria-hidden="true" /> {{ post.comments_count ? `${post.comments_count} ${post.comments_count === 1 ? 'comment' : 'comments'}` : 'Comment' }}</button>
            </footer>
            <PostComments v-if="openComments.has(post.id)" :trip-id="tripId" :post-id="post.id" :read-only="readOnly" @count="post.comments_count = $event" />
        </article>

        <button v-if="nextBefore" type="button" class="btn secondary load-more" :disabled="loading" @click="load(true)">{{ loading ? 'Loading…' : 'Load more' }}</button>
        <template v-else-if="loading && !loaded">
            <article v-for="n in 2" :key="n" class="post card skeleton-post" aria-hidden="true">
                <div class="post-head"><span class="skeleton sk-avatar" /><span class="who"><span class="skeleton sk-line" /><span class="skeleton sk-line short" /></span></div>
                <div class="skeleton sk-photo" />
                <div class="sk-foot"><span class="skeleton sk-line" /></div>
            </article>
            <p class="sr-only" role="status">Loading photos…</p>
        </template>

        <HighlightPlayer v-if="showHighlight" :trip-id="tripId" @close="showHighlight = false" />
        <button v-if="!readOnly" type="button" class="post-fab" @click="openComposer()"><ImagePlus :size="20" aria-hidden="true" /> Post photos</button>

        <dialog v-if="composing" ref="dialog" class="composer" aria-labelledby="composer-title" @close="onClosed">
            <form @submit.prevent="publish">
                <header><h2 id="composer-title">Post photos</h2><button type="button" class="icon-btn" aria-label="Close" @click="closeComposer"><X :size="18" /></button></header>
                <div class="picked">
                    <div v-for="(p, i) in picked" :key="p.url" class="thumb"><img :src="p.url" alt="" /><button type="button" :aria-label="`Remove photo ${i + 1}`" @click="removePicked(i)"><X :size="14" /></button><button type="button" class="edit-thumb" :aria-label="`Edit photo ${i + 1}`" @click="editingIndex = i"><Sparkles :size="13" aria-hidden="true" /> Edit</button></div>
                    <label v-if="picked.length < 10" class="add-thumb"><ImagePlus :size="22" aria-hidden="true" /><span>{{ picked.length ? 'Add more' : 'Choose photos' }}</span><input ref="fileInput" type="file" accept="image/*" multiple class="sr-only" @change="addFiles" /></label>
                    <!-- Opens the camera straight away on phones (capture); desktops show the file picker. -->
                    <label v-if="picked.length < 10" class="add-thumb camera"><Camera :size="22" aria-hidden="true" /><span>Take photo</span><input type="file" accept="image/*" capture="environment" class="sr-only" @change="addFiles" /></label>
                </div>
                <label class="field">Caption<textarea v-model="caption" rows="3" maxlength="2000" placeholder="Say something about these moments…" /></label>
                <p v-if="postError" class="error" role="alert">{{ postError }}</p>
                <div class="actions"><button type="button" class="btn secondary" :disabled="posting" @click="closeComposer">Cancel</button><button type="submit" class="btn" :disabled="posting || !picked.length">{{ posting ? 'Posting…' : `Post${picked.length ? ` ${picked.length} photo${picked.length === 1 ? '' : 's'}` : ''}` }}</button></div>
            </form>
            <PhotoEditor v-if="editingIndex !== null && picked[editingIndex]" :source="picked[editingIndex].url" aspect="original" :output-size="2000" subject="photo" :title="`Edit photo ${editingIndex + 1}`" confirm-label="Done" @done="applyEdit" @close="editingIndex = null" />
        </dialog>

        <div v-if="viewing" class="viewer" role="dialog" aria-modal="true" :aria-label="`Photo ${viewing.index + 1} of ${viewing.photos.length}`">
            <div ref="strip" class="strip" tabindex="0" @scroll.passive="onStripScroll" @keydown="onViewerKey">
                <div v-for="(url, i) in viewing.photos" :key="url" class="slide"><img :src="url" :alt="`Photo ${i + 1} of ${viewing.photos.length}`" :loading="Math.abs(i - viewing.index) <= 1 ? 'eager' : 'lazy'" /></div>
            </div>
            <span v-if="viewing.photos.length > 1" class="counter" aria-hidden="true">{{ viewing.index + 1 }} / {{ viewing.photos.length }}</span>
            <button v-if="viewing.photos.length > 1 && viewing.index > 0" type="button" class="nav prev" aria-label="Previous photo" @click="go(-1)"><ChevronLeft :size="26" /></button>
            <button v-if="viewing.photos.length > 1 && viewing.index < viewing.photos.length - 1" type="button" class="nav next" aria-label="Next photo" @click="go(1)"><ChevronRight :size="26" /></button>
            <button type="button" class="icon-btn close-viewer" aria-label="Close photo" @click="viewing = null"><X :size="22" /></button>
            <div v-if="viewing.photos.length > 1" class="dots" aria-hidden="true"><span v-for="(_, i) in viewing.photos" :key="i" :class="{ on: i === viewing.index }" /></div>
        </div>
            <div v-if="placing" class="place-sheet" role="dialog" aria-modal="true" aria-labelledby="place-title" @click.self="placing = null">
            <div class="place-card">
                <h3 id="place-title"><MapPin :size="16" aria-hidden="true" /> Where was this?</h3>
                <form class="place-type" @submit.prevent="placeName.trim() && savePlace({ name: placeName.trim() })">
                    <input v-model="placeName" maxlength="200" placeholder="Type a place, e.g. Lazi Church" aria-label="Place name" />
                    <button class="btn" :disabled="placeBusy || !placeName.trim()">Save</button>
                </form>
                <p v-if="stops?.length" class="place-label">Or pick a stop from the itinerary</p>
                <ul v-if="stops?.length" class="place-stops">
                    <li v-for="s in stops" :key="s.id"><button type="button" :aria-pressed="placing.place?.itinerary_item_id === s.id" :disabled="placeBusy" @click="savePlace({ itinerary_item_id: s.id })"><strong>{{ s.title }}</strong><small>{{ stopDay(s.starts_at) }}</small></button></li>
                </ul>
                <p v-if="placeError" class="error" role="alert">{{ placeError }}</p>
                <div class="place-actions">
                    <button v-if="placing.place" type="button" class="btn secondary" :disabled="placeBusy" @click="savePlace({ clear: true })">Remove place</button>
                    <button type="button" class="btn secondary" @click="placing = null">Cancel</button>
                </div>
            </div>
        </div>
    </section>
</template>

<style scoped>
.photos{display:grid;gap:16px;max-width:640px;margin:0 auto;padding-bottom:90px}
.photos-head{display:flex;align-items:flex-end;justify-content:space-between;gap:12px}
.photos-head h2{font-size:24px;font-weight:750;letter-spacing:-.5px}
.post{padding:0;overflow:hidden}
.post-head{display:flex;align-items:center;gap:10px;padding:14px 14px 10px}
.avatar{width:40px;height:40px;border-radius:50%;object-fit:cover;flex-shrink:0}
.initials{display:grid;place-items:center;background:#e3edd5;color:#214e3e;font-weight:800;font-size:14px}
.who{display:grid;flex:1;min-width:0}
.who strong{font-size:14px}
.who time{font-size:12px;color:#738178}
.place{display:inline-flex;align-items:center;gap:4px;justify-self:start;max-width:100%;margin-top:2px;padding:0;border:0;background:none;color:#4f6156;font:inherit;font-size:12px;font-weight:600;cursor:pointer;text-align:left;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.place:disabled{cursor:default}
.place svg{flex:none;color:#72883a}
.place-edit{opacity:.6}
.place-sheet{position:fixed;inset:0;z-index:70;background:#10221a66;display:flex;align-items:flex-end;justify-content:center}
@media(min-width:640px){.place-sheet{align-items:center}}
.place-card{width:min(520px,100%);max-height:85dvh;overflow:auto;background:#fffefb;border-radius:20px 20px 0 0;padding:18px 18px calc(18px + env(safe-area-inset-bottom));display:grid;gap:12px}
@media(min-width:640px){.place-card{border-radius:20px}}
.place-card h3{display:flex;align-items:center;gap:6px;font-size:17px;font-weight:750}
.place-type{display:flex;gap:8px}
.place-type input{flex:1;min-width:0}
.place-label{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#76846e}
.place-stops{list-style:none;margin:0;padding:0;display:grid;gap:6px;max-height:40dvh;overflow:auto}
.place-stops button{display:grid;width:100%;text-align:left;padding:10px 12px;border:1px solid #e3eadc;border-radius:12px;background:#fff;font:inherit;cursor:pointer}
.place-stops button[aria-pressed="true"]{border-color:#214e3e;background:#eef5e6}
.place-stops small{color:#738178;font-size:12px}
.place-actions{display:flex;gap:8px;justify-content:flex-end}
.error{color:#b42318;font-size:13px}
.icon-btn{display:grid;place-items:center;width:36px;height:36px;border-radius:10px;border:0;background:transparent;color:#738178;cursor:pointer}
.icon-btn:hover{background:#f2f6ec;color:#9c3e2d}
.post-photos{display:grid;gap:3px;background:#eef2e9}
.post-photos.two{grid-template-columns:1fr 1fr}
.post-photos.three{grid-template-columns:2fr 1fr;grid-template-rows:1fr 1fr}
.post-photos.three .photo:first-child{grid-row:span 2}
.post-photos.many{grid-template-columns:1fr 1fr}
.photo{position:relative;display:block;padding:0;border:0;background:#dfe6d9;cursor:zoom-in;aspect-ratio:1}
.post-photos.one .photo{aspect-ratio:4/3;max-height:640px}
.skeleton-post .sk-avatar{width:40px;height:40px;border-radius:50%;flex-shrink:0}.sk-line{display:block;height:12px;width:60%;border-radius:6px;margin:3px 0}.sk-line.short{width:30%}.sk-photo{aspect-ratio:4/3;border-radius:0}.sk-foot{padding:14px 16px}
.more{position:absolute;inset:0;display:grid;place-items:center;background:#172f2a99;color:#fff;font-size:26px;font-weight:800}
.caption{padding:12px 16px 0;font-size:15px;line-height:1.6;white-space:pre-line;overflow-wrap:anywhere}
.head-actions{display:flex;gap:8px;align-items:center}
.highlight-btn{min-height:44px}
.post-foot{display:flex;align-items:center;gap:6px;padding:8px 10px 12px}
.comment-btn{margin-left:auto;display:inline-flex;align-items:center;gap:6px;min-height:40px;padding:6px 12px;border:0;border-radius:999px;background:transparent;color:#4d6158;font:inherit;font-size:13px;font-weight:650;cursor:pointer}
.comment-btn:hover,.comment-btn[aria-expanded="true"]{background:#f1f5ec;color:#214e3e}
.heart{display:grid;place-items:center;width:42px;height:42px;border-radius:50%;border:0;background:transparent;color:#6c7c72;cursor:pointer;transition:transform .15s}
.heart:active{transform:scale(.85)}
.heart.on{color:#d0454c}
.empty-feed{display:grid;justify-items:center;gap:8px;padding:40px 20px;text-align:center;color:#507044}
.load-more{justify-self:center}
.center{display:flex;gap:8px;justify-content:center;align-items:center}
.link{border:0;background:none;color:inherit;text-decoration:underline;font:inherit;cursor:pointer}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.post-fab{position:fixed;z-index:39;right:16px;bottom:calc(76px + env(safe-area-inset-bottom));display:flex;align-items:center;gap:8px;padding:14px 20px;border:0;border-radius:999px;background:#214e3e;color:#fff;font:inherit;font-weight:700;box-shadow:0 8px 20px #214e3e55;cursor:pointer}
.post-desktop{display:none}
.composer{border:0;border-radius:20px;padding:0;width:min(560px,calc(100vw - 24px));max-height:calc(100dvh - 24px);box-shadow:0 24px 60px #172f2a40;color:#172f2a}
.composer::backdrop{background:#172f2a66}
.composer form{display:grid;gap:14px;padding:20px}
.composer header{display:flex;justify-content:space-between;align-items:center}
.composer h2{font-size:20px;font-weight:750}
.picked{display:grid;grid-template-columns:repeat(auto-fill,minmax(96px,1fr));gap:8px}
.thumb,.add-thumb{position:relative;aspect-ratio:1;border-radius:12px;overflow:hidden}
.thumb img{width:100%;height:100%;object-fit:cover}
.edit-thumb{position:absolute;left:4px;bottom:4px;display:inline-flex;align-items:center;gap:4px;padding:3px 8px;border:0;border-radius:999px;background:#ffffffe6;color:#214e3e;font:inherit;font-size:11px;font-weight:700;cursor:pointer}
.thumb button:not(.edit-thumb){position:absolute;top:4px;right:4px;display:grid;place-items:center;width:24px;height:24px;border-radius:50%;border:0;background:#172f2acc;color:#fff;cursor:pointer}
.add-thumb{display:grid;place-content:center;justify-items:center;gap:4px;border:2px dashed #b9c9a6;background:#f5f9ef;color:#507044;font-size:12px;font-weight:650;cursor:pointer}
.add-thumb.camera{background:#eef6e4;border-style:solid}
.composer textarea{border:1px solid #d6dfd5;border-radius:10px;padding:10px 12px;font:inherit;font-size:14px;resize:vertical}
.error{color:#9c3e2d;font-size:13px}
.actions{display:flex;justify-content:flex-end;gap:10px}
.viewer{position:fixed;inset:0;z-index:60;background:#0b1512f2}
.strip{display:flex;width:100%;height:100%;overflow-x:auto;scroll-snap-type:x mandatory;overscroll-behavior:contain;scrollbar-width:none;outline:none}
.strip::-webkit-scrollbar{display:none}
.slide{flex:0 0 100%;height:100%;display:grid;place-items:center;scroll-snap-align:center;scroll-snap-stop:always}
.slide img{max-width:100%;max-height:100%;object-fit:contain;user-select:none;-webkit-user-drag:none}
.close-viewer{position:absolute;top:calc(12px + env(safe-area-inset-top));right:12px;width:44px;height:44px;border-radius:50%;background:#0b151299;color:#fff}
.close-viewer:hover{background:#0b1512cc;color:#fff}
.counter{position:absolute;top:calc(22px + env(safe-area-inset-top));left:50%;transform:translateX(-50%);padding:4px 12px;border-radius:999px;background:#0b151299;color:#fff;font-size:13px;font-weight:700}
.nav{position:absolute;top:50%;transform:translateY(-50%);display:grid;place-items:center;width:48px;height:48px;border-radius:50%;border:0;background:#ffffff26;color:#fff;cursor:pointer}
.nav:hover{background:#ffffff40}
.nav.prev{left:12px}.nav.next{right:12px}
.dots{position:absolute;bottom:calc(20px + env(safe-area-inset-bottom));left:0;right:0;display:flex;justify-content:center;gap:6px}
.dots span{width:7px;height:7px;border-radius:50%;background:#ffffff59}
.dots span.on{background:#fff}
@media(hover:none){.nav{display:none}}
@media(min-width:1024px){.post-fab{display:none}.post-desktop{display:inline-flex}.photos{padding-bottom:24px}}
@media(prefers-reduced-motion:reduce){.spin{animation:none}.heart{transition:none}}
</style>

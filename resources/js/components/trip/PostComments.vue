<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { LoaderCircle, Send, Trash2 } from 'lucide-vue-next';
import { appFetch } from '@/lib/uploads';

/** Comments under a photo post: everyone on the trip can read and add them; authors, the post's author and the organizer can remove them. */
type Comment = { id: number; body: string; created_at: string; author: { member_id: number; name: string; avatar_url: string | null }; can_delete: boolean };
const props = defineProps<{ tripId: number; postId: number; readOnly?: boolean }>();
const emit = defineEmits<{ count: [value: number] }>();

const comments = ref<Comment[]>([]);
const loading = ref(true);
const body = ref('');
const sending = ref(false);
const error = ref('');
const input = ref<HTMLTextAreaElement | null>(null);
const base = `/trips/${props.tripId}/posts/${props.postId}/comments`;

onMounted(async () => {
    try { comments.value = (await appFetch<{ data: Comment[] }>(base)).data; }
    catch (e) { error.value = e instanceof Error ? e.message : 'Could not load comments.'; }
    finally { loading.value = false; await nextTick(); input.value?.focus(); }
});

async function send() {
    const text = body.value.trim();
    if (!text || sending.value) return;
    sending.value = true; error.value = '';
    const form = new FormData(); form.append('body', text);
    try {
        const result = await appFetch<{ data: Comment; comments_count: number }>(base, { method: 'POST', body: form });
        comments.value = [...comments.value, result.data]; body.value = '';
        emit('count', result.comments_count);
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not post your comment.'; }
    finally { sending.value = false; }
}
async function remove(comment: Comment) {
    if (!confirm('Remove this comment?')) return;
    try {
        const result = await appFetch<{ comments_count: number }>(`${base}/${comment.id}`, { method: 'DELETE' });
        comments.value = comments.value.filter(c => c.id !== comment.id);
        emit('count', result.comments_count);
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not remove the comment.'; }
}
const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]!.toUpperCase()).join('');
const when = (iso: string) => {
    const minutes = (Date.now() - Date.parse(iso)) / 60000;
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${Math.floor(minutes)}m`;
    if (minutes < 1440) return `${Math.floor(minutes / 60)}h`;
    return new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric' }).format(new Date(iso));
};
function onKey(event: KeyboardEvent) { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(); } }
</script>

<template>
    <section class="comments" aria-label="Comments">
        <p v-if="loading" class="muted small"><LoaderCircle :size="14" class="spin" aria-hidden="true" /> Loading comments…</p>
        <ul v-else-if="comments.length" class="list">
            <li v-for="c in comments" :key="c.id" class="comment">
                <img v-if="c.author.avatar_url" :src="c.author.avatar_url" alt="" class="avatar" />
                <span v-else class="avatar initials" aria-hidden="true">{{ initials(c.author.name) }}</span>
                <div class="bubble">
                    <p><strong>{{ c.author.name }}</strong> {{ c.body }}</p>
                    <small>{{ when(c.created_at) }}<button v-if="c.can_delete" type="button" class="remove" :aria-label="`Remove comment by ${c.author.name}`" @click="remove(c)"><Trash2 :size="12" /> Remove</button></small>
                </div>
            </li>
        </ul>
        <p v-else class="muted small">No comments yet. Say something!</p>
        <form v-if="!readOnly" class="compose" @submit.prevent="send">
            <textarea ref="input" v-model="body" rows="1" maxlength="1000" placeholder="Add a comment…" aria-label="Add a comment" @keydown="onKey" />
            <button class="send" :disabled="sending || !body.trim()" aria-label="Post comment"><LoaderCircle v-if="sending" :size="16" class="spin" /><Send v-else :size="16" /></button>
        </form>
        <p v-if="error" class="error small" role="alert">{{ error }}</p>
    </section>
</template>

<style scoped>
.comments{display:grid;gap:10px;padding:4px 14px 14px;border-top:1px solid #eef2ea}
.list{list-style:none;margin:0;padding:0;display:grid;gap:10px;max-height:360px;overflow-y:auto}
.comment{display:flex;gap:8px;align-items:flex-start}
.avatar{flex-shrink:0;width:30px;height:30px;border-radius:50%;object-fit:cover;background:#e3edd5;color:#214e3e;display:grid;place-items:center;font-size:11px;font-weight:800}
.bubble{display:grid;gap:2px;min-width:0}
.bubble p{background:#f3f6ef;border-radius:14px;padding:7px 11px;font-size:14px;line-height:1.45;white-space:pre-wrap;overflow-wrap:anywhere}
.bubble strong{font-weight:700;margin-right:4px}
.bubble small{display:flex;gap:10px;align-items:center;font-size:11px;color:#7b8a80;padding-left:10px}
.remove{display:inline-flex;align-items:center;gap:3px;border:0;background:none;color:#9c3e2d;font:inherit;cursor:pointer;padding:0}
.compose{display:flex;gap:8px;align-items:flex-end}
.compose textarea{flex:1;resize:none;border:1px solid #d6dfd5;border-radius:18px;padding:9px 14px;font:inherit;font-size:14px;max-height:120px}
.send{display:grid;place-items:center;width:40px;height:40px;border-radius:50%;border:0;background:#214e3e;color:#fff;cursor:pointer}
.send:disabled{opacity:.5;cursor:default}
.small{font-size:12px}.muted{color:#738178}.error{color:#9c3e2d}
.spin{animation:spin 1s linear infinite;vertical-align:-2px}
@keyframes spin{to{transform:rotate(360deg)}}
</style>

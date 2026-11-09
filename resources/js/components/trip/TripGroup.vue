<script setup lang="ts">
import { computed, ref } from 'vue';
import { router, useForm } from '@inertiajs/vue3';
import { ArrowRight, Check, Copy, HandCoins, Share2, UserPlus } from 'lucide-vue-next';
import PaymentHistoryEntry from '@/components/PaymentHistoryEntry.vue';
import TravelerDialog, { type TravelerUser } from '@/components/trip/TravelerDialog.vue';

type Member = { id: number; user_id?: number; status?: string; user: TravelerUser };
type Settlement = { id: number; expense_id: number | null; from_member_id: number; to_member_id: number; amount_minor: number; status: string; payment_method: string; paid_at: string | null; confirmed_at: string | null };
const props = defineProps<{
    tripId: number; tripName: string; organizerUserId?: number; currency: string; timezone: string;
    members: Member[]; balances: { member_id: number; name: string; net_minor: number }[]; settlements: Settlement[];
    expenses: any[]; currentMemberId: number; isOrganizer: boolean; invitationUrl: string | null;
}>();

const money = (amount: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: props.currency }).format(amount / 100);
const name = (id: number) => props.members.find(m => m.id === id)?.user.name ?? 'Traveler';
const initials = (full: string) => full.split(/\s+/).filter(Boolean).slice(0, 2).map(word => word[0]!.toUpperCase()).join('');
const minor = (value: string | number) => { const s = String(value).trim(); if (!/^\d+(\.\d{1,2})?$/.test(s)) return -1; const [whole, fraction = ''] = s.split('.'); return Number(whole) * 100 + Number(fraction.padEnd(2, '0')); };
// Traveller details sheet (tap a traveller); opening your own lets you edit it.
const viewing = ref<number | null>(null);
const editingMine = ref(false);
const viewed = computed(() => props.members.find(m => m.id === viewing.value) ?? null);
const me = computed(() => props.members.find(m => m.id === props.currentMemberId));
const missingMine = computed(() => !!me.value && !me.value.user.phone && !me.value.user.emergency_contact_phone);
const isOrganizerMember = (m: Member) => !!props.organizerUserId && (m.user_id ?? m.user.id) === props.organizerUserId;
function openTraveler(id: number, edit = false) { editingMine.value = edit; viewing.value = id; }
// Recorded bills count in full, so until merchants are paid the balances leave this much unaccounted for:
// it is what travellers still owe the merchants (or whoever pays them), not each other.
const unpaidToMerchants = computed(() => props.expenses.filter(e => ['open', 'completed', 'closed'].includes(e.status))
    .reduce((sum, e) => sum + Math.max(0, e.total_minor - (e.payments ?? []).filter((p: { paid_at?: string | null }) => p.paid_at !== null).reduce((t: number, p: { amount_minor: number }) => t + p.amount_minor, 0)), 0));
const balanceOf = (memberId: number) => props.balances.find(b => b.member_id === memberId)?.net_minor ?? 0;

// Suggested settlements: a display-only plan that pays off every balance in the fewest transfers (greedy).
const suggestions = computed(() => {
    const debtors = props.balances.filter(b => b.net_minor < 0).map(b => ({ id: b.member_id, left: -b.net_minor })).sort((a, b) => b.left - a.left);
    const creditors = props.balances.filter(b => b.net_minor > 0).map(b => ({ id: b.member_id, left: b.net_minor })).sort((a, b) => b.left - a.left);
    const plan: { from: number; to: number; amount: number }[] = [];
    let d = 0; let c = 0;
    while (d < debtors.length && c < creditors.length) {
        const amount = Math.min(debtors[d].left, creditors[c].left);
        if (amount > 0) plan.push({ from: debtors[d].id, to: creditors[c].id, amount });
        debtors[d].left -= amount; creditors[c].left -= amount;
        if (debtors[d].left === 0) d++;
        if (creditors[c].left === 0) c++;
    }
    return plan;
});

// Invitations
const inviting = ref(false);
const copied = ref(false);
function createInvite() { inviting.value = true; router.post(`/trips/${props.tripId}/invitations`, {}, { preserveScroll: true, preserveState: true, onFinish: () => { inviting.value = false; } }); }
async function copyInvite() {
    if (!props.invitationUrl) return;
    try { await navigator.clipboard.writeText(props.invitationUrl); copied.value = true; setTimeout(() => { copied.value = false; }, 2000); } catch { /* clipboard blocked: the field stays selectable */ }
}
const canShare = typeof navigator !== 'undefined' && 'share' in navigator;
function shareInvite() { if (props.invitationUrl) navigator.share({ title: props.tripName, text: `Join our trip: ${props.tripName}`, url: props.invitationUrl }).catch(() => {}); }

// Record money sent (unchanged behaviour from the former Balances tab)
const settlement = useForm({ to_member_id: props.members.find(m => m.id !== props.currentMemberId)?.id ?? '', amount: '', payment_method: 'gcash' });
function prefill(to: number, amount: number) { settlement.to_member_id = to; settlement.amount = (amount / 100).toFixed(2); document.getElementById('record-payment')?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
function recordSettlement() {
    settlement.transform(d => ({ to_member_id: d.to_member_id, amount_minor: minor(d.amount), payment_method: d.payment_method }))
        .post(`/trips/${props.tripId}/settlements`, { preserveScroll: true, preserveState: true, onSuccess: () => settlement.reset() });
}
const pendingForMe = computed(() => props.settlements.filter(s => s.status === 'pending' && s.to_member_id === props.currentMemberId).length);
</script>

<template>
    <div class="group">
        <section class="card group-card" aria-labelledby="travelers-title">
            <div class="row spread"><h2 id="travelers-title">Travelers <span class="count">{{ members.length }}</span></h2><button type="button" class="btn secondary small-btn" @click="openTraveler(currentMemberId, true)">My details</button></div>
            <button v-if="missingMine" type="button" class="nudge" @click="openTraveler(currentMemberId, true)"><strong>Add your number and emergency contact</strong><span>So your barkada can reach you, or someone close to you, if anything happens.</span></button>
            <ul class="travelers">
                <li v-for="member in members" :key="member.id" class="traveler tappable" role="button" tabindex="0" :aria-label="`${member.user.name}: contact and emergency details`" @click="openTraveler(member.id)" @keydown.enter.prevent="openTraveler(member.id)" @keydown.space.prevent="openTraveler(member.id)">
                    <img v-if="member.user.avatar_url" :src="member.user.avatar_url" alt="" class="avatar photo" />
                    <span v-else class="avatar" aria-hidden="true">{{ initials(member.user.name) }}</span>
                    <span class="traveler-body">
                        <strong>{{ member.user.name }}<span v-if="member.id === currentMemberId" class="tag">You</span><span v-if="organizerUserId && (member.user_id ?? member.user.id) === organizerUserId" class="tag organizer">Organizer</span></strong>
                        <span class="muted">{{ member.status && member.status !== 'active' ? member.status : 'Joined' }}</span>
                    </span>
                    <span class="traveler-balance" :class="{ owe: balanceOf(member.id) < 0, receive: balanceOf(member.id) > 0 }">{{ balanceOf(member.id) > 0 ? '+' : '' }}{{ money(balanceOf(member.id)) }}</span>
                </li>
            </ul>
            <div v-if="isOrganizer" class="invite">
                <template v-if="invitationUrl">
                    <label class="field">Invitation link · valid for 7 days<input :value="invitationUrl" readonly @focus="($event.target as HTMLInputElement).select()" /></label>
                    <div class="row invite-actions">
                        <button type="button" class="btn secondary" @click="copyInvite"><Check v-if="copied" :size="15" aria-hidden="true" /><Copy v-else :size="15" aria-hidden="true" /> {{ copied ? 'Copied' : 'Copy link' }}</button>
                        <button v-if="canShare" type="button" class="btn" @click="shareInvite"><Share2 :size="15" aria-hidden="true" /> Share</button>
                    </div>
                </template>
                <button v-else type="button" class="btn invite-btn" :disabled="inviting" @click="createInvite"><UserPlus :size="16" aria-hidden="true" /> {{ inviting ? 'Creating link…' : 'Invite friends' }}</button>
            </div>
        </section>

        <section class="card group-card" aria-labelledby="balances-title">
            <h2 id="balances-title">Balances</h2>
            <p class="muted">Every recorded expense counts here in full, even before the merchant is paid, so each traveller sees their whole share. Reimbursements count once the recipient confirms.</p>
            <p v-if="unpaidToMerchants > 0" class="unpaid-note">{{ money(unpaidToMerchants) }} of recorded bills isn’t paid to the merchant yet. That part of everyone’s share goes to the merchant (or whoever pays it), so it isn’t in the suggested settlements below.</p>
            <ul class="balance-list">
                <li v-for="b in balances" :key="b.member_id"><span>{{ b.name }}</span><strong :class="{ owe: b.net_minor < 0, receive: b.net_minor > 0 }">{{ b.net_minor > 0 ? 'Receives' : b.net_minor < 0 ? 'Owes' : 'Settled' }} {{ money(Math.abs(b.net_minor)) }}</strong></li>
            </ul>
            <h3 class="sub">Suggested settlements</h3>
            <p v-if="!suggestions.length" class="muted">Everyone is settled up. 🎉</p>
            <ul v-else class="suggestions">
                <li v-for="(s, i) in suggestions" :key="i">
                    <span class="suggestion-people"><strong>{{ name(s.from) }}</strong> <ArrowRight :size="14" aria-hidden="true" /> <strong>{{ name(s.to) }}</strong></span>
                    <span class="suggestion-amount">{{ money(s.amount) }}</span>
                    <button v-if="s.from === currentMemberId" type="button" class="btn secondary pay-btn" @click="prefill(s.to, s.amount)"><HandCoins :size="14" aria-hidden="true" /> I paid this</button>
                </li>
            </ul>
            <p class="muted fine">Suggestions are a plan only — record a payment once money has actually been sent.</p>
        </section>

        <form v-if="members.length > 1" id="record-payment" class="card form-grid group-card" @submit.prevent="recordSettlement">
            <h2 class="full">Record money you sent</h2>
            <p class="muted full">Record a repayment from <strong>{{ name(currentMemberId) }}</strong> to another traveler. This does not send money.</p>
            <label class="field">Recipient<select v-model="settlement.to_member_id"><option v-for="m in members.filter(m => m.id !== currentMemberId)" :key="m.id" :value="m.id">{{ m.user.name }}</option></select></label>
            <label class="field">Amount<input v-model="settlement.amount" type="number" min="0.01" step="0.01" required inputmode="decimal" /></label>
            <label class="field">Payment method<select v-model="settlement.payment_method"><option value="gcash">GCash money transfer</option><option value="cash">Cash</option><option value="bank_transfer">Bank transfer</option><option value="other">Other payment method</option></select></label>
            <div class="full"><button class="btn" :disabled="settlement.processing">Record reimbursement</button></div>
        </form>

        <section v-if="settlements.length" class="group-card" aria-labelledby="history-title">
            <h2 id="history-title">Payment history <span v-if="pendingForMe" class="count warn">{{ pendingForMe }} to confirm</span></h2>
            <PaymentHistoryEntry v-for="s in settlements" :key="s.id" :sender="name(s.from_member_id)" :recipient="name(s.to_member_id)" :amount="money(s.amount_minor)" :method="s.payment_method" :status="s.status" :paid-at="s.paid_at" :confirmed-at="s.confirmed_at" :timezone="timezone" :expense="expenses.find(e => e.id === s.expense_id)" :can-confirm="s.status === 'pending' && s.to_member_id === currentMemberId" @confirm="router.post(`/trips/${tripId}/settlements/${s.id}/confirm`, {}, { preserveScroll: true, preserveState: true })" />
        </section>
        <TravelerDialog v-if="viewed" :key="viewed.id" :user="viewed.user" :is-me="viewed.id === currentMemberId" :is-organizer="isOrganizerMember(viewed)" :start-editing="editingMine" @close="viewing = null" />
    </div>
</template>

<style scoped>
.tappable{cursor:pointer;border-radius:12px;transition:background .15s}.tappable:hover{background:#f5f8f0}.tappable:focus-visible{outline:3px solid #9cb865;outline-offset:2px}
.avatar.photo{object-fit:cover;padding:0}
.small-btn{padding:8px 12px;font-size:13px;min-height:38px}
.nudge{display:grid;gap:2px;text-align:left;width:100%;margin-top:12px;padding:12px 14px;border-radius:14px;border:1px dashed #e0a98f;background:#fff7f4;color:#7d3424;font:inherit;cursor:pointer}.nudge span{font-size:12px;color:#8a5a4b}
.group{display:grid;gap:14px}
.group-card{display:grid;gap:12px}
.travel-app .group h2{font-size:18px;letter-spacing:-.4px;display:flex;align-items:center;gap:8px}
.count{font-size:11px;font-weight:700;background:#edf2e6;color:#526936;border-radius:999px;padding:2px 8px;letter-spacing:0}
.count.warn{background:#fff3d6;color:#926815}
.unpaid-note{font-size:12px;line-height:1.6;color:#7a5a12;background:#fff8e6;border:1px solid #f0dfb0;border-radius:10px;padding:10px 12px;margin-top:8px}
.travelers,.balance-list,.suggestions{list-style:none;margin:0;padding:0}
.traveler{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:12px;min-height:56px;padding:6px 0;border-bottom:1px solid #eef2ea}
.traveler:last-child{border:0}
.avatar{display:grid;place-items:center;width:40px;height:40px;border-radius:50%;background:#e3edd5;color:#214e3e;font-size:13px;font-weight:800}
.traveler-body{display:grid;min-width:0}
.traveler-body strong{font-size:14px;display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.traveler-body .muted{font-size:12px;text-transform:capitalize}
.tag{font-size:10px;font-weight:700;background:#edf2e6;color:#526936;border-radius:6px;padding:2px 6px}
.tag.organizer{background:#214e3e;color:#fff}
.traveler-balance{font-size:13px;font-weight:750;white-space:nowrap}
.owe{color:#a6573e}
.receive{color:#3d7650}
.invite{display:grid;gap:10px;padding-top:4px}
.invite-btn,.invite-actions .btn{min-height:44px}
.invite-actions .btn{flex:1}
.balance-list li{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:12px 0;border-bottom:1px solid #eef2ea;font-size:14px}
.balance-list li:last-child{border:0}
.sub{font-size:14px;font-weight:750;margin-top:4px}
.suggestions li{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 12px;border:1px solid #e4ebdf;border-radius:12px;margin-bottom:8px;background:#fbfcf8}
.suggestion-people{display:flex;align-items:center;gap:6px;font-size:13px;flex:1;min-width:0}
.suggestion-amount{font-weight:800;font-size:14px}
.pay-btn{min-height:40px;padding:8px 12px}
.fine{font-size:11px}
</style>

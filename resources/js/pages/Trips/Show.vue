<script setup lang="ts">
import { Head, router, useForm, usePage } from '@inertiajs/vue3';
import { computed, ref, nextTick, onBeforeUnmount, onMounted, watch } from 'vue';
import { Plus, ReceiptText, Pencil, CheckCheck, Banknote, LockKeyhole, CircleX, Check, ChevronDown, Trash2 } from 'lucide-vue-next';
import TravelLayout from '@/layouts/TravelLayout.vue';
import { calculateShares } from '@/lib/splits';
import TripItinerary from '@/components/TripItinerary.vue';
import TripGroup from '@/components/trip/TripGroup.vue';
import TripPhotos from '@/components/trip/TripPhotos.vue';
import TripHeader from '@/components/trip/TripHeader.vue';
import TripCancelDialog from '@/components/trip/TripCancelDialog.vue';
import TripEditDialog from '@/components/trip/TripEditDialog.vue';
import ReceiptScanner, { type ReceiptDraft } from '@/components/trip/ReceiptScanner.vue';
import TripNavigation, { type TripScreen } from '@/components/trip/TripNavigation.vue';
import type { ItineraryStop } from '@/lib/itinerary';
type Member={id:number;user_id?:number;status?:string;user:{id?:number;name:string}};
type Expense={id:number;created_by_member_id?:number|null;title:string;merchant:string;status:string;version:number;total_minor:number;incurred_at:string;category_id:number;split_method:string;category:{name:string};cancellation_reason:string|null;payments:{trip_member_id:number;amount_minor:number;paid_at?:string|null}[];allocations:{trip_member_id:number;amount_minor:number;basis_value:string|null;expense_item_id:number|null}[];items:{id:number;description:string;line_total_minor:number}[];receipts:{id:number;processing_status:string}[]};
const props=defineProps<{trip:{id:number;organizer_user_id?:number;status?:string;cancellation_reason?:string|null;cancelled_at?:string|null;hidden_at?:string|null;cover_url?:string|null;name:string;destination:string;description:string;starts_on:string;ends_on:string;timezone:string;currency_code:string;members:Member[];expenses:Expense[];itinerary_items:ItineraryStop[];settlements:{id:number;expense_id:number|null;from_member_id:number;to_member_id:number;amount_minor:number;status:string;payment_method:string;paid_at:string|null;confirmed_at:string|null}[]};googleMaps:{browserKey:string;mapId:string};categories:{id:number;name:string}[];balances:{member_id:number;name:string;net_minor:number}[];isOrganizer:boolean;currentMemberId:number;invitationUrl:string|null;expenseShares:Record<number,{member_id:number;share_minor:number;covered_minor:number;remaining_minor:number}[]>}>();
// Which trip screen is shown. Kept in the URL hash (so Back works) and per browser tab (so it survives saves).
type PlanScreen='today'|'itinerary'|'map';
const screens:TripScreen[]=['today','itinerary','map','expenses','photos','group'];
const SCREEN_KEY=`barkada.trip.${props.trip.id}.screen`;
const fromHash=()=>{const h=location.hash.slice(1) as TripScreen;return screens.includes(h)?h:null;};
const readScreen=():TripScreen=>{try{return fromHash()??(sessionStorage.getItem(SCREEN_KEY) as TripScreen|null)??'today';}catch{return fromHash()??'today';}};
const screen=ref<TripScreen>(readScreen());
const urlFor=(value:TripScreen)=>`${location.pathname}${location.search}#${value}`;
const isPlan=computed(()=>['today','itinerary','map'].includes(screen.value));
// A cancelled trip locks the plan and new expenses; settling up stays available.
const cancelled=computed(()=>props.trip.status==='cancelled');
const cancelling=ref(false);
const editingTrip=ref(false);
function manageTrip(action:'cancel'|'reopen'|'hide'|'show'|'edit'){
    if(action==='edit'){editingTrip.value=true;return;}
    if(action==='cancel'){cancelling.value=true;return;}
    if(action==='reopen'){router.post(`/trips/${props.trip.id}/reopen`,{},{preserveScroll:true});return;}
    router.patch(`/trips/${props.trip.id}/visibility`,{hidden:action==='hide'},{preserveScroll:true});
}
// The plan screens stay mounted (v-show) so the map is drawn once and keeps its state.
const planView=ref<PlanScreen>(isPlan.value?screen.value as PlanScreen:'today');
watch(screen,value=>{
    if(isPlan.value)planView.value=value as PlanScreen;
    try{sessionStorage.setItem(SCREEN_KEY,value);}catch{/* storage unavailable */}
    // Add a history entry so Back returns to the previous screen, then sync Inertia's own copy of the URL
    // (Inertia rewrites history with its page URL, which would otherwise drop the hash).
    if(location.hash.slice(1)!==value){history.pushState(history.state,'',urlFor(value));syncInertiaUrl(value);}
    window.scrollTo({top:0});
});
const onPop=()=>{const h=fromHash();if(h)screen.value=h;};
// router.replace is queued behind Inertia's own history writes, so it reliably wins.
function syncInertiaUrl(value:TripScreen){router.replace({url:urlFor(value),preserveState:true,preserveScroll:true});}
const page=usePage();
// Saving redirects back to the trip URL without the hash; whenever Inertia's page URL loses it, put it back.
watch(()=>page.url,url=>{if(!url.endsWith(`#${screen.value}`))syncInertiaUrl(screen.value);});
onMounted(()=>{window.addEventListener('popstate',onPop);if(!page.url.endsWith(`#${screen.value}`))syncInertiaUrl(screen.value);});
onBeforeUnmount(()=>window.removeEventListener('popstate',onPop));
const adding=ref(false); const statusFilter=ref('all'); const reason=ref<Record<number,string>>({});
const cancellingId=ref<number|null>(null); const deletingId=ref<number|null>(null);
/** The organizer manages every expense; whoever recorded one may cancel or delete it. */
function canManage(e:Expense){return props.isOrganizer || (!!e.created_by_member_id && e.created_by_member_id===props.currentMemberId);}
/** Deleting is for mistakes: only while no money has been recorded against the expense. */
function canDelete(e:Expense){return canManage(e) && e.payments.length===0 && !props.trip.settlements.some(s=>s.expense_id===e.id);}
function destroyExpense(e:Expense){
    actionErrors.value[e.id]='';busyId.value=e.id;
    router.delete(`/trips/${props.trip.id}/expenses/${e.id}`,{preserveScroll:true,onError:errors=>actionErrors.value[e.id]=Object.values(errors).join(' '),onSuccess:()=>{deletingId.value=null;openExpense.value=null;},onFinish:()=>busyId.value=null});
} const voidPayments=ref<Record<number,boolean>>({});
const actionErrors=ref<Record<number,string>>({}); const busyId=ref<number|null>(null);
const shareConfirm=ref<{expenseId:number;memberId:number}|null>(null);
const paymentSource=ref('new');
function canConfirmShare(e:Expense){return props.isOrganizer || (props.expenseShares[e.id]??[]).some(s=>s.member_id===props.currentMemberId&&s.covered_minor>s.share_minor);}
function existingRepayments(e:Expense,memberId:number){const creditors=(props.expenseShares[e.id]??[]).filter(s=>s.covered_minor>s.share_minor&&(props.isOrganizer||s.member_id===props.currentMemberId)).map(s=>s.member_id);return props.trip.settlements.filter(s=>s.expense_id===null&&s.from_member_id===memberId&&creditors.includes(s.to_member_id)&&['pending','confirmed'].includes(s.status));}
function openShareConfirmation(e:Expense,memberId:number){shareConfirm.value={expenseId:e.id,memberId};paymentSource.value=String(existingRepayments(e,memberId)[0]?.id??'new');actionErrors.value[e.id]='';}
function confirmableAmount(e:Expense,memberId:number){const rows=props.expenseShares[e.id]??[];const remaining=rows.find(s=>s.member_id===memberId)?.remaining_minor??0;const available=rows.filter(s=>props.isOrganizer||s.member_id===props.currentMemberId).reduce((sum,s)=>sum+Math.max(0,s.covered_minor-s.share_minor),0);const existing=existingRepayments(e,memberId).find(s=>String(s.id)===paymentSource.value);if(existing){const credit=rows.find(s=>s.member_id===existing.to_member_id);return Math.min(remaining,existing.amount_minor,Math.max(0,(credit?.covered_minor??0)-(credit?.share_minor??0)));}return Math.min(remaining,available);}
const editingId=ref<number|null>(null); const editingVersion=ref(1); const editor=ref<HTMLFormElement|null>(null);
const minor=(value:string|number)=>{const s=String(value).trim();if(!/^\d+(\.\d{1,2})?$/.test(s))return -1;const [whole,fraction='']=s.split('.');return Number(whole)*100+Number(fraction.padEnd(2,'0'));};
const money=(amount:number)=>new Intl.NumberFormat('en-PH',{style:'currency',currency:props.trip.currency_code}).format(amount/100);
const name=(id:number)=>props.trip.members.find(m=>m.id===id)?.user.name??'Traveler';
// Quick filters: everything, expenses I paid or share, and ones that still need money or completion.
const quickFilter=ref<'all'|'mine'|'unsettled'>('all');
const quickFilters=[['all','All'],['mine','Mine'],['unsettled','Unsettled']] as const;
const involvesMe=(e:Expense)=>e.payments.some(p=>p.trip_member_id===props.currentMemberId)||e.allocations.some(a=>a.trip_member_id===props.currentMemberId);
const unsettled=(e:Expense)=>['open','draft'].includes(e.status)||(e.status!=='cancelled'&&(props.expenseShares[e.id]??[]).some(s=>s.remaining_minor>0));
const shown=computed(()=>props.trip.expenses.filter(e=>(statusFilter.value==='all'||e.status===statusFilter.value)&&(quickFilter.value==='all'||(quickFilter.value==='mine'?involvesMe(e):unsettled(e)))));
const myContribution=computed(()=>props.trip.expenses.filter(e=>e.status!=='cancelled').reduce((sum,e)=>sum+e.payments.filter(p=>p.trip_member_id===props.currentMemberId).reduce((t,p)=>t+p.amount_minor,0),0));
const myBalance=computed(()=>props.balances.find(b=>b.member_id===props.currentMemberId)?.net_minor??0);
const payers=(e:Expense)=>[...new Set(e.payments.map(p=>name(p.trip_member_id)))].join(', ')||'Not paid yet';
const openExpense=ref<number|null>(null);
const shortDate=(value:string)=>new Intl.DateTimeFormat('en-PH',{month:'short',day:'numeric',timeZone:'UTC'}).format(new Date(`${value.slice(0,10)}T00:00:00Z`));
const pendingForMe=computed(()=>props.trip.settlements.filter(s=>s.status==='pending'&&s.to_member_id===props.currentMemberId).length);
// Same rule as the server's balances: completed/closed bills, plus open ones the merchant has been paid for in full.
const counts=(e:Expense)=>['completed','closed'].includes(e.status)||(e.status==='open'&&e.payments.filter(p=>p.paid_at).reduce((t,p)=>t+p.amount_minor,0)===e.total_minor);
const spent=computed(()=>props.trip.expenses.filter(counts).reduce((s,e)=>s+e.total_minor,0));
const today=new Intl.DateTimeFormat('en-CA',{timeZone:props.trip.timezone,year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const expense=useForm({title:'',merchant:'',incurred_at:today,category_id:props.categories[0]?.id??'',amount:'',split_method:'equal',people:props.trip.members.map(m=>({id:m.id,selected:true as boolean,value:'1'})),payers:[{id:props.currentMemberId,amount:''}],items:[{description:'',amount:'',member_ids:[] as number[]}],receipt_scan_id:null as number|null});
/** Fills the new-expense form from a scanned receipt; each receipt line starts shared by everyone. */
function applyScan(draft:ReceiptDraft){
    expense.receipt_scan_id=draft.scan_id;
    expense.title=draft.title;
    if(draft.merchant)expense.merchant=draft.merchant;
    if(draft.incurred_at)expense.incurred_at=draft.incurred_at;
    if(draft.category_id)expense.category_id=draft.category_id;
    if(draft.total_minor)expense.amount=(draft.total_minor/100).toFixed(2);
    if(draft.lines.length){
        const everyone=props.trip.members.map(m=>m.id);
        expense.split_method='itemized';
        expense.items=draft.lines.map(line=>({description:line.description,amount:(line.amount_minor/100).toFixed(2),member_ids:[...everyone]}));
    }
}
const splitPreview=computed(()=>{
    try {
        let amounts:Record<number,number>={};
        if(expense.split_method==='itemized') {
            if(expense.items.reduce((sum,item)=>sum+minor(item.amount),0)!==minor(expense.amount)) throw new Error('Receipt line totals must match the expense total.');
            for(const item of expense.items) {
                const shares=calculateShares(minor(item.amount),'equal',item.member_ids.map(id=>({id,weight:1})));
                for(const [id,amount] of Object.entries(shares)) amounts[Number(id)]=(amounts[Number(id)]??0)+amount;
            }
        } else amounts=calculateShares(minor(expense.amount),expense.split_method,expense.people.filter(p=>p.selected).map(p=>({id:p.id,weight:['exact','percentage'].includes(expense.split_method)?minor(p.value):Number(p.value)})));
        return {amounts,error:''};
    } catch(error) {return {amounts:{} as Record<number,number>,error:error instanceof Error?error.message:'Check the split values.'};}
});
function newExpense(){expense.reset();expense.clearErrors();editingId.value=null;adding.value=true;screen.value='expenses';nextTick(()=>editor.value?.scrollIntoView({block:'start'}));}
async function editExpense(e:Expense){
    expense.clearErrors();editingId.value=e.id;editingVersion.value=e.version;
    expense.title=e.title;expense.merchant=e.merchant??'';expense.incurred_at=e.incurred_at;expense.category_id=e.category_id;expense.amount=(e.total_minor/100).toFixed(2);expense.split_method=e.split_method;
    expense.people=props.trip.members.map(m=>{const allocations=e.allocations.filter(a=>a.trip_member_id===m.id);const basis=Number(allocations[0]?.basis_value??1);return {id:m.id,selected:allocations.length>0,value:e.split_method==='exact'?(allocations.reduce((sum,a)=>sum+a.amount_minor,0)/100).toFixed(2):e.split_method==='percentage'?(basis/100).toFixed(2):String(basis)};});
    const paid:Record<number,number>={};for(const p of e.payments)paid[p.trip_member_id]=(paid[p.trip_member_id]??0)+p.amount_minor;
    expense.payers=Object.entries(paid).map(([id,amount])=>({id:Number(id),amount:(amount/100).toFixed(2)}));
    expense.items=e.items.length?e.items.map(item=>({description:item.description,amount:(item.line_total_minor/100).toFixed(2),member_ids:e.allocations.filter(a=>a.expense_item_id===item.id).map(a=>a.trip_member_id)})):[{description:'',amount:'',member_ids:[]}];
    adding.value=true;await nextTick();editor.value?.scrollIntoView({behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth',block:'start'});
}
function saveExpense(){
    expense.transform(d=>({version:editingVersion.value,receipt_scan_id:editingId.value?null:d.receipt_scan_id,title:d.title,merchant:d.merchant,incurred_at:d.incurred_at,category_id:d.category_id,total_minor:minor(d.amount),split_method:d.split_method,payments:d.payers.filter(p=>p.amount!=='').map(p=>({trip_member_id:p.id,amount_minor:minor(p.amount)})),allocations:d.people.filter(p=>p.selected).map(p=>({trip_member_id:p.id,weight:d.split_method==='equal'?1:['exact','percentage'].includes(d.split_method)?minor(p.value):Number(p.value)})),items:d.split_method==='itemized'?d.items.map(i=>({description:i.description,amount_minor:minor(i.amount),member_ids:i.member_ids})):[]}));
    const options={preserveScroll:true,onSuccess:()=>{adding.value=false;editingId.value=null;expense.reset();}};
    if(editingId.value)expense.patch(`/trips/${props.trip.id}/expenses/${editingId.value}`,options);else expense.post(`/trips/${props.trip.id}/expenses`,options);
}

const payment=useForm({expense_id:0,trip_member_id:props.currentMemberId,amount:'',version:1});
const receipt=useForm<{receipt:File|null}>({receipt:null});
function status(e:Expense,target:string){
    actionErrors.value[e.id]='';busyId.value=e.id;
    router.patch(`/trips/${props.trip.id}/expenses/${e.id}/status`,{status:target,version:e.version,reason:reason.value[e.id]??null,void_payments:voidPayments.value[e.id]??false},{preserveScroll:true,onError:errors=>actionErrors.value[e.id]=Object.values(errors).join(' '),onSuccess:()=>{cancellingId.value=null;if(target==='cancelled')statusFilter.value='all';},onFinish:()=>busyId.value=null});
}
function markSharePaid(e:Expense,memberId:number){
    actionErrors.value[e.id]='';busyId.value=e.id;
    router.post(`/trips/${props.trip.id}/expenses/${e.id}/shares/paid`,{member_id:memberId,version:e.version,settlement_id:paymentSource.value==='new'?null:Number(paymentSource.value),new_payment:paymentSource.value==='new'},{preserveScroll:true,onError:errors=>actionErrors.value[e.id]=Object.values(errors).join(' '),onSuccess:()=>shareConfirm.value=null,onFinish:()=>busyId.value=null});
}
function upload(event:Event,e:Expense){receipt.receipt=(event.target as HTMLInputElement).files?.[0]??null;if(receipt.receipt)receipt.post(`/trips/${props.trip.id}/expenses/${e.id}/receipts`,{preserveScroll:true,onSuccess:()=>receipt.reset()});}
</script>
<template>
<Head :title="trip.name"/><TravelLayout app-nav>
 <TripHeader :name="trip.name" :destination="trip.destination" :starts-on="String(trip.starts_on).slice(0,10)" :ends-on="String(trip.ends_on).slice(0,10)" :travelers="trip.members.length" :is-organizer="isOrganizer" :spent="money(spent)" :balance-minor="myBalance" :balance="money(myBalance)" :expense-count="trip.expenses.length" :open-expenses="trip.expenses.filter(e=>e.status==='open').length" :hide-summary="!isPlan" :cancelled="cancelled" :hidden="!!trip.hidden_at" :cover-url="trip.cover_url" @open="screen=$event" @manage="manageTrip"/>
 <div v-if="cancelled" class="cancelled-banner" role="status">
  <strong>This trip was cancelled<span v-if="trip.hidden_at"> and is hidden from trip lists</span>.</strong>
  <span v-if="trip.cancellation_reason">“{{ trip.cancellation_reason }}”</span>
  <span>The plan and new expenses are locked. You can still record repayments and settle up.</span>
  <button v-if="isOrganizer" type="button" class="btn secondary" @click="manageTrip('reopen')">Reopen trip</button>
 </div>
 <TripEditDialog v-if="editingTrip" :trip="trip" :has-expenses="trip.expenses.length>0" @close="editingTrip=false"/>
 <TripCancelDialog v-if="cancelling" :trip-id="trip.id" :trip-name="trip.name" @close="cancelling=false"/>
 <TripNavigation v-model="screen" :badges="{group:pendingForMe}"/>

 <TripItinerary v-show="isPlan" :view="planView" :visible="isPlan" :trip-id="trip.id" :items="trip.itinerary_items" :timezone="trip.timezone" :is-organizer="isOrganizer && !cancelled" :google-maps="googleMaps" @navigate="screen=$event"/>

 <section v-if="screen==='expenses'" class="expenses-screen" aria-labelledby="expenses-title">
 <div class="screen-top"><h2 id="expenses-title">Expenses</h2><button v-if="!cancelled" class="btn add-expense-desktop" @click="newExpense"><Plus :size="16"/> Add expense</button></div>
 <div class="money-strip"><div><span>Group spending</span><strong>{{ money(spent) }}</strong></div><div><span>You paid</span><strong>{{ money(myContribution) }}</strong></div><div><span>Your balance</span><strong :class="{owe:myBalance<0,receive:myBalance>0}">{{ money(myBalance) }}</strong></div></div>
 <div class="filter-row"><div class="chips" role="group" aria-label="Filter expenses"><button v-for="f in quickFilters" :key="f[0]" type="button" :aria-pressed="quickFilter===f[0]" @click="quickFilter=f[0]">{{ f[1] }}</button></div><select v-model="statusFilter" aria-label="Filter by status" class="filter"><option value="all">Any status</option><option v-for="s in ['open','completed','closed','cancelled']" :key="s" :value="s">{{ s }}</option></select></div>
 <form v-if="adding" ref="editor" class="card form-grid expense-form" @submit.prevent="saveExpense"><h2 class="full">{{ editingId ? 'Edit expense & assigned shares' : 'Record a shared expense' }}</h2><ReceiptScanner v-if="!editingId" :trip-id="trip.id" @scanned="applyScan"/><label class="field">Title<input v-model="expense.title" required placeholder="Dinner by the beach"/></label><label class="field">Category<select v-model="expense.category_id" required><option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></label><label class="field">Total ({{ trip.currency_code }})<input v-model="expense.amount" type="number" min="0.01" max="1000000" step="0.01" required/></label><label class="field">Date<input v-model="expense.incurred_at" type="date" required/></label><label class="field">Merchant<input v-model="expense.merchant"/></label><label class="field">Split method<select v-model="expense.split_method"><option value="equal">Equally</option><option value="exact">Exact amounts</option><option value="percentage">Percentages</option><option value="shares">Shares</option><option value="itemized">By receipt item</option></select></label>
 <div class="full"><h3>Who paid the merchant?</h3><p class="muted">{{ editingId ? 'Recorded payments are preserved. Use Record payment to add another payment.' : 'Leave amounts blank if this expense has not been paid yet.' }}</p><div v-for="(payer,index) in expense.payers" :key="index" class="row" style="margin-top:10px"><label class="field" style="flex:1">Payer<select v-model="payer.id" :disabled="editingId!==null"><option v-for="m in trip.members" :key="m.id" :value="m.id">{{ m.user.name }}</option></select></label><label class="field" style="flex:1">Amount paid<input :disabled="editingId!==null" v-model="payer.amount" type="number" min="0.01" step="0.01"/></label><button v-if="index>0 && !editingId" class="btn secondary" type="button" @click="expense.payers.splice(index,1)">Remove</button></div><button v-if="!editingId" class="btn secondary section-gap" type="button" @click="expense.payers.push({id:currentMemberId,amount:''})">Add another payer</button></div>
 <div v-if="expense.split_method!=='itemized'" class="full"><h3>Who shares this expense?</h3><div v-for="person in expense.people" :key="person.id" class="row" style="margin-top:12px"><label style="flex:1"><input v-model="person.selected" type="checkbox"/> {{ name(person.id) }}</label><label v-if="expense.split_method!=='equal' && person.selected" class="field">{{ expense.split_method==='percentage'?'Percentage':expense.split_method==='exact'?'Amount':'Shares' }}<input v-model="person.value" type="number" min="0" :step="expense.split_method==='shares'?'1':'0.01'" required/></label></div></div>
 <div v-else class="full"><h3>Receipt items</h3><p class="muted">Enter reviewed line totals, including allocated tax or discounts. Each line is split equally among its selected travelers.</p><div v-for="(item,index) in expense.items" :key="index" class="item-entry"><div class="form-grid"><label class="field">Item<input v-model="item.description" required/></label><label class="field">Line total<input v-model="item.amount" type="number" min="0.01" step="0.01" required/></label></div><div class="row" style="margin-top:12px"><label v-for="m in trip.members" :key="m.id"><input v-model="item.member_ids" type="checkbox" :value="m.id"/> {{ m.user.name }}</label><button v-if="expense.items.length>1" type="button" class="btn secondary" @click="expense.items.splice(index,1)">Remove</button></div></div><button type="button" class="btn secondary" @click="expense.items.push({description:'',amount:'',member_ids:[]})">Add receipt line</button></div>
 <div class="full" style="background:#f0f5e9;border-radius:12px;padding:18px" aria-live="polite"><h3>Calculated shares</h3><p v-if="splitPreview.error" class="muted">{{ splitPreview.error }}</p><template v-else><p v-for="(amount,id) in splitPreview.amounts" :key="id" class="row spread" style="margin-top:10px;font-size:14px"><span>{{ name(Number(id)) }}</span><strong>{{ money(amount) }}</strong></p><p class="muted" style="margin-top:12px">Total assigned: {{ money(Object.values(splitPreview.amounts).reduce((sum,amount)=>sum+amount,0)) }}</p></template></div><div class="row full"><button class="btn" :disabled="expense.processing || !!splitPreview.error">{{ editingId ? 'Save changes' : 'Save expense' }}</button><button class="btn secondary" type="button" @click="adding=false">Cancel</button></div></form>
 <div v-if="!shown.length" class="card empty"><ReceiptText :size="36" style="margin:0 auto 16px;color:#839362"/><h2>{{ trip.expenses.length ? 'Nothing matches this filter.' : 'No expenses here yet.' }}</h2><p class="muted">{{ trip.expenses.length ? 'Try another filter.' : 'Record the first shared bill to keep everyone on the same page.' }}</p><button v-if="!trip.expenses.length && !cancelled" class="btn" @click="newExpense"><Plus :size="16"/> Add the first expense</button></div>
 <ul v-else class="expense-list">
 <li v-for="e in shown" :key="e.id" class="expense-row" :class="{open:openExpense===e.id}">
 <button type="button" class="expense-summary" :aria-expanded="openExpense===e.id" @click="openExpense=openExpense===e.id?null:e.id">
 <span class="expense-main"><span class="expense-title">{{ e.title }}</span><span class="expense-meta">{{ payers(e) }} · {{ shortDate(e.incurred_at) }} · {{ e.category.name }}</span></span>
 <span class="expense-side"><strong>{{ money(e.total_minor) }}</strong><span class="badge" :class="e.status">{{ e.status }}</span></span>
 <ChevronDown :size="16" class="expense-chevron" aria-hidden="true"/>
 </button>
 <div v-if="openExpense===e.id" class="expense-body">
 <div class="row spread"><p class="muted">{{ e.category.name }} · {{ e.incurred_at }}<span v-if="e.merchant"> · {{ e.merchant }}</span></p><span v-if="canManage(e)" class="expense-actions" role="group" :aria-label="'Actions for '+e.title">
<button v-if="isOrganizer && !['closed','cancelled'].includes(e.status)" class="icon-action" title="Edit expense and shares" aria-label="Edit expense and shares" @click="editExpense(e)"><Pencil :size="16"/></button>
<button v-if="isOrganizer && e.status==='open'" class="icon-action" title="Mark merchant bill completed" aria-label="Mark merchant bill completed" :disabled="busyId===e.id" @click="status(e,'completed')"><CheckCheck :size="17"/></button>
<button v-if="isOrganizer && e.status==='open'" class="icon-action" title="Record payment to merchant" aria-label="Record payment to merchant" @click="payment.expense_id=e.id;payment.version=e.version;payment.amount=''"><Banknote :size="17"/></button>
<button v-if="isOrganizer && e.status==='completed'" class="icon-action" title="Close expense" aria-label="Close expense" :disabled="busyId===e.id" @click="status(e,'closed')"><LockKeyhole :size="16"/></button>
<button v-if="['open','draft','completed'].includes(e.status)" class="icon-action danger-icon" title="Cancel expense" aria-label="Cancel expense" @click="deletingId=null;cancellingId=cancellingId===e.id?null:e.id;actionErrors[e.id]=''"><CircleX :size="17"/></button><button v-if="canDelete(e)" class="icon-action danger-icon" title="Delete expense" aria-label="Delete expense" @click="cancellingId=null;deletingId=deletingId===e.id?null:e.id;actionErrors[e.id]=''"><Trash2 :size="16"/></button></span></div>
 <p v-if="actionErrors[e.id]" class="error-box section-gap" role="alert">{{ actionErrors[e.id] }}</p>
 <form v-if="cancellingId===e.id && canManage(e)" class="cancel-panel section-gap" @submit.prevent="status(e,'cancelled')"><h3>Cancel this expense?</h3><label class="field section-gap">Cancellation reason<input v-model="reason[e.id]" maxlength="2000" required placeholder="Why is this expense being cancelled?"/></label><label v-if="e.payments.length" class="void-confirm"><input type="checkbox" v-model="voidPayments[e.id]" required/><span>Void this expense and its linked payments/reimbursements from trip balances. Keep the history. I understand this does not refund money.</span></label><div class="row section-gap"><button class="btn danger" :disabled="busyId===e.id || !reason[e.id]?.trim() || (e.payments.length>0&&!voidPayments[e.id])">{{ busyId===e.id?'Cancelling…':'Confirm cancellation' }}</button><button type="button" class="btn secondary" @click="cancellingId=null">Keep expense</button></div></form>
 <form v-if="deletingId===e.id && canDelete(e)" class="cancel-panel section-gap" @submit.prevent="destroyExpense(e)"><h3>Delete this expense?</h3><p class="muted section-gap">It is removed for everyone, including its shares and receipt photos. Use this for duplicates or mistakes.</p><div class="row section-gap"><button class="btn danger" :disabled="busyId===e.id">{{ busyId===e.id?'Deleting…':'Delete expense' }}</button><button type="button" class="btn secondary" @click="deletingId=null">Keep expense</button></div></form>
 <p v-if="e.cancellation_reason" class="muted">Cancelled: {{ e.cancellation_reason }}</p>
 <div class="detail-grid"><div><h3>Assigned shares</h3><div v-for="share in (expenseShares[e.id]??[]).filter(s=>s.share_minor>0)" :key="share.member_id" class="assigned-share">
<div class="row spread muted"><span>{{ name(share.member_id) }}</span><strong>{{ money(share.share_minor) }}</strong></div>
<div v-if="e.status!=='cancelled'" class="row share-status">
<span v-if="share.remaining_minor===0" class="paid-label"><Check :size="14"/> Paid / covered</span>
<template v-else><span class="muted">Remaining {{ money(share.remaining_minor) }}</span><button v-if="canConfirmShare(e)" class="share-paid" :disabled="busyId===e.id" @click="openShareConfirmation(e,share.member_id)"><Check :size="14"/> Mark as paid</button></template>
</div>
<div v-if="shareConfirm?.expenseId===e.id && shareConfirm.memberId===share.member_id" class="share-confirm"><label v-if="existingRepayments(e,share.member_id).length" class="field">Payment to confirm<select v-model="paymentSource"><option v-for="repayment in existingRepayments(e,share.member_id)" :key="repayment.id" :value="String(repayment.id)">Use recorded {{ money(repayment.amount_minor) }} to {{ name(repayment.to_member_id) }} ({{ repayment.status }})</option><option value="new">A separate new payment</option></select></label><p>Confirm {{ money(confirmableAmount(e,share.member_id)) }} received from {{ name(share.member_id) }}{{ isOrganizer ? ' by the traveler(s) who covered this bill' : ' by you' }}. {{ paymentSource==='new' ? 'This records a separate payment already received.' : 'The recorded reimbursement will be applied to this expense, without counting it twice.' }} No money is sent.</p><div class="row"><button class="btn" :disabled="busyId===e.id" @click="markSharePaid(e,share.member_id)">Confirm paid</button><button class="btn secondary" @click="shareConfirm=null">Back</button></div></div>
</div></div><div><h3>Paid to merchant</h3><p v-for="(p,i) in e.payments" :key="i" class="row spread muted">{{ name(p.trip_member_id) }} <strong>{{ money(p.amount_minor) }}</strong></p><p v-if="!e.payments.length" class="muted">No payments recorded.</p></div></div><p v-for="item in e.items" :key="item.id" class="muted">{{ item.description }} · {{ money(item.line_total_minor) }}</p><div class="row section-gap"><a v-for="r in e.receipts" :key="r.id" :href="`/receipts/${r.id}`" class="btn secondary">Download receipt #{{ r.id }}</a></div><label v-if="!['closed','cancelled'].includes(e.status)" class="field section-gap">Attach receipt (JPG, PNG, PDF · up to 10 MB)<input type="file" accept="image/jpeg,image/png,application/pdf" :disabled="receipt.processing" @change="upload($event,e)"/><span class="muted">Stored privately. Automatic reading is not configured yet; enter receipt lines above.</span></label>
 <form v-if="payment.expense_id===e.id && e.status==='open'" class="form-grid section-gap" @submit.prevent="payment.transform(d=>({trip_member_id:d.trip_member_id,amount_minor:minor(d.amount),version:d.version})).post(`/trips/${trip.id}/expenses/${e.id}/payments`,{preserveScroll:true,onSuccess:()=>payment.expense_id=0})"><label class="field">Payer<select v-model="payment.trip_member_id"><option v-for="m in trip.members" :key="m.id" :value="m.id">{{ m.user.name }}</option></select></label><label class="field">Amount<input v-model="payment.amount" type="number" min="0.01" step="0.01" required/></label><div class="row full"><button class="btn" :disabled="payment.processing">Save payment</button><button class="btn secondary" type="button" @click="payment.expense_id=0">Cancel</button></div></form>
 </div>
 </li>
 </ul>
 <button type="button" v-if="!cancelled" class="fab" aria-label="Add expense" @click="newExpense"><Plus :size="24"/></button>
 </section>

 <TripPhotos v-if="screen==='photos'" :trip-id="trip.id" :trip-name="trip.name"/>
 <TripGroup v-if="screen==='group'" :trip-id="trip.id" :trip-name="trip.name" :organizer-user-id="trip.organizer_user_id" :currency="trip.currency_code" :timezone="trip.timezone" :members="trip.members" :balances="balances" :settlements="trip.settlements" :expenses="trip.expenses" :current-member-id="currentMemberId" :is-organizer="isOrganizer" :invitation-url="invitationUrl"/>
</TravelLayout>
</template>
<style scoped>.expense-actions{display:inline-flex;gap:4px;align-items:center}.icon-action{display:inline-flex;align-items:center;justify-content:center;min-width:34px;min-height:34px;border-radius:8px;color:#62745b;background:#f2f5ed;border:1px solid #e2e8da}.icon-action:hover{background:#e3ecd9;color:#254d3d}.icon-action:disabled,.share-paid:disabled{opacity:.5;cursor:wait}.danger-icon{color:#a3543e}.danger-icon:hover{background:#fff0e9;color:#9d3e27}.assigned-share{padding:10px 0;border-bottom:1px solid #edf0e8}.share-status{justify-content:flex-end;margin-top:7px;gap:8px}.share-status .muted{font-size:11px}.share-paid{display:inline-flex;align-items:center;gap:4px;font-size:11px;font-weight:700;border:1px solid #cadbc3;background:#f0f7eb;color:#37623c;padding:5px 9px;border-radius:7px;min-height:32px}.paid-label{display:flex;gap:4px;align-items:center;color:#427648;font-size:11px;font-weight:650}.share-confirm,.cancel-panel{border:1px solid #dedfcd;background:#fbfcf6;padding:16px;border-radius:10px}.share-confirm p{font-size:12px;line-height:1.7;margin-bottom:12px}.void-confirm{display:flex;align-items:flex-start;gap:10px;font-size:13px;line-height:1.6;margin-top:16px}.void-confirm input{margin-top:4px}.filter{border:1px solid #dce5d7;border-radius:8px;background:white;font-size:12px;padding:7px 28px 7px 10px}.expense-card{margin-bottom:16px}.detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:28px;margin:20px 0}.detail-grid h3{font-size:13px;margin-bottom:12px}.item-entry{padding:18px;border:1px solid #e0e7da;border-radius:10px;margin:14px 0}.balance-row{display:flex;justify-content:space-between;gap:12px;padding:18px 0;border-bottom:1px solid #e8ede3;font-size:14px}.balance-row:last-child{border:0}@media(max-width:640px){.detail-grid{grid-template-columns:1fr}}
.expenses-screen{display:grid;gap:12px}
.screen-top{display:flex;align-items:center;justify-content:space-between;gap:12px}
.travel-app .screen-top h2{font-size:22px;letter-spacing:-.6px}
.money-strip{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}
.money-strip div{display:grid;gap:2px;padding:10px 12px;border-radius:14px;background:#fff;border:1px solid #e0e7dc;min-width:0}
.money-strip span{font-size:11px;color:#6c7c72;font-weight:600}
.money-strip strong{font-size:16px;font-weight:800;letter-spacing:-.4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.owe{color:#a6573e}.receive{color:#3d7650}
.filter-row{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}
.chips{display:flex;gap:6px}
.chips button{min-height:40px;padding:0 14px;border-radius:999px;border:1px solid #d5dfd5;background:#fff;color:#214e3e;font-size:13px;font-weight:650;cursor:pointer}
.chips button[aria-pressed="true"]{background:#214e3e;border-color:#214e3e;color:#fff}
.filter{min-height:40px;text-transform:capitalize}
.expense-list{list-style:none;margin:0;padding:0;display:grid;gap:8px}
.expense-row{background:#fff;border:1px solid #e0e7dc;border-radius:16px;overflow:hidden}
.expense-row.open{border-color:#c4d4b0;box-shadow:0 3px 12px #233d2610}
.expense-summary{display:grid;grid-template-columns:minmax(0,1fr) auto auto;align-items:center;gap:10px;width:100%;min-height:64px;padding:12px 14px;border:0;background:transparent;text-align:left;color:inherit;font:inherit;cursor:pointer}
.expense-summary:hover{background:#fafcf7}
.expense-summary:focus-visible{outline:3px solid #9cb865;outline-offset:-3px}
.expense-main{display:grid;min-width:0}
.expense-title{font-size:15px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.expense-meta{font-size:12px;color:#6c7c72;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.expense-side{display:grid;justify-items:end;gap:4px}
.expense-side strong{font-size:15px;white-space:nowrap}
.expense-chevron{color:#8a978f;transition:transform .2s}
.expense-row.open .expense-chevron{transform:rotate(180deg)}
.expense-body{display:grid;gap:12px;padding:4px 14px 16px;border-top:1px solid #eef2ea}
.expense-body .detail-grid{margin:8px 0}
.fab{position:fixed;z-index:35;right:max(16px,env(safe-area-inset-right));bottom:calc(76px + env(safe-area-inset-bottom));display:grid;place-items:center;width:56px;height:56px;border-radius:18px;border:0;background:#214e3e;color:#fff;box-shadow:0 8px 24px #214e3e55;cursor:pointer}
.fab:focus-visible{outline:3px solid #9cb865;outline-offset:3px}
.add-expense-desktop{display:none}
@media(min-width:1024px){.fab{display:none}.add-expense-desktop{display:inline-flex}.money-strip strong{font-size:19px}}
@media(max-width:360px){.money-strip strong{font-size:14px}.money-strip div{padding:9px 10px}}
@media(prefers-reduced-motion:reduce){.expense-chevron{transition:none}}
.cancelled-banner{display:grid;gap:6px;justify-items:start;padding:16px 18px;margin-bottom:16px;border-radius:16px;background:#fff4ef;border:1px solid #edc9bd;color:#7d3424;font-size:14px;line-height:1.5}.cancelled-banner .btn{margin-top:4px}
</style>

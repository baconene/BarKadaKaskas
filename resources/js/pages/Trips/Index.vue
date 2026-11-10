<script setup lang="ts">
import { Head, Link, router } from '@inertiajs/vue3';
import PlanTripWizard from '@/components/trip/PlanTripWizard.vue';
import { onBeforeUnmount, ref } from 'vue';
import LoadingImage from '@/components/LoadingImage.vue';
import { MapPin, Plus, ArrowUpRight, Users, CalendarDays, EyeOff } from 'lucide-vue-next';
import TravelLayout from '@/layouts/TravelLayout.vue';
/**
 * The trip card's badge from its dates (in the trip's time zone), like the Android app: upcoming, ongoing
 * (day N of M) or completed; a cancelled trip says so. The stored status alone never moves past "planning".
 */
function tripPhase(trip:{starts_on:string;ends_on:string;status:string;timezone?:string}):{label:string;tone:string}{
    if(trip.status==='cancelled')return{label:'Cancelled',tone:'cancelled'};
    let today:string;
    try{today=new Intl.DateTimeFormat('en-CA',{timeZone:trip.timezone||undefined,year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());}
    catch{today=new Intl.DateTimeFormat('en-CA',{year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());}
    const start=String(trip.starts_on).slice(0,10), end=String(trip.ends_on).slice(0,10);
    const days=(a:string,b:string)=>Math.round((Date.parse(`${b}T00:00:00Z`)-Date.parse(`${a}T00:00:00Z`))/86400000);
    if(today<start){const n=days(today,start);return{label:n===1?'Tomorrow':`In ${n} days`,tone:'upcoming'};}
    if(today>end)return{label:'Completed',tone:'closed'};
    return{label:start===end?'Today':`Ongoing · Day ${days(start,today)+1} of ${days(start,end)+1}`,tone:'completed'};
}
defineProps<{mapsKey?: string | null; aiPlanner?: boolean; trips: {id:number;name:string;destination:string;starts_on:string;ends_on:string;members_count:number;status:string;timezone?:string;cover_url?:string|null}[]; hiddenCount?: number; showingHidden?: boolean}>();
const creating = ref(false);
// The card being opened: on slow connections it shows "Opening…" until the trip page arrives.
const opening = ref<number | null>(null);
const stopListening = router.on('finish', () => { opening.value = null; });
onBeforeUnmount(stopListening);
</script>
<template>
 <Head title="My trips" /><TravelLayout>
  <div class="page-heading"><div><p class="eyebrow">Your next story starts here</p><h1>Adventures, together.</h1><p class="muted" style="margin-top:12px">The plans, the people, and every shared peso. All in one place.</p></div><button class="btn" @click="creating = true"><Plus :size="17" /> Plan a trip</button></div>
  <PlanTripWizard v-if="creating" :maps-key="mapsKey" :ai-planner="aiPlanner" @close="creating = false" />
  <div class="row spread" style="margin:32px 0 18px"><h2>{{ showingHidden ? 'Hidden trips' : 'Your trips' }} <span class="muted">({{ trips.length }})</span></h2><Link v-if="showingHidden" href="/dashboard" class="hidden-link">← Back to your trips</Link><Link v-else-if="hiddenCount" href="/dashboard?hidden=1" class="hidden-link"><EyeOff :size="15" /> {{ hiddenCount }} hidden trip{{ hiddenCount === 1 ? '' : 's' }}</Link><span v-else class="muted">A little planning. A lot of memories.</span></div>
  <p v-if="showingHidden" class="muted" style="margin:-8px 0 18px">Cancelled trips the organizer hid. Open one to reopen it or show it again.</p>
  <div v-if="!trips.length && showingHidden" class="card empty"><h2>No hidden trips.</h2></div>
  <div v-else-if="!trips.length" class="card empty"><CompassArt /><h2>Your first adventure is waiting.</h2><p class="muted">Create a trip, invite your friends, and leave the expense math to us.</p><button class="btn" @click="creating=true">Create your first trip <ArrowUpRight :size="16" /></button></div>
  <div v-else class="trip-grid"><Link v-for="(trip,index) in trips" :key="trip.id" :href="`/trips/${trip.id}`" class="trip-card" :class="{ 'is-opening': opening === trip.id }" :aria-busy="opening === trip.id" @click="opening = trip.id"><span v-if="opening === trip.id" class="opening-badge" role="status"><span class="spinner" aria-hidden="true" /> Opening…</span><div class="trip-art" :class="[`art-${index%3}`, { 'has-cover': trip.cover_url }]"><LoadingImage v-if="trip.cover_url" :src="trip.cover_url" class="trip-cover" :eager="index < 3" /><MapPin v-else :size="46" :stroke-width="1" /><span class="badge" :class="tripPhase(trip).tone">{{ tripPhase(trip).label }}</span><div class="hills" /></div><div style="padding:22px"><p class="eyebrow">{{ trip.destination }}</p><h2>{{ trip.name }}</h2><div class="row muted" style="margin-top:20px"><CalendarDays :size="15" /> {{ trip.starts_on }} — {{ trip.ends_on }}</div><div class="row spread" style="margin-top:18px"><span class="row muted"><Users :size="15" /> {{ trip.members_count }} traveler{{ trip.members_count === 1 ? '' : 's' }}</span><ArrowUpRight :size="20" /></div></div></Link></div>
 </TravelLayout>
</template>
<script lang="ts">
import { defineComponent, h } from 'vue';
const CompassArt = defineComponent({render:()=>h('div',{style:'font-size:54px;color:#81955b;margin-bottom:18px'},'✳')});
</script>
<style scoped>.trip-cover{transition:transform .3s}.trip-card:hover .trip-cover{transform:scale(1.04)}.has-cover .hills{display:none}.has-cover .badge{z-index:1}.hidden-link{display:inline-flex;align-items:center;gap:6px;font-size:14px;font-weight:650;color:#214e3e;padding:8px 12px;border-radius:10px;background:#edf2e6}.trip-card:has(.badge.cancelled) .trip-art{filter:grayscale(.6);opacity:.8}.trip-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px}.trip-card{background:white;border:1px solid #dfe6d9;border-radius:18px;overflow:hidden;transition:transform .2s}.trip-card:hover{transform:translateY(-4px)}.trip-art{height:154px;position:relative;overflow:hidden;background:#dce7cb;padding:22px;color:#507044}.trip-art .badge{position:absolute;right:18px;top:18px;background:#ffffffb5}.hills{position:absolute;width:110%;height:130px;border-radius:50%;background:#718e6340;bottom:-75px;right:-50px;transform:rotate(-12deg)}.art-1{background:#eee3d1;color:#947344}.art-2{background:#dce9ea;color:#47787c}@media(max-width:950px){.trip-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:640px){.trip-grid{grid-template-columns:1fr}}</style>

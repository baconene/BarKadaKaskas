<script setup lang="ts">
import { Head, Link, useForm } from '@inertiajs/vue3';
import { ref } from 'vue';
import { MapPin, Plus, ArrowUpRight, Users, CalendarDays } from 'lucide-vue-next';
import TravelLayout from '@/layouts/TravelLayout.vue';
defineProps<{trips: {id:number;name:string;destination:string;starts_on:string;ends_on:string;members_count:number;status:string}[]}>();
const creating = ref(false);
const form = useForm({name:'',destination:'',description:'',starts_on:'',ends_on:'',timezone:'Asia/Manila',currency_code:'PHP'});
</script>
<template>
 <Head title="My trips" /><TravelLayout>
  <div class="page-heading"><div><p class="eyebrow">Your next story starts here</p><h1>Adventures, together.</h1><p class="muted" style="margin-top:12px">The plans, the people, and every shared peso. All in one place.</p></div><button class="btn" @click="creating = !creating"><Plus :size="17" /> Plan a trip</button></div>
  <form v-if="creating" class="card form-grid" style="margin-bottom:28px" @submit.prevent="form.post('/trips')"><h2 class="full">Where are we going?</h2><label class="field">Trip name<input v-model="form.name" required placeholder="The barkada goes to Palawan" maxlength="120" /></label><label class="field">Destination<input v-model="form.destination" required placeholder="El Nido, Philippines" /></label><label class="field">Start date<input v-model="form.starts_on" type="date" required /></label><label class="field">End date<input v-model="form.ends_on" type="date" :min="form.starts_on" required /></label><label class="field">Trip currency<select v-model="form.currency_code"><option v-for="currency in ['PHP','USD','EUR','GBP','SGD','AUD','CAD']" :key="currency">{{ currency }}</option></select></label><label class="field">Time zone<input v-model="form.timezone" required placeholder="Asia/Manila" /></label><label class="field full">About this trip<textarea v-model="form.description" rows="2" /></label><div class="row full"><button class="btn" :disabled="form.processing">Create trip</button><button type="button" class="btn secondary" @click="creating=false">Cancel</button></div></form>
  <div class="row spread" style="margin:32px 0 18px"><h2>Your trips <span class="muted">({{ trips.length }})</span></h2><span class="muted">A little planning. A lot of memories.</span></div>
  <div v-if="!trips.length" class="card empty"><CompassArt /><h2>Your first adventure is waiting.</h2><p class="muted">Create a trip, invite your friends, and leave the expense math to us.</p><button class="btn" @click="creating=true">Create your first trip <ArrowUpRight :size="16" /></button></div>
  <div v-else class="trip-grid"><Link v-for="(trip,index) in trips" :key="trip.id" :href="`/trips/${trip.id}`" class="trip-card"><div class="trip-art" :class="`art-${index%3}`"><MapPin :size="46" :stroke-width="1" /><span class="badge">{{ trip.status }}</span><div class="hills" /></div><div style="padding:22px"><p class="eyebrow">{{ trip.destination }}</p><h2>{{ trip.name }}</h2><div class="row muted" style="margin-top:20px"><CalendarDays :size="15" /> {{ trip.starts_on }} — {{ trip.ends_on }}</div><div class="row spread" style="margin-top:18px"><span class="row muted"><Users :size="15" /> {{ trip.members_count }} traveler{{ trip.members_count === 1 ? '' : 's' }}</span><ArrowUpRight :size="20" /></div></div></Link></div>
 </TravelLayout>
</template>
<script lang="ts">
import { defineComponent, h } from 'vue';
const CompassArt = defineComponent({render:()=>h('div',{style:'font-size:54px;color:#81955b;margin-bottom:18px'},'✳')});
</script>
<style scoped>.trip-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px}.trip-card{background:white;border:1px solid #dfe6d9;border-radius:18px;overflow:hidden;transition:transform .2s}.trip-card:hover{transform:translateY(-4px)}.trip-art{height:154px;position:relative;overflow:hidden;background:#dce7cb;padding:22px;color:#507044}.trip-art .badge{position:absolute;right:18px;top:18px;background:#ffffffb5}.hills{position:absolute;width:110%;height:130px;border-radius:50%;background:#718e6340;bottom:-75px;right:-50px;transform:rotate(-12deg)}.art-1{background:#eee3d1;color:#947344}.art-2{background:#dce9ea;color:#47787c}@media(max-width:950px){.trip-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:640px){.trip-grid{grid-template-columns:1fr}}</style>

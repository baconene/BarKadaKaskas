<script setup lang="ts">
import { Head, useForm } from '@inertiajs/vue3';
import TravelLayout from '@/layouts/TravelLayout.vue';
defineProps<{tripName:string;token:string}>();
const form=useForm({});
</script>
<template><Head title="Join a trip"/><TravelLayout><div class="card empty"><p class="eyebrow">You're invited</p><h1>{{ tripName }}</h1><p class="muted">Join your friends to see the itinerary and share trip expenses.</p><form @submit.prevent="form.post(`/join/${token}`)"><button class="btn" :disabled="form.processing">Join the trip</button></form></div></TravelLayout></template>

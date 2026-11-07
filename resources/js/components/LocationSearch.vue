<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { MapPin, MapPinCheck } from 'lucide-vue-next';
import { loadGoogleMaps } from '@/lib/itinerary';

export type PickedPlace = { latitude: number; longitude: number };

const props = defineProps<{ browserKey: string; id: string; placeholder?: string; maxlength?: number }>();
const location = defineModel<string>({ required: true });
const pinned = defineModel<PickedPlace | null>('pinned', { default: null });

type Suggestion = { id: string; main: string; secondary: string; prediction: google.maps.places.PlacePrediction };
const suggestions = ref<Suggestion[]>([]);
const open = ref(false);
const active = ref(-1);
const loading = ref(false);
const unavailable = ref(false);
let places: google.maps.PlacesLibrary | undefined;
let session: google.maps.places.AutocompleteSessionToken | undefined;
let timer: number | undefined;
let request = 0;
const listId = `${props.id}-suggestions`;

async function library() {
    if (places) return places;
    await loadGoogleMaps(props.browserKey);
    places = (await google.maps.importLibrary('places')) as google.maps.PlacesLibrary;
    return places;
}

function onInput() {
    pinned.value = null; // Typed text no longer matches the picked place.
    window.clearTimeout(timer);
    const query = location.value.trim();
    if (!props.browserKey || unavailable.value || query.length < 3) { suggestions.value = []; open.value = false; return; }
    timer = window.setTimeout(() => search(query), 250);
}

async function search(query: string) {
    const current = ++request;
    loading.value = true;
    try {
        const lib = await library();
        session ??= new lib.AutocompleteSessionToken();
        const { suggestions: found } = await lib.AutocompleteSuggestion.fetchAutocompleteSuggestions({ input: query, sessionToken: session });
        if (current !== request) return;
        suggestions.value = found.flatMap(s => s.placePrediction ? [{
            id: s.placePrediction.placeId,
            main: s.placePrediction.mainText?.text ?? s.placePrediction.text.text,
            secondary: s.placePrediction.secondaryText?.text ?? '',
            prediction: s.placePrediction,
        }] : []).slice(0, 5);
        active.value = -1;
        open.value = suggestions.value.length > 0;
    } catch (e) {
        if (current !== request) return;
        suggestions.value = []; open.value = false;
        if (/PERMISSION_DENIED|REQUEST_DENIED|could not load/i.test(String(e))) unavailable.value = true;
    } finally {
        if (current === request) loading.value = false;
    }
}

async function choose(suggestion: Suggestion) {
    open.value = false; request++;
    const fallback = [suggestion.main, suggestion.secondary].filter(Boolean).join(', ');
    location.value = fallback.slice(0, props.maxlength ?? 200);
    try {
        const place = suggestion.prediction.toPlace();
        await place.fetchFields({ fields: ['displayName', 'formattedAddress', 'location'] });
        const address = place.formattedAddress ?? '';
        const name = place.displayName ?? suggestion.main;
        const label = address.toLowerCase().startsWith(name.toLowerCase()) ? address : [name, address].filter(Boolean).join(', ');
        location.value = (label || fallback).slice(0, props.maxlength ?? 200);
        pinned.value = place.location ? { latitude: place.location.lat(), longitude: place.location.lng() } : null;
    } catch {
        pinned.value = null; // Keep the chosen text; the map will look it up instead.
    } finally {
        session = undefined; // A session ends once a place is selected.
    }
}

function onKeydown(event: KeyboardEvent) {
    if (!open.value || !suggestions.value.length) return;
    if (event.key === 'ArrowDown') { event.preventDefault(); active.value = (active.value + 1) % suggestions.value.length; }
    else if (event.key === 'ArrowUp') { event.preventDefault(); active.value = (active.value - 1 + suggestions.value.length) % suggestions.value.length; }
    else if (event.key === 'Enter' && active.value >= 0) { event.preventDefault(); choose(suggestions.value[active.value]); }
    else if (event.key === 'Escape') { open.value = false; }
}

function onBlur() { window.setTimeout(() => { open.value = false; }, 150); }
onBeforeUnmount(() => { window.clearTimeout(timer); request++; });
</script>

<template>
    <div class="location-search">
        <div class="location-input">
            <MapPin class="location-icon" :size="16" aria-hidden="true" />
            <input
                :id="id"
                v-model="location"
                type="text"
                role="combobox"
                autocomplete="off"
                :maxlength="maxlength ?? 200"
                :placeholder="placeholder"
                :aria-expanded="open"
                :aria-controls="listId"
                aria-autocomplete="list"
                :aria-activedescendant="active >= 0 ? `${listId}-${active}` : undefined"
                @input="onInput"
                @keydown="onKeydown"
                @focus="open = suggestions.length > 0"
                @blur="onBlur"
            />
        </div>
        <ul v-show="open" :id="listId" role="listbox" class="location-suggestions">
            <li
                v-for="(suggestion, index) in suggestions"
                :id="`${listId}-${index}`"
                :key="suggestion.id"
                role="option"
                :aria-selected="index === active"
                :class="{ active: index === active }"
                @mousedown.prevent="choose(suggestion)"
                @mouseenter="active = index"
            >
                <MapPin :size="15" aria-hidden="true" />
                <span><strong>{{ suggestion.main }}</strong><small v-if="suggestion.secondary">{{ suggestion.secondary }}</small></span>
            </li>
            <li class="location-attribution" aria-hidden="true">Powered by Google</li>
        </ul>
        <small v-if="pinned" class="location-pinned"><MapPinCheck :size="14" aria-hidden="true" /> Pinned on the map</small>
        <small v-else-if="loading">Searching Google Maps…</small>
        <small v-else-if="unavailable || !browserKey">Type the full address, including the town and country.</small>
        <small v-else>Start typing and pick a place from Google Maps for an exact pin.</small>
    </div>
</template>

<style scoped>
.location-search{position:relative;display:flex;flex-direction:column;gap:7px}
.location-input{position:relative}
.location-input input{padding-left:34px}
.location-icon{position:absolute;left:11px;top:50%;transform:translateY(-50%);color:#7a8c6a;pointer-events:none}
.location-suggestions{position:absolute;z-index:20;top:44px;left:0;right:0;list-style:none;margin:0;padding:6px;background:#fff;border:1px solid #d6dfd5;border-radius:12px;box-shadow:0 12px 32px #233d2620}
.location-suggestions li[role=option]{display:flex;gap:10px;align-items:flex-start;padding:9px 10px;border-radius:8px;cursor:pointer;color:#172f2a}
.location-suggestions li[role=option] svg{margin-top:2px;color:#72883a;flex-shrink:0}
.location-suggestions li.active{background:#eef3e6}
.location-suggestions strong{display:block;font-size:13px;font-weight:650}
.location-suggestions small{display:block;font-size:12px;font-weight:400;color:#738178;margin-top:2px}
.location-attribution{font-size:10px;color:#94a092;text-align:right;padding:4px 8px 2px;font-weight:500}
.location-search>small{font-weight:400;color:#71816f;line-height:1.6;font-size:12px}
.location-pinned{display:inline-flex;align-items:center;gap:5px;color:#276c47!important;font-weight:600!important}
</style>

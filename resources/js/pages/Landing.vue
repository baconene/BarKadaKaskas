<script setup lang="ts">
import { Head, Link, usePage } from '@inertiajs/vue3';
import { Compass, ArrowUpRight, Download, ShieldCheck, Smartphone, Apple } from 'lucide-vue-next';
import { computed } from 'vue';
import type { SharedData } from '@/types';
const props=defineProps<{android:{downloadUrl:string;releasesUrl:string}}>();
const page=usePage<SharedData>();
// Put the steps for the visitor's own phone first.
const isIphone=computed(()=>typeof navigator!=='undefined'&&/iPhone|iPad|iPod/i.test(navigator.userAgent));
</script>
<template><Head title="Travel together. Split fairly."/><div class="landing"><nav><a href="/" class="logo"><Compass/> BarKadaKaskas</a><Link :href="page.props.auth?.user?'/dashboard':'/login'">{{ page.props.auth?.user?'My trips':'Log in' }} ↗</Link></nav><main><p class="eyebrow">FOR THE TRIPS THAT BECOME STORIES</p><h1>More memories.<br/>Less “who owes who?”</h1><p class="intro">Bring your barkada. Plan the adventure. Share every expense fairly, from the first coffee to the last ride home.</p><Link :href="page.props.auth?.user?'/dashboard':'/register'" class="cta">Let's plan a trip <ArrowUpRight :size="20"/></Link><a href="#download" class="cta ghost"><Smartphone :size="19"/> Get the Android app</a><div class="features"><article><span>01 / PLAN</span><h2>One trip. Everyone in.</h2><p>Keep your itinerary and your friends in the same place.</p></article><article><span>02 / SHARE</span><h2>Your order. Your share.</h2><p>Split the whole bill or assign each receipt item to its people.</p></article><article><span>03 / SETTLE</span><h2>Clear balances. Happy friends.</h2><p>Track payments, close expenses, and get back to the good part.</p></article></div>
<section id="download" class="download" aria-labelledby="download-title">
 <div class="download-copy">
  <p class="eyebrow">GET THE APP</p>
  <h2 id="download-title">BarkadaKasKas on your phone</h2>
  <p class="notice"><ShieldCheck :size="18" aria-hidden="true"/> <span><strong>Not on Google Play or the App Store (yet).</strong> Download the Android app directly here. This page is the official download.</span></p>
  <a :href="props.android.downloadUrl" class="cta download-btn" download><Download :size="20"/> Download for Android <small>APK</small></a>
  <p class="small">Always the latest version. <a :href="props.android.releasesUrl" target="_blank" rel="noopener">See all versions</a></p>
 </div>
 <div class="steps" :class="{ iphone: isIphone }">
  <article class="android-steps">
   <h3><Smartphone :size="18" aria-hidden="true"/> Install on Android</h3>
   <ol>
    <li>Open this page on your Android phone and tap <strong>Download for Android</strong>.</li>
    <li>If Chrome says the file might be harmful, tap <strong>Download anyway</strong>. Apps from outside Google Play always show this warning.</li>
    <li>Open <strong>BarkadaKasKas.apk</strong> from your notifications or the Downloads app.</li>
    <li>When asked, allow <strong>Install unknown apps</strong> for your browser, go back, then tap <strong>Install</strong>.</li>
    <li>Open <strong>BarkadaKasKas</strong> (the green <strong>BK</strong> icon) and log in with your usual email and password.</li>
   </ol>
   <p class="small">Updating: download again from this page and tap Install. Your trips stay on the server, so nothing is lost.</p>
  </article>
  <article class="iphone-steps">
   <h3><Apple :size="18" aria-hidden="true"/> On iPhone</h3>
   <p>The iPhone app isn't available for download yet. Until then, use BarkadaKasKas in Safari: open this site, tap <strong>Share</strong>, then <strong>Add to Home Screen</strong>.</p>
  </article>
 </div>
</section>
</main><footer>BarKadaKaskas · Made for going places, together.</footer></div></template>
<style scoped>.landing{min-height:100vh;background:#f4f5ed;color:#244735;font-family:ui-sans-serif,system-ui}.landing nav{display:flex;justify-content:space-between;align-items:center;max-width:1160px;margin:auto;padding:30px 24px;font-size:14px}.logo{display:flex;gap:9px;align-items:center;font-size:20px;font-weight:800}.landing main{max-width:1160px;margin:auto;padding:70px 24px}.eyebrow{font-size:11px;letter-spacing:2px;color:#7a8b50;font-weight:800}.landing h1{font-size:clamp(42px,6.8vw,82px);font-weight:750;letter-spacing:-3px;line-height:1.08;margin:24px 0}.intro{font-size:18px;line-height:1.7;max-width:560px;color:#72806b}.cta{display:inline-flex;gap:28px;align-items:center;background:#264d3b;color:white;border-radius:10px;padding:17px 24px;margin-top:32px;font-weight:650}.features{display:grid;grid-template-columns:repeat(3,1fr);gap:30px;margin-top:90px;border-top:1px solid #d7deca;padding-top:30px}.features span{font-size:10px;letter-spacing:2px;color:#819365}.features h2{font-size:19px;font-weight:700;margin:15px 0 10px}.features p{color:#74816b;line-height:1.6;font-size:14px}.landing footer{max-width:1160px;margin:auto;padding:24px;font-size:12px;color:#819077}.cta.ghost{background:transparent;color:#264d3b;border:1.5px solid #264d3b;margin-left:12px;gap:10px}
.download{display:grid;grid-template-columns:1fr 1.1fr;gap:40px;margin-top:90px;padding:36px;border-radius:24px;background:#fff;border:1px solid #dfe5d3;scroll-margin-top:20px}
.download h2{font-size:clamp(26px,3.4vw,38px);font-weight:750;letter-spacing:-1px;line-height:1.15;margin:14px 0 16px}
.notice{display:flex;gap:10px;align-items:flex-start;padding:14px 16px;border-radius:14px;background:#f0f5e9;color:#3e5a33;font-size:14px;line-height:1.55}
.notice svg{flex-shrink:0;margin-top:2px}
.download-btn{margin-top:22px;gap:12px}.download-btn small{font-size:11px;font-weight:700;letter-spacing:1px;padding:3px 7px;border-radius:6px;background:#ffffff26}
.small{font-size:13px;color:#7b8873;margin-top:12px;line-height:1.6}.small a{color:#264d3b;font-weight:650;text-decoration:underline}
.steps{display:grid;gap:16px;align-content:start}.steps.iphone .iphone-steps{order:-1}
.steps article{padding:20px 22px;border-radius:16px;background:#f7f8f3;border:1px solid #e3e8da}
.steps h3{display:flex;gap:8px;align-items:center;font-size:16px;font-weight:700;margin-bottom:10px}
.steps ol{display:grid;gap:9px;padding-left:20px;font-size:14px;line-height:1.55;color:#4d5d47;list-style:decimal}
.steps p{font-size:14px;line-height:1.6;color:#4d5d47}
@media(max-width:860px){.download{grid-template-columns:1fr;padding:24px;gap:26px}}
@media(max-width:650px){.cta.ghost{margin-left:0;margin-top:12px}.landing main .cta{width:100%;justify-content:center}.landing main{padding-top:45px}.features{grid-template-columns:1fr;margin-top:60px}.landing h1{letter-spacing:-2px}}</style>

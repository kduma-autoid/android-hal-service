<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import RollingQrReader from '../components/RollingQrReader.vue';
import RollingQrGenerator from '../components/RollingQrGenerator.vue';

const route = useRoute();
const router = useRouter();
const tab = computed(() => (route.query.tab === 'generator' ? 'generator' : 'reader'));

function show(next: 'reader' | 'generator') {
  router.replace({ query: { ...route.query, tab: next } });
}
</script>

<template>
  <h2>Rolling QR Code</h2>
  <p class="lead">
    A payload too long for one QR code, shown as a sequence of frames (<code>AQR|id|n/total|chunk</code>).
    The generator plays it on screen; the reader collects the frames from a barcode scanner in any order.
  </p>
  <div class="tabs">
    <button class="tab" :class="{ on: tab === 'reader' }" @click="show('reader')">Reader</button>
    <button class="tab" :class="{ on: tab === 'generator' }" @click="show('generator')">Generator</button>
  </div>
  <RollingQrReader v-if="tab === 'reader'" />
  <RollingQrGenerator v-else />
</template>

<style scoped>
h2 { margin-top: 0; }
.lead { font-size: 14px; color: #555; margin: 0 0 16px; }
.lead code { font-size: 12px; background: #f1f5f9; padding: 1px 4px; border-radius: 3px; }
.tabs { display: flex; gap: 4px; border-bottom: 1px solid #ddd; margin-bottom: 16px; }
.tab {
  padding: 8px 16px; border: 1px solid transparent; border-bottom: none; border-radius: 6px 6px 0 0;
  background: none; font-size: 14px; font-weight: 600; color: #666; cursor: pointer; margin-bottom: -1px;
}
.tab.on { background: #fff; border-color: #ddd; color: #0066cc; }
</style>

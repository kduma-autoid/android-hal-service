<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import type { DescribeResponse, InterfaceDescriptor } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import InterfaceSummaryCard from '../components/InterfaceSummaryCard.vue';

const { client, isConnected } = useHalClient();

const interfaces = ref<InterfaceDescriptor[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

const sortedInterfaces = computed(() =>
  [...interfaces.value].sort((a, b) => a.interfaceId.localeCompare(b.interfaceId)),
);

async function fetchInterfaces() {
  if (!client.value || !isConnected.value) return;
  loading.value = true;
  error.value = null;
  try {
    const result = await client.value.execute<DescribeResponse>('system.describe', {});
    interfaces.value = result.interfaces ?? [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    interfaces.value = [];
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (isConnected.value) fetchInterfaces();
});

watch(isConnected, (connected) => {
  if (connected) fetchInterfaces();
  else interfaces.value = [];
});
</script>

<template>
  <h2>Interfaces</h2>

  <div v-if="!isConnected" class="not-connected">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>

  <template v-else>
    <div class="toolbar">
      <button class="btn" @click="fetchInterfaces" :disabled="loading">
        {{ loading ? 'Loading...' : 'Refresh' }}
      </button>
    </div>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="loading && !interfaces.length" class="loading">Loading interfaces...</div>

    <div v-if="sortedInterfaces.length">
      <p class="count">{{ sortedInterfaces.length }} interface(s)</p>
      <InterfaceSummaryCard v-for="iface in sortedInterfaces" :key="iface.interfaceId" :iface="iface" />
    </div>

    <div v-if="!loading && !error && !sortedInterfaces.length" class="empty">
      No interfaces registered.
    </div>
  </template>
</template>

<style scoped>
h2 { margin-top: 0; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.btn {
  padding: 6px 14px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}
.btn:hover { background: #f5f5f5; }
.btn:disabled { opacity: 0.5; cursor: default; }
.not-connected {
  padding: 24px;
  text-align: center;
  color: #666;
  font-size: 15px;
}
.not-connected a { color: #0066cc; }
.error {
  padding: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  color: #dc2626;
  font-size: 13px;
  margin-bottom: 12px;
}
.loading {
  text-align: center;
  padding: 24px;
  color: #888;
}
.count {
  font-size: 13px;
  color: #888;
  margin: 0 0 12px;
}
.empty {
  text-align: center;
  padding: 24px;
  color: #888;
}
</style>

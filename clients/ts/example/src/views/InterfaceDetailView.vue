<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import type { DescribeResponse, InterfaceDescriptor } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import InterfaceCard from '../components/InterfaceCard.vue';

const route = useRoute();
const { client, isConnected } = useHalClient();

const iface = ref<InterfaceDescriptor | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

const interfaceId = computed(() => route.params.interfaceId as string);

async function fetchInterface() {
  if (!client.value || !isConnected.value) return;
  loading.value = true;
  error.value = null;
  try {
    const result = await client.value.execute<DescribeResponse>('system.describe', {});
    const found = (result.interfaces ?? []).find((i) => i.interfaceId === interfaceId.value);
    if (found) {
      iface.value = found;
    } else {
      error.value = `Interface not found: ${interfaceId.value}`;
      iface.value = null;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    iface.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (isConnected.value) fetchInterface();
});

watch(isConnected, (connected) => {
  if (connected) fetchInterface();
  else iface.value = null;
});

watch(interfaceId, fetchInterface);
</script>

<template>
  <div>
    <router-link to="/interfaces" class="back-link">&larr; Back to Interfaces</router-link>

    <div v-if="!isConnected" class="not-connected">
      Not connected.
      <router-link to="/settings">Go to Settings</router-link>
      to configure and connect.
    </div>

    <template v-else>
      <div v-if="error" class="error">{{ error }}</div>

      <div v-if="loading && !iface" class="loading">Loading interface details...</div>

      <InterfaceCard v-if="iface" :iface="iface" @refresh="fetchInterface" />
    </template>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-block;
  margin-bottom: 16px;
  color: #0066cc;
  font-size: 14px;
  text-decoration: none;
}
.back-link:hover { text-decoration: underline; }
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
</style>

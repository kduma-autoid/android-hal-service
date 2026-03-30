<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import type { PluginDescriptor } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import PluginCard from '../components/PluginCard.vue';

const { client, isConnected } = useHalClient();

const plugins = ref<PluginDescriptor[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const withSuper = ref(false);
const withExperimental = ref(false);

const sortedPlugins = computed(() =>
  [...plugins.value].sort((a, b) => a.name.localeCompare(b.name))
);

async function fetchDescribe() {
  if (!client.value || !isConnected.value) return;

  loading.value = true;
  error.value = null;
  try {
    const result = await client.value.getDescribe({
      withSuper: withSuper.value,
      withExperimental: withExperimental.value,
    });
    plugins.value = result.plugins;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    plugins.value = [];
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (isConnected.value) fetchDescribe();
});

watch(isConnected, (connected) => {
  if (connected) fetchDescribe();
  else plugins.value = [];
});
</script>

<template>
  <h2>API Explorer</h2>

  <div v-if="!isConnected" class="not-connected">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>

  <template v-else>
    <div class="toolbar">
      <label class="checkbox">
        <input v-model="withSuper" type="checkbox" @change="fetchDescribe" />
        Include super methods
      </label>
      <label class="checkbox">
        <input v-model="withExperimental" type="checkbox" @change="fetchDescribe" />
        Include experimental
      </label>
      <button class="btn" @click="fetchDescribe" :disabled="loading">
        {{ loading ? 'Loading...' : 'Refresh' }}
      </button>
    </div>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="loading && !plugins.length" class="loading">Loading API description...</div>

    <div v-if="sortedPlugins.length" class="plugins">
      <p class="count">{{ sortedPlugins.length }} plugin(s)</p>
      <PluginCard v-for="p in sortedPlugins" :key="p.pluginId" :plugin="p" />
    </div>

    <div v-if="!loading && !error && !sortedPlugins.length" class="empty">
      No plugins found.
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
.checkbox {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  cursor: pointer;
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

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import type { PluginDescriptor } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import PluginCard from '../components/PluginCard.vue';

const route = useRoute();
const { client, isConnected } = useHalClient();

const plugin = ref<PluginDescriptor | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

const pluginId = computed(() => route.params.pluginId as string);
const withSuper = computed(() => route.query.withSuper === 'true');
const withExperimental = computed(() => route.query.withExperimental === 'true');

const backLink = computed(() => {
  const query: Record<string, string> = {};
  if (withSuper.value) query.withSuper = 'true';
  if (withExperimental.value) query.withExperimental = 'true';
  return { name: 'describe', query };
});

async function fetchPlugin() {
  if (!client.value || !isConnected.value) return;

  loading.value = true;
  error.value = null;
  try {
    const result = await client.value.getDescribe({
      withSuper: withSuper.value,
      withExperimental: withExperimental.value,
    });
    const found = result.plugins.find(p => p.pluginId === pluginId.value);
    if (found) {
      plugin.value = found;
    } else {
      error.value = `Plugin not found: ${pluginId.value}`;
      plugin.value = null;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    plugin.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (isConnected.value) fetchPlugin();
});

watch(isConnected, (connected) => {
  if (connected) fetchPlugin();
  else plugin.value = null;
});

watch(pluginId, () => {
  fetchPlugin();
});
</script>

<template>
  <div>
    <router-link :to="backLink" class="back-link">&larr; Back to API Explorer</router-link>

    <div v-if="!isConnected" class="not-connected">
      Not connected.
      <router-link to="/settings">Go to Settings</router-link>
      to configure and connect.
    </div>

    <template v-else>
      <div v-if="error" class="error">{{ error }}</div>

      <div v-if="loading && !plugin" class="loading">Loading plugin details...</div>

      <PluginCard v-if="plugin" :plugin="plugin" />
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

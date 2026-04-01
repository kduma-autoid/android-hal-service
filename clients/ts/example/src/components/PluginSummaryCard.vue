<script setup lang="ts">
import { computed } from 'vue';
import type { PluginDescriptor } from '@kduma-autoid/hal-client-common';
import { allMethods, allEvents } from '@kduma-autoid/hal-client-common';

const props = defineProps<{
  plugin: PluginDescriptor;
  linkQuery?: Record<string, string>;
}>();

const methodCount = computed(() => allMethods(props.plugin).length);
const eventCount = computed(() => allEvents(props.plugin).length);
const hasActiveExperimental = computed(() =>
  allMethods(props.plugin).some(m => m.experimental && m.experimentalActive) ||
  allEvents(props.plugin).some(e => e.experimental && e.experimentalActive)
);

const detailLink = computed(() => ({
  name: 'describe-detail',
  params: { pluginId: props.plugin.pluginId },
  query: props.linkQuery,
}));
</script>

<template>
  <router-link :to="detailLink" class="card">
    <div class="card-header">
      <h3 class="plugin-name">{{ plugin.name }}</h3>
      <code class="plugin-id">{{ plugin.pluginId }}</code>
      <span class="badge badge-version">v{{ plugin.version }}</span>
      <span v-if="plugin.experimental && plugin.experimentalActive" class="badge badge-experimental-active">experimental (enabled)</span>
      <span v-else-if="plugin.experimental" class="badge badge-experimental">experimental</span>
      <span v-else-if="hasActiveExperimental" class="badge badge-experimental-active">includes experimental</span>
    </div>

    <div v-if="plugin.capabilities.length" class="capabilities">
      <span v-for="cap in plugin.capabilities" :key="cap" class="badge badge-cap">{{ cap }}</span>
    </div>

    <p class="summary">{{ methodCount }} method(s), {{ eventCount }} event(s)</p>
  </router-link>
</template>

<style scoped>
.card {
  display: block;
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
  text-decoration: none;
  color: inherit;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.card:hover {
  border-color: #0066cc;
  box-shadow: 0 2px 8px rgba(0, 102, 204, 0.1);
}
.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.plugin-name {
  margin: 0;
  font-size: 18px;
}
.plugin-id {
  color: #666;
  font-size: 13px;
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.badge-version { background: #e0e7ff; color: #3730a3; }
.badge-experimental { background: #fef3c7; color: #92400e; }
.badge-experimental-active { background: #d1fae5; color: #065f46; }
.badge-cap { background: #ecfdf5; color: #065f46; }
.capabilities {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.summary {
  margin: 0;
  font-size: 13px;
  color: #888;
}
code {
  font-size: 12px;
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
}
</style>
